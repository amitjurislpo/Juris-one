import "server-only";
import { cookies, headers } from "next/headers";

/**
 * Server-side client for the Spring Boot API (backend/). Pages and server actions call it on the browser's
 * behalf: the session cookie, client IP and user agent are forwarded, and writes carry this origin so the
 * API's same-origin check passes.
 */
export const BACKEND_URL = (process.env.BACKEND_URL || "http://127.0.0.1:8080").replace(/\/$/, "");

export type ApiResult<T = undefined> = { ok: true; msg?: string; data?: T } | { ok: false; error: string };

export interface ApiResponse<T> {
  status: number;
  body: ApiResult<T>;
  setCookies: string[];
}

async function forwardHeaders(write: boolean): Promise<Record<string, string>> {
  const [jar, h] = await Promise.all([cookies(), headers()]);
  const out: Record<string, string> = { accept: "application/json" };
  const cookie = jar.toString();
  if (cookie) out.cookie = cookie;
  const fwd = h.get("x-forwarded-for") ?? h.get("x-real-ip");
  if (fwd) out["x-forwarded-for"] = fwd;
  const ua = h.get("user-agent");
  if (ua) out["user-agent"] = ua;
  if (write) {
    const host = h.get("x-forwarded-host") ?? h.get("host") ?? "localhost";
    out["x-forwarded-host"] = host;
    out.origin = h.get("origin") ?? `http://${host}`;
    out["content-type"] = "application/json";
  }
  return out;
}

export async function apiCall<T>(method: "GET" | "POST" | "PUT" | "PATCH" | "DELETE", path: string, body?: unknown): Promise<ApiResponse<T>> {
  const write = method !== "GET";
  const res = await fetch(`${BACKEND_URL}${path}`, {
    method,
    headers: await forwardHeaders(write),
    body: write ? JSON.stringify(body ?? {}) : undefined,
    cache: "no-store",
  });
  let parsed: ApiResult<T>;
  try {
    const json = await res.json();
    // errors arrive as RFC 7807 problem details: { status, code, detail, ... }
    parsed = typeof json?.ok === "boolean" ? (json as ApiResult<T>) : { ok: false, error: String(json?.detail ?? "Something went wrong. Please try again.") };
  } catch {
    parsed = { ok: false, error: "Something went wrong. Please try again." };
  }
  return { status: res.status, body: parsed, setCookies: res.headers.getSetCookie() };
}

/** GET returning `data`, or null on 401 (signed out). Other errors throw. */
export async function apiGet<T>(path: string): Promise<T | null> {
  const r = await apiCall<T>("GET", path);
  if (r.status === 401) return null;
  if (!r.body.ok) throw new Error(`${path}: ${r.status} ${r.body.error}`);
  return r.body.data as T;
}

/** Copies the API's Set-Cookie headers (the session cookie) onto this response. */
export async function applySetCookies(list: string[]) {
  const jar = await cookies();
  for (const raw of list) {
    const [pair, ...attrs] = raw.split(";").map((s) => s.trim());
    const eq = pair.indexOf("=");
    const name = pair.slice(0, eq), value = pair.slice(eq + 1);
    const a = Object.fromEntries(attrs.map((x) => { const i = x.indexOf("="); return i < 0 ? [x.toLowerCase(), true] : [x.slice(0, i).toLowerCase(), x.slice(i + 1)]; }));
    const maxAge = a["max-age"] !== undefined ? Number(a["max-age"]) : undefined;
    if (maxAge === 0) {
      jar.delete(name);
      continue;
    }
    jar.set(name, value, {
      httpOnly: !!a.httponly,
      secure: !!a.secure,
      sameSite: typeof a.samesite === "string" ? (a.samesite.toLowerCase() as "lax" | "strict" | "none") : undefined,
      path: typeof a.path === "string" ? a.path : "/",
      ...(maxAge !== undefined ? { maxAge } : {}),
    });
  }
}

/**
 * A server action that runs in the API: returns its `ActionResult` unchanged and, on success, revalidates the
 * same pages the action used to.
 */
export async function apiAction<T = undefined>(method: "POST" | "PUT" | "PATCH" | "DELETE", path: string, body?: unknown, revalidate: string[] = []): Promise<ApiResult<T>> {
  const r = await apiCall<T>(method, path, body);
  if (r.body.ok) {
    const { revalidatePath } = await import("next/cache");
    for (const p of revalidate) revalidatePath(p);
  }
  return r.body;
}

/** GET returning `data`; throws when signed out or on any error (pages call requirePage first). */
export async function apiData<T>(path: string): Promise<T> {
  const r = await apiCall<T>("GET", path);
  if (!r.body.ok) throw new Error(`${path}: ${r.status} ${r.body.error}`);
  return r.body.data as T;
}

/** Search params → query string (first value of each). */
export function qs(sp: Record<string, string | string[] | undefined>): string {
  const p = new URLSearchParams();
  for (const [k, v] of Object.entries(sp)) {
    const x = Array.isArray(v) ? v[0] : v;
    if (x !== undefined) p.set(k, x);
  }
  const s = p.toString();
  return s ? `?${s}` : "";
}
