"use client";

import { Icon } from "@/components/Icon";
import { useQuery } from "@/components/table/useQuery";
import { addDays } from "@/lib/attendance/engine";
import { useT } from "@/lib/i18n/client";

/** Date picker (no future dates) with previous / next day. */
export function DayNav({ day, max }: { day: string; max: string }) {
  const t = useT();
  const { set } = useQuery();
  const go = (d: string) => set({ day: d === max ? null : d });
  return (
    <div className="daynav">
      <button className="icon-btn" onClick={() => go(addDays(day, -1))} aria-label={t("Previous day")}><Icon n="chevL" s={16} /></button>
      <input type="date" value={day} max={max} aria-label={t("Date")} onChange={(e) => e.target.value && go(e.target.value > max ? max : e.target.value)} />
      <button className="icon-btn" onClick={() => go(addDays(day, 1))} disabled={day >= max} aria-label={t("Next day")}><Icon n="chevR" s={16} /></button>
    </div>
  );
}

export function ZonePicker({ zones, value }: { zones: readonly { v: string; l: string }[]; value: string }) {
  const t = useT();
  const { set } = useQuery();
  return (
    <label className="tsel tz">
      <Icon n="globe" s={15} />
      <span>{t("Show times in")}</span>
      <select value={value} onChange={(e) => set({ tz: e.target.value })}>{zones.map((z) => <option key={z.v} value={z.v}>{t(z.l)}</option>)}</select>
    </label>
  );
}
