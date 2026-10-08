import "server-only";
import { cookies } from "next/headers";

/** The 5-minute JWT access token set by the API on sign-in / refresh (HTTP-only). */
export const ACCESS_COOKIE = "jo_at";
/** The rotating refresh token (HTTP-only); `proxy.ts` exchanges it for a new access token. */
export const REFRESH_COOKIE = "jo_rt";

/** True when this request carries a sign-in (the API still decides whether it is valid). */
export async function hasSessionCookie() {
  const jar = await cookies();
  return jar.has(ACCESS_COOKIE) || jar.has(REFRESH_COOKIE);
}
