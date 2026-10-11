import { createRemoteJWKSet, jwtVerify } from "jose";
import { cert, initializeApp, deleteApp } from "firebase-admin/app";
import type { ServiceAccount } from "firebase-admin/app";
import { getMessaging } from "firebase-admin/messaging";
import { readFile, stat } from "node:fs/promises";
import type { EventDetail } from "./state.ts";

export type Authenticate = (request: Request) => Promise<boolean>;
export type Send = (token: string, event: EventDetail) => Promise<void>;
export interface FirebaseSender { send: Send; close: () => Promise<void> }

export function accessAuthenticator(team: string, audience: string): Authenticate {
  if (!/^[a-z0-9-]+$/.test(team) || !audience) throw new Error("Access configuration required");
  const issuer = `https://${team}.cloudflareaccess.com`;
  const jwks = createRemoteJWKSet(new URL(`${issuer}/cdn-cgi/access/certs`));
  return async request => {
    const assertion = request.headers.get("Cf-Access-Jwt-Assertion");
    if (!assertion || assertion.length > 16384) return false;
    try {
      const { payload } = await jwtVerify(assertion, jwks, { issuer, audience, algorithms: ["RS256"], requiredClaims: ["exp", "iat"] });
      return (typeof payload.sub === "string" && payload.sub.length > 0) || (typeof payload.common_name === "string" && payload.common_name.length > 0);
    } catch { return false; }
  };
}

export async function firebaseSender(path: string): Promise<FirebaseSender> {
  const info = await stat(path);
  if (!info.isFile() || (info.mode & 0o077) !== 0 || info.size > 65536) throw new Error("Service account must be a private bounded file");
  let account: ServiceAccount;
  try { account = JSON.parse(await readFile(path, "utf8")); } catch { throw new Error("Invalid service account file"); }
  const app = initializeApp({ credential: cert(account) }, `herdr-push-${crypto.randomUUID()}`);
  const messaging = getMessaging(app);
  const send: Send = async (token, event) => {
    await messaging.send({
      token,
      data: { type: "herdr_event", event_id: event.event_id, kind: event.kind, pane_id: event.pane_id, server_origin: event.server_origin },
      android: { priority: "high", ttl: 12 * 60 * 60 * 1000 },
    });
  };
  return { send, close: () => deleteApp(app) };
}

export function staleToken(error: unknown) {
  return typeof error === "object" && error !== null && "code" in error && error.code === "messaging/registration-token-not-registered";
}
