import "server-only";

/**
 * Fixed-window in-memory counter keyed by e.g. IP. Good for a single app instance; for several
 * instances, move this to Postgres/Redis. Per-account lockout lives on `users` in the DB.
 */
const hits = new Map<string, { n: number; reset: number }>();

function live(key: string) {
  const cur = hits.get(key);
  if (cur && cur.reset <= Date.now()) {
    hits.delete(key);
    return undefined;
  }
  return cur;
}

/** True when `key` has used up `max` hits in its current window. */
export function limited(key: string, max: number): { limited: boolean; retryInMs: number } {
  const cur = live(key);
  return cur && cur.n >= max ? { limited: true, retryInMs: cur.reset - Date.now() } : { limited: false, retryInMs: 0 };
}

export function hit(key: string, windowMs: number) {
  const cur = live(key);
  if (cur) cur.n += 1;
  else hits.set(key, { n: 1, reset: Date.now() + windowMs });
  if (hits.size > 10_000) for (const k of hits.keys()) live(k);
}

/** Counts every call (used for endpoints like "forgot password"). */
export function rateLimit(key: string, max: number, windowMs: number): { ok: boolean; retryInMs: number } {
  const l = limited(key, max);
  if (l.limited) return { ok: false, retryInMs: l.retryInMs };
  hit(key, windowMs);
  return { ok: true, retryInMs: 0 };
}
