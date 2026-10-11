import { join } from "node:path";
import { homedir } from "node:os";
import { accessAuthenticator, firebaseSender } from "./boundaries.ts";
import type { FirebaseSender } from "./boundaries.ts";
import { createService } from "./service.ts";

export function configuration() {
  const port = Number(process.env.HERDR_PUSH_PORT ?? 7318);
  if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error("Invalid bridge port");
  return {
    port,
    origin: process.env.HERDR_PUSH_PUBLIC_ORIGIN ?? "",
    stateDirectory: process.env.HERDR_PUSH_STATE_DIR ?? join(homedir(), ".config/herdr-android/push-state"),
    serviceAccount: process.env.HERDR_PUSH_SERVICE_ACCOUNT ?? join(homedir(), ".config/herdr-android/fcm-service-account.json"),
    accessTeam: process.env.HERDR_PUSH_ACCESS_TEAM ?? "",
    accessAudience: process.env.HERDR_PUSH_ACCESS_AUD ?? "",
    upstream: process.env.HERDR_PUSH_UPSTREAM ?? "http://127.0.0.1:7317",
  };
}

if (import.meta.main) {
  let sender: FirebaseSender | undefined;
  try {
    const config = configuration();
    const authenticate = accessAuthenticator(config.accessTeam, config.accessAudience);
    sender = await firebaseSender(config.serviceAccount);
    const service = await createService({ ...config, authenticate, send: sender.send });
    let closing = false;
    const shutdown = async () => {
      if (closing) return;
      closing = true;
      await service.close();
      await sender?.close();
      process.exit(0);
    };
    process.once("SIGTERM", shutdown);
    process.once("SIGINT", shutdown);
    console.info("Android push bridge listening on loopback");
  } catch {
    await sender?.close();
    console.error("Android push bridge startup failed; check private configuration");
    process.exitCode = 1;
  }
}
