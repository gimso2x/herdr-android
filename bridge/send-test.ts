import { readFile } from "node:fs/promises";
import { join } from "node:path";
import { configuration } from "./server.ts";
import { firebaseSender } from "./boundaries.ts";
import type { FirebaseSender } from "./boundaries.ts";
import { UUID } from "./state.ts";
import type { EventDetail } from "./state.ts";

// Run: bun run bridge/send-test.ts --installation <UUID> --pane qa:fcm-live --event <UUID>
// Reads the registry atomically; never rewrites a running bridge's private state.
export async function sendTest(args: string[]) {
  if (args.length !== 6 || args[0] !== "--installation" || args[2] !== "--pane" || args[4] !== "--event" || !UUID.test(args[1] ?? "") || !UUID.test(args[5] ?? "") || args[3] !== "qa:fcm-live") throw new Error("Invalid single-device arguments");
  const config = configuration();
  const origin = new URL(config.origin);
  if (origin.origin !== config.origin || origin.protocol !== "https:") throw new Error("Invalid public origin");
  const registry = JSON.parse(await readFile(join(config.stateDirectory, "state.json"), "utf8"));
  const token = registry.devices?.[args[1]!.toLowerCase()];
  if (typeof token !== "string" || !token) throw new Error("Test installation not registered");
  const event: EventDetail = { event_id: args[5]!.toLowerCase(), kind: "needs_input", pane_id: args[3]!, server_origin: config.origin, created_at: Date.now() };
  let sender: FirebaseSender | undefined;
  try {
    sender = await firebaseSender(config.serviceAccount);
    await sender.send(token, event);
  } finally { await sender?.close(); }
}

if (import.meta.main) {
  try { await sendTest(process.argv.slice(2)); console.info('{"accepted":true}'); }
  catch { console.error('{"accepted":false}'); process.exitCode = 1; }
}
