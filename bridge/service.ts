import { State, UUID } from "./state.ts";
import type { EventDetail } from "./state.ts";
import { staleToken } from "./boundaries.ts";
import type { Authenticate, Send } from "./boundaries.ts";
import { Observer } from "./observer.ts";
import type { ObserverOptions } from "./observer.ts";

export interface ServiceOptions {
  origin: string;
  stateDirectory: string;
  upstream: string;
  authenticate: Authenticate;
  send: Send;
  port?: number;
  observer?: Pick<ObserverOptions, "now" | "monotonic" | "signal" | "reconnect">;
}

export async function createService(options: ServiceOptions) {
  const origin = new URL(options.origin);
  if (origin.origin !== options.origin || origin.protocol !== "https:" || origin.username || origin.password) throw new Error("Public origin must be a bare HTTPS origin");
  const upstream = new URL(options.upstream);
  if (upstream.protocol !== "http:" || !["127.0.0.1", "localhost", "[::1]"].includes(upstream.hostname) || upstream.username || upstream.password) throw new Error("Upstream must be loopback HTTP");
  const state = await State.load(options.stateDirectory, options.observer?.now);
  const counters = { events: 0, delivered: 0, delivery_errors: 0, stale_tokens: 0, prune_errors: 0, request_errors: 0 };
  const observer = new Observer({ ...options.observer, upstream: options.upstream, origin: options.origin, emit: async (event: EventDetail) => {
    await state.addEvent(event);
    counters.events++;
    const devices = await state.devices();
    for (let offset = 0; offset < devices.length; offset += 10) {
      await Promise.all(devices.slice(offset, offset + 10).map(async ([id, token]) => {
        try { await options.send(token, event); counters.delivered++; }
        catch (error) {
          counters.delivery_errors++;
          // A removal that cannot be persisted is retried by the next event; it must not stop later batches.
          if (staleToken(error)) { try { await state.remove(id, token); counters.stale_tokens++; } catch { counters.prune_errors++; } }
        }
      }));
    }
  } });
  const server = Bun.serve({
    hostname: "127.0.0.1", port: options.port ?? 7318, maxRequestBodySize: 8192,
    async fetch(request) {
      const reply = (status: number, data?: unknown) => new Response(data === undefined ? null : JSON.stringify(data), { status, headers: { "Cache-Control": "no-store", "Content-Type": "application/json", "X-Content-Type-Options": "nosniff" } });
      try {
        if (!await options.authenticate(request)) return reply(401, { error: "Unauthorized" });
        const url = new URL(request.url);
        if (request.method === "GET" && url.pathname === "/api/android-push/health") return reply(200, { connected: observer.connected, observer_errors: observer.errors, ...counters, ...await state.metrics() });
        const match = /^\/api\/android-push\/events\/([^/]+)$/.exec(url.pathname);
        if (request.method === "GET" && match) {
          if (!UUID.test(match[1]!)) return reply(404, { error: "Not found" });
          const event = await state.event(match[1]!);
          if (!event) return reply(404, { error: "Not found" });
          let detail: string | undefined;
          try {
            const path = event.kind === "needs_input" ? "/api/pane/prompt" : "/api/pane/conversation";
            const target = new URL(path, options.upstream);
            target.searchParams.set("pane_id", event.pane_id);
            const response = await fetch(target, { redirect: "error", signal: AbortSignal.timeout(5000) });
            if (response.ok && response.body) {
              const chunks: Uint8Array[] = [];
              let bytes = 0;
              for await (const chunk of response.body) {
                bytes += chunk.length;
                if (bytes > 1_048_576) throw new Error("Detail oversized");
                chunks.push(chunk);
              }
              const body = JSON.parse(Buffer.concat(chunks).toString());
              let text: unknown;
              if (event.kind === "needs_input") text = body.prompt?.question || body.prompt?.title;
              else if (Array.isArray(body.turns)) {
                const turn = body.turns.findLast((value: { role?: string }) => value?.role === "assistant");
                if (Array.isArray(turn?.parts)) text = turn.parts.findLast((value: { kind?: string; text?: string }) => value?.kind === "text" && typeof value.text === "string" && value.text.trim())?.text;
              }
              if (typeof text === "string") {
                const first = text.split(/\r?\n/).find(line => line.trim())?.replace(/[\x00-\x1f\x7f\s]+/g, " ").trim();
                if (first) detail = first.length <= 120 ? first : first.slice(0, 119).trimEnd() + "\u2026";
              }
            }
          } catch { /* Optional detail failure leaves the private labels and generic notification intact. */ }
          return reply(200, { ...event, ...(detail ? { detail } : {}) });
        }
        if (url.pathname !== "/api/android-push/devices") return reply(404, { error: "Not found" });
        if (!["POST", "DELETE"].includes(request.method)) return reply(405, { error: "Method not allowed" });
        const requestOrigin = request.headers.get("Origin");
        const site = request.headers.get("Sec-Fetch-Site");
        if ((requestOrigin !== null && requestOrigin !== options.origin) || (site !== null && site !== "same-origin" && site !== "none")) return reply(403, { error: "Origin refused" });
        if (request.headers.get("Content-Type")?.split(";")[0]?.trim().toLowerCase() !== "application/json") return reply(415, { error: "JSON required" });
        if (Number(request.headers.get("Content-Length")) > 8192) return reply(413, { error: "Body too large" });
        if (!request.body) return reply(400, { error: "Invalid device" });
        const chunks: Uint8Array[] = [];
        let bytes = 0;
        for await (const chunk of request.body) { bytes += chunk.length; if (bytes > 8192) return reply(413, { error: "Body too large" }); chunks.push(chunk); }
        let body;
        try { body = JSON.parse(Buffer.concat(chunks).toString()); } catch { return reply(400, { error: "Invalid JSON" }); }
        if (!body || typeof body !== "object" || Array.isArray(body) || typeof body.installation_id !== "string" || !UUID.test(body.installation_id)) return reply(400, { error: "Invalid device" });
        const allowed = request.method === "POST" ? ["installation_id", "fcm_token"] : ["installation_id"];
        if (Object.keys(body).some(key => !allowed.includes(key))) return reply(400, { error: "Invalid device" });
        if (request.method === "POST") {
          if (typeof body.fcm_token !== "string" || body.fcm_token.length < 1 || body.fcm_token.length > 4096 || !/^[A-Za-z0-9_:.-]+$/.test(body.fcm_token)) return reply(400, { error: "Invalid device" });
          await state.register(body.installation_id.toLowerCase(), body.fcm_token);
        } else await state.remove(body.installation_id.toLowerCase());
        return reply(204);
      } catch { counters.request_errors++; return reply(500, { error: "Request failed" }); }
    },
    error() { return new Response('{"error":"Request failed"}', { status: 400, headers: { "Content-Type": "application/json", "Cache-Control": "no-store" } }); },
  });
  observer.start();
  return { server, state, observer, async close() { await observer.stop(); server.stop(true); await state.flush(); } };
}
