/** What every server action returns — the API's `{ ok, msg?, data?, error? }` envelope, passed through unchanged. */
export type ActionResult<T = undefined> = { ok: true; msg?: string; data?: T } | { ok: false; error: string };
