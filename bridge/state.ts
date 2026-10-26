import { mkdir, open, rename, chmod, readFile, unlink } from "node:fs/promises";
import { join } from "node:path";

export const TTL_MS = 12 * 60 * 60 * 1000;
export const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
export type Kind = "needs_input" | "finished";
export interface EventDetail {
  event_id: string;
  kind: Kind;
  pane_id: string;
  server_origin: string;
  created_at: number;
  workspace?: string;
  tab?: string;
  agent?: string;
  pane_title?: string;
  detail?: string;
  // Only events for panes on a remote herdr machine carry this.
  machine_id?: string;
}
interface DiskState { devices: Record<string, string>; events: EventDetail[] }

export class State {
  private data: DiskState = { devices: {}, events: [] };
  private serial: Promise<unknown> = Promise.resolve();
  private constructor(private directory: string, private now: () => number) {}

  static async load(directory: string, now = Date.now) {
    const state = new State(directory, now);
    await mkdir(directory, { recursive: true, mode: 0o700 });
    await chmod(directory, 0o700);
    try {
      const parsed = JSON.parse(await readFile(join(directory, "state.json"), "utf8"));
      if (!parsed || typeof parsed.devices !== "object" || !Array.isArray(parsed.events)) throw new Error("Invalid private state");
      const devices: Record<string, string> = {};
      for (const [id, token] of Object.entries(parsed.devices)) {
        if (!UUID.test(id) || typeof token !== "string" || token.length > 4096) throw new Error("Invalid private state");
        devices[id] = token;
      }
      state.data = { devices, events: parsed.events.filter((e: EventDetail) => e && UUID.test(e.event_id) && ["needs_input", "finished"].includes(e.kind) && Number.isFinite(e.created_at)) };
      await chmod(join(directory, "state.json"), 0o600);
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== "ENOENT") throw new Error("Cannot load private state");
    }
    state.prune();
    return state;
  }
  private prune() {
    this.data.events = this.data.events.filter(e => this.now() - e.created_at < TTL_MS).slice(-1000);
  }
  private update(change: () => void) {
    const task = this.serial.then(async () => {
      const previous = structuredClone(this.data);
      change();
      this.prune();
      const temporary = join(this.directory, `state-${crypto.randomUUID()}.tmp`);
      try {
        const file = await open(temporary, "wx", 0o600);
        try { await file.writeFile(JSON.stringify(this.data)); await file.sync(); } finally { await file.close(); }
        await rename(temporary, join(this.directory, "state.json"));
      } catch {
        this.data = previous;
        await unlink(temporary).catch(() => {});
        throw new Error("Cannot persist private state");
      }
    });
    this.serial = task.catch(() => {});
    return task;
  }
  register(id: string, token: string) {
    return this.update(() => {
      if (!(id in this.data.devices) && Object.keys(this.data.devices).length >= 1000) throw new Error("Device capacity reached");
      // A refreshed token belongs to exactly one current installation.
      for (const [other, value] of Object.entries(this.data.devices)) if (other !== id && value === token) delete this.data.devices[other];
      this.data.devices[id] = token;
    });
  }
  remove(id: string, expectedToken?: string) {
    return this.update(() => { if (expectedToken === undefined || this.data.devices[id] === expectedToken) delete this.data.devices[id]; });
  }
  async devices() { await this.serial; return Object.entries(this.data.devices); }
  addEvent(event: EventDetail) { return this.update(() => { if (!this.data.events.some(e => e.event_id === event.event_id)) this.data.events.push(event); }); }
  async event(id: string) { await this.serial; this.prune(); return this.data.events.find(e => e.event_id === id); }
  async metrics() { await this.serial; this.prune(); return { devices: Object.keys(this.data.devices).length, cached_events: this.data.events.length }; }
  async flush() { await this.serial; }
}
