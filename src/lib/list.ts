/** URL search params on list pages (the API applies ?q=&sort=&dir=&page= and the filters). */
export type SearchParams = Record<string, string | string[] | undefined>;

export const str = (sp: SearchParams, k: string, dflt = "") => {
  const v = sp[k];
  return (Array.isArray(v) ? v[0] : v)?.slice(0, 200) ?? dflt;
};

/** Read a value restricted to an allowed set. */
export function pick<T extends string>(sp: SearchParams, k: string, allowed: readonly T[], dflt: T): T {
  const v = str(sp, k);
  return (allowed as readonly string[]).includes(v) ? (v as T) : dflt;
}
