import type { EventDetail, Kind } from "./state.ts";

type Pane = Record<string, unknown> & { pane_id: string; agent_status: string; background_wait?: boolean };
interface Tracked { pane: Pane; busy: boolean; blocked: boolean; start?: number }
export interface ObserverOptions {
  upstream: string;
  origin: string;
  emit: (event: EventDetail) => Promise<void>;
  monotonic?: () => number;
  now?: () => number;
  signal?: (type: string) => void;
  reconnect?: (callback: () => void, delay: number) => () => void;
}

export class Observer {
  connected = false;
  errors = 0;
  private socket?: WebSocket;
  private stopped = false;
  private cancelRetry?: () => void;
  private attempt = 0;
  private generation = 0;
  private tracked = new Map<string, Tracked>();
  private seeded = false;
  private serial = Promise.resolve();
  private refresh?: AbortController;
  private revision = 0;
  private newer = new Map<string, number>();
  constructor(private options: ObserverOptions) {}
  start() { this.connect(); }
  private connect() {
    if (this.stopped) return;
    const generation = ++this.generation;
    this.seeded = false;
    this.tracked.clear();
    this.newer.clear();
    const url = new URL("/ws", this.options.upstream);
    url.protocol = url.protocol === "https:" ? "wss:" : "ws:";
    const socket = this.socket = new WebSocket(url);
    socket.onopen = () => {
      if (generation !== this.generation) return;
      this.connected = true;
      socket.send(JSON.stringify({ type: "role", mode: "observe" }));
      this.options.signal?.("connected");
    };
    socket.onmessage = frame => {
      const received = (this.options.monotonic ?? (() => performance.now()))();
      this.serial = this.serial.then(async () => {
        if (generation !== this.generation || this.stopped) return;
        if (typeof frame.data !== "string" || frame.data.length > 2 * 1024 * 1024) { socket.close(1009); return; }
        let message;
        try { message = JSON.parse(frame.data); } catch { this.errors++; return; }
        if (!message || typeof message !== "object") return;
        if (message.type === "snapshot") {
          this.seed(message.snapshot);
          this.attempt = 0;
        } else if (message.type === "pane-status" && this.seeded && typeof message.pane_id === "string" && typeof message.agent_status === "string" && message.agent_status.length <= 64) {
          if (!["idle", "working", "blocked", "done"].includes(message.agent_status)) return;
          const state = this.tracked.get(message.pane_id);
          if (state) {
            this.newer.set(message.pane_id, ++this.revision);
            const pane = { ...state.pane, agent_status: message.agent_status, background_wait: message.background_wait === true };
            const busy = pane.background_wait || ["working", "blocked"].includes(pane.agent_status);
            const blocked = pane.agent_status === "blocked";
            const start = busy ? state.busy ? state.start : received : undefined;
            this.tracked.set(pane.pane_id, { pane, busy, blocked, start });
            if (blocked && !state.blocked) await this.notify(pane, "needs_input");
            if (!busy && state.busy && state.start !== undefined && received - state.start >= 60000 && ["idle", "done"].includes(pane.agent_status)) await this.notify(pane, "finished");
          }
        } else if (message.type === "pane-exited" && this.seeded) {
          const state = this.tracked.get(message.pane_id);
          this.tracked.delete(message.pane_id);
          if (state) this.newer.set(message.pane_id, ++this.revision);
          if (state?.busy && state.start !== undefined && received - state.start >= 60000) await this.notify(state.pane, "finished");
        } else if (message.type === "session-changed" && this.seeded) {
          this.refreshSnapshot(generation);
        }
        this.options.signal?.(message.type);
      }).catch(() => { this.errors++; this.options.signal?.("error"); if (!this.seeded && generation === this.generation) socket.close(); });
    };
    socket.onerror = () => { this.errors++; socket.close(); };
    socket.onclose = () => {
      if (generation !== this.generation || this.stopped) return;
      this.connected = false;
      this.seeded = false;
      this.tracked.clear();
      this.refresh?.abort();
      // Invalidate queued frames from this connection before scheduling another.
      this.generation++;
      const delay = Math.min(30000, 250 * 2 ** Math.min(this.attempt++, 7));
      const reconnect = this.options.reconnect ?? ((callback, delay) => { const timer = setTimeout(callback, delay); return () => clearTimeout(timer); });
      this.cancelRetry = reconnect(() => this.connect(), delay);
      this.options.signal?.("disconnected");
    };
  }
  private seed(snapshot: unknown, since?: number) {
    if (!snapshot || typeof snapshot !== "object" || !("panes" in snapshot) || !Array.isArray(snapshot.panes) || snapshot.panes.length > 10000) throw new Error("Invalid snapshot");
    const next = new Map<string, Tracked>();
    const workspaceLabels = new Map<string, string>();
    const tabLabels = new Map<string, string>();
    if ("workspaces" in snapshot && Array.isArray(snapshot.workspaces)) {
      for (const workspace of snapshot.workspaces) {
        if (workspace && typeof workspace.workspace_id === "string" && typeof workspace.label === "string") workspaceLabels.set(workspace.workspace_id, workspace.label);
      }
    }
    if ("tabs" in snapshot && Array.isArray(snapshot.tabs)) {
      for (const tab of snapshot.tabs) {
        if (tab && typeof tab.tab_id === "string" && typeof tab.label === "string") tabLabels.set(tab.tab_id, tab.label);
      }
    }
    for (const value of snapshot.panes) {
      if (!value || typeof value.pane_id !== "string" || !value.pane_id || value.pane_id.length > 512 || typeof value.agent_status !== "string" || value.agent_status.length > 64) continue;
      const pane = { ...value, workspace_label: workspaceLabels.get(value.workspace_id), tab_label: tabLabels.get(value.tab_id) } as Pane;
      const previous = this.tracked.get(pane.pane_id);
      if (since !== undefined && (this.newer.get(pane.pane_id) ?? 0) > since) { if (previous) next.set(pane.pane_id, previous); continue; }
      const busy = pane.background_wait === true || ["working", "blocked"].includes(pane.agent_status);
      next.set(pane.pane_id, { pane, busy, blocked: pane.agent_status === "blocked", start: busy && previous?.busy ? previous.start : undefined });
    }
    // A pane added by a newer live event must not be removed by an older structure response.
    if (since !== undefined) for (const [id, previous] of this.tracked) if ((this.newer.get(id) ?? 0) > since) next.set(id, previous);
    this.tracked = next;
    this.seeded = true;
    this.newer.clear();
  }
  private refreshSnapshot(generation: number) {
    this.refresh?.abort();
    const controller = this.refresh = new AbortController();
    const since = this.revision;
    void (async () => {
      try {
        const response = await fetch(new URL("/api/session", this.options.upstream), { signal: AbortSignal.any([controller.signal, AbortSignal.timeout(10000)]), redirect: "error" });
        if (!response.ok || !response.body) throw new Error("Snapshot unavailable");
        let bytes = 0;
        const chunks: Uint8Array[] = [];
        for await (const chunk of response.body) { bytes += chunk.length; if (bytes > 2 * 1024 * 1024) throw new Error("Snapshot oversized"); chunks.push(chunk); }
        const result = JSON.parse(Buffer.concat(chunks).toString());
        this.serial = this.serial.then(() => {
          if (generation === this.generation && !controller.signal.aborted && !this.stopped) { this.seed(result.snapshot, since); this.options.signal?.("resynced"); }
        }).catch(() => { this.errors++; if (generation === this.generation && !controller.signal.aborted) this.socket?.close(); });
      } catch { if (!controller.signal.aborted && generation === this.generation) { this.errors++; this.options.signal?.("resync-error"); this.socket?.close(); } }
    })();
  }
  private async notify(pane: Pane, kind: Kind) {
    const event: EventDetail = { event_id: crypto.randomUUID(), kind, pane_id: pane.pane_id, server_origin: this.options.origin, created_at: (this.options.now ?? Date.now)() };
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
    this.refresh?.abort();
    this.socket?.close();
    await this.serial;
  }
}
