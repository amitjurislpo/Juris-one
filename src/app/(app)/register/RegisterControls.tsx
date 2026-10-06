"use client";

import { Icon } from "@/components/Icon";
import { useQuery } from "@/components/table/useQuery";
import { addDays } from "@/lib/attendance/engine";

/** Date picker (no future dates) with previous / next day. */
export function DayNav({ day, max }: { day: string; max: string }) {
  const { set } = useQuery();
  const go = (d: string) => set({ day: d === max ? null : d });
  return (
    <div className="daynav">
      <button className="icon-btn" onClick={() => go(addDays(day, -1))} aria-label="Previous day"><Icon n="chevL" s={16} /></button>
      <input type="date" value={day} max={max} aria-label="Date" onChange={(e) => e.target.value && go(e.target.value > max ? max : e.target.value)} />
      <button className="icon-btn" onClick={() => go(addDays(day, 1))} disabled={day >= max} aria-label="Next day"><Icon n="chevR" s={16} /></button>
    </div>
  );
}

export function ZonePicker({ zones, value }: { zones: readonly { v: string; l: string }[]; value: string }) {
  const { set } = useQuery();
  return (
    <label className="tsel tz">
      <Icon n="globe" s={15} />
      <span>Show times in</span>
      <select value={value} onChange={(e) => set({ tz: e.target.value })}>{zones.map((z) => <option key={z.v} value={z.v}>{z.l}</option>)}</select>
    </label>
  );
}
