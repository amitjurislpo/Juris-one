import "server-only";
import { createHash } from "node:crypto";
import { cookies } from "next/headers";

/** The HTTP-only session cookie set by the API on sign-in (an opaque token; the API stores only its SHA-256). */
export const SESSION_COOKIE = "jo_session";

/** The session id (SHA-256 of the cookie token), or null when signed out. */
export async function readSessionCookie() {
  const token = (await cookies()).get(SESSION_COOKIE)?.value;
  return token ? createHash("sha256").update(token).digest("hex") : null;
}
