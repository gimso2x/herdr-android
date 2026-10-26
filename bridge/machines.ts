import type { EventDetail, Kind } from "./state.ts";

// Notifications for panes on REMOTE herdr machines. herdr-web-ui multiplexes every machine into
// one SSE stream (GET /api/machines/events): `machines` frames carry each machine's state and
// snapshot, `machine-message` frames carry that machine's live websocket messages. The local
// machine ("local") is covered by the /ws Observer and is ignored here so nothing fires twice.
// Status rules deliberately mirror observer.ts.

type Pane = Record<string, unknown> & { pane_id: string; agent_status: string; background_wait?: boolean };
interface Tracked { machine: string; pane: Pane; busy: boolean; blocked: boolean; start?: number }
export interface MachinesOptions {
  upstream: string;
  origin: string;
  emit: (event: EventDetail) => Promise<void>;
  monotonic?: () => number;
  now?: () => number;
  signal?: (type: string) => void;
  reconnect?: (callback: () => void, delay: number) => () => void;
}

const MAX_BYTES = 2 * 1024 * 1024;
const STATUSES = ["idle", "working", "blocked", "done"];
const trackingKey = (machine: string, paneId: string) => `${machine}\u0000${paneId}`;
const validMachineId = (value: unknown): value is string => typeof value === "string" && value.length > 0 && value.length <= 512 && value !== "local";

