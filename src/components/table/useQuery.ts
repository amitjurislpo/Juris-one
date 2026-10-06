"use client";

import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { useCallback, useTransition } from "react";

/**
 * URL-backed table state. Changing any filter resets to page 1; the server component
 * re-renders with the new searchParams (search, filters, sorting and paging all run in SQL).
 */
export function useQuery() {
  const router = useRouter();
  const pathname = usePathname();
  const sp = useSearchParams();
  const [pending, start] = useTransition();

  const get = useCallback((k: string, dflt = "") => sp.get(k) ?? dflt, [sp]);

  const set = useCallback(
    (patch: Record<string, string | null>) => {
      const next = new URLSearchParams(sp.toString());
      for (const [k, v] of Object.entries(patch)) {
        if (v === null || v === "") next.delete(k);
        else next.set(k, v);
      }
      if (!("page" in patch)) next.delete("page");
      const qs = next.toString();
      start(() => router.replace(qs ? `${pathname}?${qs}` : pathname, { scroll: false }));
    },
    [sp, pathname, router],
  );

  return { get, set, pending, params: sp };
}
