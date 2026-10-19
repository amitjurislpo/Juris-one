"use client";

import { useEffect, useRef, useState } from "react";
import { Icon } from "@/components/Icon";
import { useT } from "@/lib/i18n/client";
import { useQuery } from "./useQuery";

/** Prototype `.tsearch` — debounced, writes ?q= */
export function SearchBox({ placeholder, label, big }: { placeholder: string; label: string; big?: boolean }) {
  const { get, set } = useQuery();
  const urlQ = get("q");
  const [v, setV] = useState(urlQ);
  const t = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  // keep the box in sync when the URL changes elsewhere (e.g. "Clear filters")
  const [lastUrlQ, setLastUrlQ] = useState(urlQ);
  if (lastUrlQ !== urlQ) {
    setLastUrlQ(urlQ);
    setV(urlQ);
  }
  useEffect(() => () => clearTimeout(t.current), []);
  return (
    <label className={`tsearch${big ? " big" : ""}`}>
      <Icon n="search" s={big ? 16 : 15} />
      <input
        value={v}
        onChange={(e) => {
          const val = e.target.value;
          setV(val);
          clearTimeout(t.current);
          t.current = setTimeout(() => set({ q: val.trim() || null }), 250);
        }}
        placeholder={placeholder}
        aria-label={label}
        maxLength={100}
      />
    </label>
  );
}

/** Prototype `.tsel` dropdown filter bound to ?<name>= */
export function SelectFilter({ name, label, options, dflt }: { name: string; label: string; options: { v: string; l: string }[]; dflt: string }) {
  const { get, set } = useQuery();
  return (
    <label className="tsel">
      <span>{label}</span>
      <select value={get(name, dflt)} onChange={(e) => set({ [name]: e.target.value === dflt ? null : e.target.value })}>
        {options.map((o) => <option key={o.v} value={o.v}>{o.l}</option>)}
      </select>
    </label>
  );
}

/** Prototype `.seg` segmented filter bound to ?<name>= */
export function SegFilter({ name, options, dflt, label }: { name: string; options: { v: string; l: string }[]; dflt: string; label: string }) {
  const { get, set } = useQuery();
  const cur = get(name, dflt);
  return (
    <div className="seg" role="group" aria-label={label}>
      {options.map((o) => (
        <button key={o.v} type="button" className={cur === o.v ? "on" : undefined} aria-pressed={cur === o.v} onClick={() => set({ [name]: o.v === dflt ? null : o.v })}>
          {o.l}
        </button>
      ))}
    </div>
  );
}

/** Click-to-sort column header (↑ / ↓ / ↕). */
export function SortTh({ k, children, right, dflt, dfltDir = "asc", className }: { k: string; children: React.ReactNode; right?: boolean; dflt: string; dfltDir?: "asc" | "desc"; className?: string }) {
  const { get, set } = useQuery();
  const sort = get("sort", dflt);
  const dir = get("dir", dfltDir);
  const on = sort === k;
  const next = on ? (dir === "asc" ? "desc" : "asc") : "asc";
  return (
    <th className={[right && "r", className].filter(Boolean).join(" ") || undefined} aria-sort={on ? (dir === "asc" ? "ascending" : "descending") : "none"}>
      <button className="sh" type="button" onClick={() => set({ sort: k, dir: next })}>
        {children}
        <i>{on ? (dir === "asc" ? "↑" : "↓") : "↕"}</i>
      </button>
    </th>
  );
}

export function Pager({ page, take, total }: { page: number; take: number; total: number }) {
  const t = useT();
  const { set } = useQuery();
  if (total <= take) return null;
  const last = Math.ceil(total / take);
  const from = (page - 1) * take + 1;
  return (
    <div className="pager">
      <span className="mono">{t("{from}–{to} of {total}", { from, to: Math.min(total, page * take), total })}</span>
      <span style={{ display: "flex", gap: 6 }}>
        <button className="btn btn-sm" type="button" disabled={page <= 1} onClick={() => set({ page: String(page - 1) })}><Icon n="chevL" s={14} />{t("Previous")}</button>
        <button className="btn btn-sm" type="button" disabled={page >= last} onClick={() => set({ page: String(page + 1) })}>{t("Next")}<Icon n="chevR" s={14} /></button>
      </span>
    </div>
  );
}

/** Wraps a table body so it dims while the server re-renders after a filter change. */
export function Pending({ children }: { children: React.ReactNode }) {
  const { pending } = useQuery();
  return <div style={{ opacity: pending ? 0.55 : 1, transition: "opacity .15s" }} aria-busy={pending}>{children}</div>;
}

export function ClearFilters({ label }: { label?: string }) {
  const t = useT();
  const { params, set } = useQuery();
  return (
    <button className="btn btn-sm" type="button" onClick={() => set(Object.fromEntries([...params.keys()].map((k) => [k, null])))}>
      {label ?? t("Clear filters")}
    </button>
  );
}
