import { describe, expect, test } from "bun:test";
import type { ServerWebSocket } from "bun";
import { chmod, mkdtemp, rm, stat, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { createService } from "./service.ts";
import { State, TTL_MS } from "./state.ts";
import type { EventDetail } from "./state.ts";
import { accessAuthenticator } from "./boundaries.ts";
import type { Send } from "./boundaries.ts";

// All waits subscribe before the action and have a failure timeout, never a timing-based success.
class Signals {
  private listeners = new Map<string, Array<() => void>>();
  wait(name: string) {
    let cancel: () => void;
    const promise = new Promise<void>((resolve, reject) => {
      const deadline = AbortSignal.timeout(3000);
      const expired = () => { cancel(); reject(new Error(`Missing event: ${name}`)); };
      deadline.addEventListener("abort", expired, { once: true });
      const listener = () => { deadline.removeEventListener("abort", expired); cancel(); resolve(); };
      const listeners = this.listeners.get(name) ?? [];
      listeners.push(listener);
      this.listeners.set(name, listeners);
      cancel = () => { this.listeners.set(name, (this.listeners.get(name) ?? []).filter(value => value !== listener)); };
    });
    return promise;
  }
  emit(name: string) { for (const listener of [...(this.listeners.get(name) ?? [])]) listener(); }
}

const ORIGIN = "https://herdr.example.com";
const INSTALLATION = "11111111-1111-4111-8111-111111111111";
const pane = (status = "idle", extra = {}) => ({ pane_id: "fixture-pane", agent_status: status, title: "private title", workspace_id: "workspace", tab_id: "tab", ...extra });

async function fixture(send?: Send, initial = pane()) {
  const directory = await mkdtemp(join(tmpdir(), "herdr-push-test-"));
  const signals = new Signals();
  let socket: ServerWebSocket<unknown> | undefined;
  let snapshot = { panes: [initial], workspaces: [{ workspace_id: "workspace", label: "Fixture workspace" }], tabs: [{ tab_id: "tab", label: "Fixture tab" }] };
  let clock = 0;
  let retry: (() => void) | undefined;
  const frames: unknown[] = [];
  const delivered: Array<{ token: string; event: EventDetail }> = [];
  let detailResponse: { status: number; body: unknown } = { status: 404, body: {} };
  const detailRequests: Array<{ method: string; pane: string | null }> = [];
  const upstream = Bun.serve({
    hostname: "127.0.0.1", port: 0,
    fetch(request, server) {
      if (new URL(request.url).pathname === "/ws") { if (server.upgrade(request, { data: {} })) return; return new Response(null, { status: 400 }); }
      if (new URL(request.url).pathname === "/api/session") { signals.emit("snapshot-fetch"); return Response.json({ snapshot }); }
      if (["/api/pane/prompt", "/api/pane/conversation"].includes(new URL(request.url).pathname)) {
        detailRequests.push({ method: request.method, pane: new URL(request.url).searchParams.get("pane_id") });
        return Response.json(detailResponse.body, { status: detailResponse.status });
      }
      return new Response(null, { status: 404 });
    },
    websocket: {
      open(ws) { socket = ws; },
      message(ws, data) {
        const frame = JSON.parse(data.toString());
        frames.push(frame);
        if (frame.type === "role" && frame.mode === "observe") ws.send(JSON.stringify({ type: "snapshot", snapshot }));
      },
    },
  });
  const seeded = signals.wait("snapshot");
  const service = await createService({
    origin: ORIGIN, upstream: `http://127.0.0.1:${upstream.port}`, port: 0, stateDirectory: directory,
    authenticate: async request => request.headers.get("Cf-Access-Jwt-Assertion") === "test-verified",
    send: async (token, event) => { delivered.push({ token, event }); signals.emit("send"); if (send) await send(token, event); },
    observer: { monotonic: () => clock, signal: type => signals.emit(type), reconnect: callback => { retry = callback; return () => { retry = undefined; }; } },
  });
  await seeded;
  const request = (path: string, method = "GET", body?: unknown, headers: Record<string, string> = {}) => fetch(`http://127.0.0.1:${service.server.port}/api/android-push${path}`, { method, headers: { "Cf-Access-Jwt-Assertion": "test-verified", "Content-Type": "application/json", ...headers }, ...(body === undefined ? {} : { body: typeof body === "string" ? body : JSON.stringify(body) }) });
  const status = async (agent_status: string, extra = {}) => {
    const done = signals.wait("pane-status");
    socket!.send(JSON.stringify({ type: "pane-status", pane_id: "fixture-pane", agent_status, ...extra }));
    await done;
  };
  return {
    service, directory, signals, delivered, frames, request, status,
    detailRequests,
    detail(body: unknown, status = 200) { detailResponse = { body, status }; },
    rawStatus(agent_status: string) { socket!.send(JSON.stringify({ type: "pane-status", pane_id: "fixture-pane", agent_status })); },
    advance(ms: number) { clock += ms; },
    async register(token = "token-old") { return request("/devices", "POST", { installation_id: INSTALLATION, fcm_token: token }); },
    async exit() { const done = signals.wait("pane-exited"); socket!.send(JSON.stringify({ type: "pane-exited", pane_id: "fixture-pane" })); await done; },
    async reconnect(next: typeof initial) {
      const closed = signals.wait("disconnected"); socket!.close(); await closed;
      snapshot = { ...snapshot, panes: [next] };
      const seeded = signals.wait("snapshot"); retry!(); await seeded;
    },
    async resync(next: typeof initial) {
      snapshot = { ...snapshot, panes: [next] };
      const refreshed = signals.wait("resynced");
      socket!.send(JSON.stringify({ type: "session-changed" }));
      await refreshed;
    },
    async close() { await service.close(); upstream.stop(true); await rm(directory, { recursive: true, force: true }); },
  };
}

describe("authenticated private HTTP API", () => {
  test("rejects unauthenticated, malformed, oversized and cross-origin registration without leaking input", async () => {
    const f = await fixture();
    try {
      expect((await f.request("/health", "GET", undefined, { "Cf-Access-Jwt-Assertion": "" })).status).toBe(401);
      expect((await f.request("/devices", "POST", "{secret-token")).status).toBe(400);
      expect((await f.request("/devices", "POST", { installation_id: "bad", fcm_token: "secret-token" })).status).toBe(400);
      expect((await f.request("/devices", "POST", { installation_id: INSTALLATION, fcm_token: "secret-token", service_account: "secret" })).status).toBe(400);
      expect((await f.request("/devices", "POST", { installation_id: INSTALLATION, fcm_token: "secret-token" }, { Origin: "https://attacker.example" })).status).toBe(403);
      expect((await f.request("/devices", "POST", { installation_id: INSTALLATION, fcm_token: "secret-token" }, { "Sec-Fetch-Site": "cross-site" })).status).toBe(403);
      expect((await f.request("/devices", "POST", "{}", { "Content-Type": "text/plain" })).status).toBe(415);
      // Valid JSON, so only the size limit can refuse it.
      expect((await f.request("/devices", "POST", { installation_id: INSTALLATION, fcm_token: "secret-token", pad: "x".repeat(9000) })).status).toBe(413);
      expect((await f.request("/devices", "POST", { installation_id: INSTALLATION, fcm_token: "bad token" })).status).toBe(400);
      expect(await f.service.state.devices()).toEqual([]);
      const health = await (await f.request("/health")).text();
      expect(health).not.toContain("secret-token");
      expect(health).not.toContain("private title");
      expect((await f.request("/broadcast", "POST", {})).status).toBe(404);
    } finally { await f.close(); }
  });

  test("idempotent registration rotates privately, persists mode600, and unregisters", async () => {
    const f = await fixture();
    try {
      expect((await f.register()).status).toBe(204);
      expect((await f.register()).status).toBe(204);
      expect((await f.register("token-new")).status).toBe(204);
      expect(await f.service.state.devices()).toEqual([[INSTALLATION, "token-new"]]);
      expect((await stat(join(f.directory, "state.json"))).mode & 0o777).toBe(0o600);
      expect((await stat(f.directory)).mode & 0o777).toBe(0o700);
      const persisted = await State.load(f.directory);
      expect(await persisted.devices()).toEqual([[INSTALLATION, "token-new"]]);
      expect((await f.request("/devices", "DELETE", { installation_id: INSTALLATION })).status).toBe(204);
      expect((await f.request("/devices", "DELETE", { installation_id: INSTALLATION })).status).toBe(204);
      expect(await f.service.state.devices()).toEqual([]);
    } finally { await f.close(); }
  });

  test("event details are private, authenticated, bounded and expire after twelve hours", async () => {
    const f = await fixture();
    try {
      await f.register(); await f.status("blocked");
      const event = f.delivered[0]!.event;
      expect(typeof event.created_at).toBe("number");
      const response = await f.request(`/events/${event.event_id}`);
      expect(response.headers.get("Cache-Control")).toBe("no-store");
      const detail = await response.json();
      expect(detail.pane_title).toBe("private title");
      expect(detail.workspace).toBe("Fixture workspace");
      expect(detail.tab).toBe("Fixture tab");
      expect((await f.request(`/events/${event.event_id}`, "GET", undefined, { "Cf-Access-Jwt-Assertion": "" })).status).toBe(401);
      expect((await f.request(`/events/${crypto.randomUUID()}`)).status).toBe(404);
      expect((await f.request("/events/not-a-uuid")).status).toBe(404);
      let now = 100;
      const temporary = await mkdtemp(join(tmpdir(), "herdr-event-cache-"));
      try {
        const cache = await State.load(temporary, () => now);
        await cache.addEvent({ ...event, created_at: now });
        await cache.addEvent({ ...event, created_at: now });
        expect((await cache.metrics()).cached_events).toBe(1);
        now += TTL_MS;
        expect(await cache.event(event.event_id)).toBeUndefined();
        // Seed a realistic persisted cache in one write, rather than 1001 disk syncs.
        await writeFile(join(temporary, "state.json"), JSON.stringify({ devices: {}, events: Array.from({ length: 1002 }, () => ({ ...event, event_id: crypto.randomUUID(), created_at: now })) }), { mode: 0o600 });
        const bounded = await State.load(temporary, () => now);
        expect((await bounded.metrics()).cached_events).toBe(1000);
      } finally { await rm(temporary, { recursive: true, force: true }); }
    } finally { await f.close(); }
  });
});

test("private detail fetch stays read-only and does not reach back to an older assistant turn", async () => {
  const f = await fixture();
  try {
    await f.register(); await f.status("blocked");
    const input = f.delivered[0]!.event;
    f.detail({ prompt: { question: "\nProceed with this task?\nprivate second line" } });
    expect((await (await f.request(`/events/${input.event_id}`)).json()).detail).toBe("Proceed with this task?");
    await f.status("working"); f.advance(60000); await f.status("done");
    const finish = f.delivered[1]!.event;
    f.detail({ turns: [{ role: "assistant", parts: [{ kind: "text", text: "old answer" }] }, { role: "assistant", parts: [{ kind: "text", text: "New answer.\nsecond line" }] }] });
    expect((await (await f.request(`/events/${finish.event_id}`)).json()).detail).toBe("New answer.");
    f.detail({ turns: [{ role: "assistant", parts: [{ kind: "text", text: "old answer" }] }, { role: "assistant", parts: [{ kind: "tool", text: "not an answer" }] }] });
    expect((await (await f.request(`/events/${finish.event_id}`)).json()).detail).toBeUndefined();
    f.detail({ prompt: { question: "x".repeat(1_048_577) } });
    const oversized = await (await f.request(`/events/${input.event_id}`)).json();
    expect(oversized.detail).toBeUndefined();
    expect(oversized.workspace).toBe("Fixture workspace");
    expect(f.detailRequests).toEqual(Array.from({ length: 4 }, () => ({ method: "GET", pane: "fixture-pane" })));
  } finally { await f.close(); }
});

describe("read-only live observer", () => {
  test("deduplicates blocked delivery and measures completion with a monotonic sixty-second boundary", async () => {
    const f = await fixture();
    try {
      await f.register();
      await f.status("working"); f.advance(59999); await f.status("done");
      expect(f.delivered).toHaveLength(0);
      await f.status("working"); f.advance(60000); await f.status("done");
      await f.status("done"); await f.status("idle");
      expect(f.delivered.map(x => x.event.kind)).toEqual(["finished"]);
      await f.status("blocked"); await f.status("blocked");
      expect(f.delivered.map(x => x.event.kind)).toEqual(["finished", "needs_input"]);
      expect(new Set(f.delivered.map(x => x.event.event_id)).size).toBe(2);
      expect(f.frames).toEqual([{ type: "role", mode: "observe" }]);
    } finally { await f.close(); }
  });

  test("a transient unknown frame cannot erase the measured active turn", async () => {
    const f = await fixture();
    try {
      await f.register(); await f.status("working"); f.advance(60000);
      f.rawStatus("unknown");
      await f.status("done");
      expect(f.delivered.map(x => x.event.kind)).toEqual(["finished"]);
    } finally { await f.close(); }
  });

  test("background_wait defers completion but does not suppress an input request", async () => {
    const f = await fixture();
    try {
      await f.register(); await f.status("working"); f.advance(60000);
      await f.status("done", { background_wait: true });
      expect(f.delivered).toHaveLength(0);
      await f.status("blocked", { background_wait: true });
      expect(f.delivered.map(x => x.event.kind)).toEqual(["needs_input"]);
      await f.status("done");
      expect(f.delivered.map(x => x.event.kind)).toEqual(["needs_input", "finished"]);
      await f.status("done"); expect(f.delivered).toHaveLength(2);
    } finally { await f.close(); }
  });

  test("unknown initial busy and reconnect seed cannot fabricate finish or blocked spam", async () => {
    const f = await fixture(undefined, pane("working"));
    try {
      await f.register(); f.advance(60000); await f.status("done");
      expect(f.delivered).toHaveLength(0);
      await f.status("working"); f.advance(60000);
      await f.reconnect(pane("blocked"));
      await f.status("blocked"); await f.status("done");
      expect(f.delivered).toHaveLength(0);
      expect(f.frames).toEqual([{ type: "role", mode: "observe" }, { type: "role", mode: "observe" }]);
    } finally { await f.close(); }
  });

  test("structure resync retains only continuous known active turns without status-history spam", async () => {
    const f = await fixture();
    try {
      await f.register(); await f.status("working"); f.advance(60000);
      await f.resync(pane("working")); await f.status("done");
      expect(f.delivered.map(x => x.event.kind)).toEqual(["finished"]);
      await f.resync(pane("blocked")); await f.status("blocked"); await f.status("done");
      expect(f.delivered).toHaveLength(1);
      expect(f.frames).toEqual([{ type: "role", mode: "observe" }]);
    } finally { await f.close(); }
  });

  test("pane exit requires known long active turn and duplicate exits are idempotent", async () => {
    const f = await fixture();
    try {
      await f.register(); await f.exit(); expect(f.delivered).toHaveLength(0);
      await f.resync(pane("idle")); await f.status("working"); f.advance(60000);
      await f.exit(); await f.exit(); expect(f.delivered.map(x => x.event.kind)).toEqual(["finished"]);
    } finally { await f.close(); }
  });

  test("UNREGISTERED response removes only the sent token, never a concurrent rotation", async () => {
    const entered = new Signals();
    let release!: () => void;
    const held = new Promise<void>(resolve => { release = resolve; });
    const f = await fixture(async token => { if (token === "token-old") { entered.emit("sending"); await held; } throw { code: "messaging/registration-token-not-registered", message: "private-token" }; });
    try {
      await f.register();
      const sending = entered.wait("sending");
      const transition = f.status("blocked");
      await sending;
      expect((await f.register("token-new")).status).toBe(204);
      release(); await transition;
      expect(await f.service.state.devices()).toEqual([[INSTALLATION, "token-new"]]);
      await f.status("idle"); await f.status("blocked");
      expect(await f.service.state.devices()).toEqual([]);
      const health = await (await f.request("/health")).text();
      expect(health).not.toContain("private-token");
      expect(JSON.parse(health).stale_tokens).toBe(2);
    } finally { release(); await f.close(); }
  });
});

test("a stale token whose removal cannot be persisted does not stop delivery to later devices", async () => {
  let directory = "";
  // The event is already persisted when sending starts; only the stale-token removal hits a read-only directory.
  const f = await fixture(async token => { if (token === "token-stale") { await chmod(directory, 0o500); throw { code: "messaging/registration-token-not-registered" }; } });
  directory = f.directory;
  try {
    expect((await f.register("token-stale")).status).toBe(204);
    const others = Array.from({ length: 10 }, (_, i) => `00000000-0000-4000-8000-${String(i).padStart(12, "0")}`);
    for (const [i, id] of others.entries()) expect((await f.request("/devices", "POST", { installation_id: id, fcm_token: `token-${i}` })).status).toBe(204);
    try { await f.status("blocked"); } finally { await chmod(directory, 0o700); }
    expect(f.delivered.map(x => x.token).sort()).toEqual(["token-stale", ...others.map((_, i) => `token-${i}`)].sort());
    expect(JSON.parse(await (await f.request("/health")).text())).toMatchObject({ prune_errors: 1, stale_tokens: 0 });
  } finally { await f.close(); }
});

test("production authenticator cannot accept missing, malformed or untrusted-cookie claims", async () => {
  const authenticate = accessAuthenticator("test-team", "test-audience");
  expect(await authenticate(new Request("http://127.0.0.1", { headers: { Cookie: "CF_Authorization=untrusted" } }))).toBe(false);
  expect(await authenticate(new Request("http://127.0.0.1", { headers: { "Cf-Access-Jwt-Assertion": "not-a-jwt" } }))).toBe(false);
  expect(() => accessAuthenticator("", "")).toThrow();
});