export class MachinesSource {
  connected = false;
  errors = 0;
  private stopped = false;
  private generation = 0;
  private attempt = 0;
  private controller?: AbortController;
  private cancelRetry?: () => void;
  private serial = Promise.resolve();
  private tracked = new Map<string, Tracked>();
  private seeded = new Set<string>();
  // Panes removed by a live pane-exited; an older snapshot must not bring them back.
  private gone = new Set<string>();
  constructor(private options: MachinesOptions) {}
  start() { this.connect(); }
  private reset() {
    this.tracked.clear();
    this.seeded.clear();
    this.gone.clear();
  }
  private connect() {
    if (this.stopped) return;
    const generation = ++this.generation;
    this.reset();
    const controller = this.controller = new AbortController();
    void (async () => {
      try {
        const response = await fetch(new URL("/api/machines/events", this.options.upstream), { signal: controller.signal, headers: { accept: "text/event-stream" }, redirect: "error" });
        if (generation !== this.generation) return;
        if (!response.ok || !response.body || !(response.headers.get("content-type") ?? "").includes("text/event-stream")) {
          await response.body?.cancel().catch(() => {});
          throw new Error("Machines stream unavailable");
        }
        this.connected = true;
        this.options.signal?.("machines-connected");
        const decoder = new TextDecoder("utf-8");
        let buffer = "";
        for await (const chunk of response.body) {
          if (generation !== this.generation) return;
          const received = (this.options.monotonic ?? (() => performance.now()))();
          buffer += decoder.decode(chunk, { stream: true });
          let newline: number;
          while ((newline = buffer.indexOf("\n")) >= 0) {
            const line = buffer.slice(0, newline).replace(/\r$/, "");
            buffer = buffer.slice(newline + 1);
            this.enqueue(line, received, generation);
          }
          if (buffer.length > MAX_BYTES) throw new Error("Frame oversized");
        }
        throw new Error("Machines stream ended");
      } catch {
        if (generation === this.generation && !this.stopped) this.lost(generation);
      }
    })();
  }
  private lost(generation: number) {
    if (generation !== this.generation || this.stopped) return;
    this.connected = false;
    this.errors++;
    this.controller?.abort();
    this.reset();
    // Invalidate queued frames from this connection before scheduling another.
    this.generation++;
    const delay = Math.min(30000, 250 * 2 ** Math.min(this.attempt++, 7));
    const reconnect = this.options.reconnect ?? ((callback, delay) => { const timer = setTimeout(callback, delay); return () => clearTimeout(timer); });
    this.cancelRetry = reconnect(() => this.connect(), delay);
    this.options.signal?.("machines-disconnected");
  }
  private enqueue(line: string, received: number, generation: number) {
    if (!line.startsWith("data:")) return;
    if (line.length > MAX_BYTES) { this.lost(generation); return; }
    this.serial = this.serial.then(async () => {
      if (generation !== this.generation || this.stopped) return;
      let event;
      try { event = JSON.parse(line.slice(5).trimStart()); } catch { this.errors++; return; }
      if (!event || typeof event !== "object") return;
      if (event.type === "machines" && Array.isArray(event.machines)) {
        this.machines(event.machines);
        this.attempt = 0;
        this.options.signal?.("machines-frame");
      } else if (event.type === "machine-message") {
        if (validMachineId(event.machine_id) && event.message && typeof event.message === "object") await this.message(event.machine_id, event.message, received);
        this.options.signal?.("machines-message");
      }
    }).catch(() => { this.errors++; this.options.signal?.("machines-error"); });
  }
  private machines(list: unknown[]) {
    const connected = new Set<string>();
    for (const machine of list) {
      if (!machine || typeof machine !== "object") continue;
      const { id, state, snapshot } = machine as { id?: unknown; state?: unknown; snapshot?: unknown };
      if (!validMachineId(id) || state !== "connected") continue;
      connected.add(id);
      if (!snapshot) continue;
      try { this.reconcile(id, snapshot); } catch { this.errors++; }
    }
    // A machine that left the list or is not connected speaks for no pane any more.
    for (const machine of [...this.seeded]) if (!connected.has(machine)) this.forget(machine);
  }
  private forget(machine: string) {
    this.seeded.delete(machine);
    for (const [key, state] of this.tracked) if (state.machine === machine) { this.tracked.delete(key); this.gone.delete(key); }
  }
  private reconcile(machine: string, snapshot: unknown) {
    if (!snapshot || typeof snapshot !== "object" || !("panes" in snapshot) || !Array.isArray(snapshot.panes) || snapshot.panes.length > 10000) throw new Error("Invalid snapshot");
    const workspaceLabels = new Map<string, string>();
    const tabLabels = new Map<string, string>();
    if ("workspaces" in snapshot && Array.isArray(snapshot.workspaces)) {
      for (const workspace of snapshot.workspaces) if (workspace && typeof workspace.workspace_id === "string" && typeof workspace.label === "string") workspaceLabels.set(workspace.workspace_id, workspace.label);
    }
    if ("tabs" in snapshot && Array.isArray(snapshot.tabs)) {
      for (const tab of snapshot.tabs) if (tab && typeof tab.tab_id === "string" && typeof tab.label === "string") tabLabels.set(tab.tab_id, tab.label);
    }
    const present = new Set<string>();
    for (const value of snapshot.panes) {
      if (!value || typeof value.pane_id !== "string" || !value.pane_id || value.pane_id.length > 512 || typeof value.agent_status !== "string" || value.agent_status.length > 64) continue;
      const key = trackingKey(machine, value.pane_id);
      present.add(key);
      if (this.gone.has(key)) continue;
      const labels = { workspace_label: workspaceLabels.get(value.workspace_id), tab_label: tabLabels.get(value.tab_id) };
      const previous = this.tracked.get(key);
      if (previous) {
        // Live events are newer than any snapshot: keep their status, only refresh descriptive fields.
        const { agent_status, background_wait } = previous.pane;
        this.tracked.set(key, { ...previous, pane: { ...value, ...labels, agent_status, background_wait } as Pane });
        continue;
      }
      const pane = { ...value, ...labels } as Pane;
      const busy = pane.background_wait === true || ["working", "blocked"].includes(pane.agent_status);
      this.tracked.set(key, { machine, pane, busy, blocked: pane.agent_status === "blocked", start: undefined });
    }
    for (const [key, state] of this.tracked) if (state.machine === machine && !present.has(key)) this.tracked.delete(key);
    for (const key of this.gone) if (key.startsWith(`${machine}\u0000`) && !present.has(key)) this.gone.delete(key);
    this.seeded.add(machine);
  }
  private async message(machine: string, message: Record<string, unknown>, received: number) {
    if (message.type === "snapshot") {
      try { this.reconcile(machine, message.snapshot); } catch { this.errors++; }
      return;
    }
    if (!this.seeded.has(machine) || typeof message.pane_id !== "string") return;
    const key = trackingKey(machine, message.pane_id);
    const state = this.tracked.get(key);
    if (message.type === "pane-status" && typeof message.agent_status === "string" && message.agent_status.length <= 64) {
      if (!STATUSES.includes(message.agent_status) || !state) return;
      const pane = { ...state.pane, agent_status: message.agent_status, background_wait: message.background_wait === true };
      const busy = pane.background_wait || ["working", "blocked"].includes(pane.agent_status);
      const blocked = pane.agent_status === "blocked";
      const start = busy ? state.busy ? state.start : received : undefined;
      this.tracked.set(key, { machine, pane, busy, blocked, start });
      if (blocked && !state.blocked) await this.notify(machine, pane, "needs_input");
      if (!busy && state.busy && state.start !== undefined && received - state.start >= 60000 && ["idle", "done"].includes(pane.agent_status)) await this.notify(machine, pane, "finished");
    } else if (message.type === "pane-exited") {
      this.tracked.delete(key);
      if (state) this.gone.add(key);
      if (state?.busy && state.start !== undefined && received - state.start >= 60000) await this.notify(machine, state.pane, "finished");
    }
  }
  private async notify(machine: string, pane: Pane, kind: Kind) {
    const event: EventDetail = { event_id: crypto.randomUUID(), kind, pane_id: pane.pane_id, server_origin: this.options.origin, created_at: (this.options.now ?? Date.now)(), machine_id: machine };
    for (const [field, value] of [["workspace", pane.workspace_label], ["tab", pane.tab_label], ["agent", pane.display_agent ?? pane.agent], ["pane_title", pane.terminal_title_stripped ?? pane.title ?? pane.label]] as const) {
      if (typeof value === "string") event[field] = value.slice(0, 4096);
    }
    await this.options.emit(event);
  }
  async stop() {
    this.stopped = true;
    this.generation++;
    this.connected = false;
    this.cancelRetry?.();
    this.controller?.abort();
    await this.serial;
  }
}
