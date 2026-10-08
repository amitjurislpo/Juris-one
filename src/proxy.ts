import { NextResponse, type NextRequest } from "next/server";

const PUBLIC = ["/login", "/forgot", "/invite/", "/reset/", "/onboard/"];
const ACCESS_COOKIE = "jo_at";
const REFRESH_COOKIE = "jo_rt";
/** Refresh a little before the 5-minute access token runs out, so a request never reaches the API with a dying token. */
const REFRESH_EARLY_SECONDS = 30;
const BACKEND_URL = (process.env.BACKEND_URL || "http://127.0.0.1:8080").replace(/\/$/, "");

/** Seconds until the JWT's `exp` (unverified — the API verifies; this only decides whether to refresh). */
function secondsLeft(jwt: string | undefined): number {
  if (!jwt) return -1;
  try {
    const payload = jwt.split(".")[1].replace(/-/g, "+").replace(/_/g, "/");
    const { exp } = JSON.parse(atob(payload)) as { exp?: number };
    return typeof exp === "number" ? exp - Math.floor(Date.now() / 1000) : -1;
  } catch {
    return -1;
  }
}

/** Exchanges the refresh cookie for new cookies. Returns the Set-Cookie headers, or null when the session has ended. */
async function refresh(req: NextRequest, refreshToken: string): Promise<string[] | null> {
  const host = req.headers.get("x-forwarded-host") ?? req.headers.get("host") ?? req.nextUrl.host;
  const headers: Record<string, string> = {
    cookie: `${REFRESH_COOKIE}=${refreshToken}`,
    origin: `${req.nextUrl.protocol}//${host}`,
    "x-forwarded-host": host,
    "content-type": "application/json",
  };
  const ip = req.headers.get("x-forwarded-for") ?? req.headers.get("x-real-ip");
  if (ip) headers["x-forwarded-for"] = ip;
  const ua = req.headers.get("user-agent");
  if (ua) headers["user-agent"] = ua;
  try {
    const res = await fetch(`${BACKEND_URL}/api/auth/session/refresh`, { method: "POST", headers, body: "{}" });
    return res.ok ? res.headers.getSetCookie() : null;
  } catch {
    return null; // API unreachable: let the request through; the page will show the error
  }
}

/** `name=value` from a Set-Cookie header. */
function cookieValue(setCookie: string, name: string): string | undefined {
  const [pair] = setCookie.split(";");
  return pair.startsWith(`${name}=`) ? pair.slice(name.length + 1) : undefined;
}

/**
 * Runs before every page, server action and browser `/api` request:
 * - keeps the sign-in alive: when the 5-minute access token is missing or about to expire and a refresh cookie is
 *   present, it is exchanged for new cookies — for this request (so the page and the API see a fresh token) and for
 *   the browser;
 * - bounces signed-out visitors to /login (an optimistic check; the API checks every request).
 */
export async function proxy(req: NextRequest) {
  const { pathname, search } = req.nextUrl;
  const isPublic = PUBLIC.some((p) => pathname === p || pathname.startsWith(p));
  const isApi = pathname.startsWith("/api/");
  if (pathname.startsWith("/api/auth/")) return NextResponse.next();

  const access = req.cookies.get(ACCESS_COOKIE)?.value;
  const refreshToken = req.cookies.get(REFRESH_COOKIE)?.value;

  if (refreshToken && secondsLeft(access) < REFRESH_EARLY_SECONDS) {
    const setCookies = await refresh(req, refreshToken);
    if (setCookies) {
      for (const sc of setCookies) {
        for (const name of [ACCESS_COOKIE, REFRESH_COOKIE]) {
          const v = cookieValue(sc, name);
          if (v !== undefined) req.cookies.set(name, v);
        }
      }
      const res = NextResponse.next({ request: { headers: new Headers(req.headers) } });
      for (const sc of setCookies) res.headers.append("set-cookie", sc);
      return res;
    }
    // the session has ended (signed out elsewhere, access disabled, expired): forget the cookies
    req.cookies.delete(ACCESS_COOKIE);
    req.cookies.delete(REFRESH_COOKIE);
    const res = isPublic || isApi ? NextResponse.next({ request: { headers: new Headers(req.headers) } }) : toLogin(req, pathname, search);
    res.cookies.delete(ACCESS_COOKIE);
    res.cookies.delete(REFRESH_COOKIE);
    return res;
  }

  if (isPublic || isApi) return NextResponse.next();
  if (!access && !refreshToken) return toLogin(req, pathname, search);
  return NextResponse.next();
}

function toLogin(req: NextRequest, pathname: string, search: string) {
  const url = new URL("/login", req.url);
  if (pathname !== "/") url.searchParams.set("next", pathname + search);
  return NextResponse.redirect(url);
}

export const config = {
  matcher: ["/((?!_next/static|_next/image|brand/|fonts/|icon.svg|favicon.ico|robots.txt).*)"],
};
