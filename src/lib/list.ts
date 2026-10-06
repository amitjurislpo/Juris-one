/** Server-side list state from the URL: ?q=&sort=&dir=&page= (+ page-specific filters). */
export type SearchParams = Record<string, string | string[] | undefined>;

export interface ListState<S extends string> {
  q: string;
  sort: S;
  dir: "asc" | "desc";
  page: number;
  skip: number;
  take: number;
}

export const str = (sp: SearchParams, k: string, dflt = "") => {
  const v = sp[k];
  return (Array.isArray(v) ? v[0] : v)?.slice(0, 200) ?? dflt;
};

/** Read a value restricted to an allowed set. */
export function pick<T extends string>(sp: SearchParams, k: string, allowed: readonly T[], dflt: T): T {
  const v = str(sp, k);
  return (allowed as readonly string[]).includes(v) ? (v as T) : dflt;
}

export function listState<S extends string>(sp: SearchParams, sorts: readonly S[], dflt: S, pageSize = 25, dfltDir: "asc" | "desc" = "asc"): ListState<S> {
  const page = Math.max(1, Math.min(10_000, Number.parseInt(str(sp, "page", "1"), 10) || 1));
  return {
    q: str(sp, "q").trim(),
    sort: pick(sp, "sort", sorts, dflt),
    dir: pick(sp, "dir", ["asc", "desc"] as const, dfltDir),
    page,
    skip: (page - 1) * pageSize,
    take: pageSize,
  };
}
