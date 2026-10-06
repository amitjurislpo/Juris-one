"use client";

import { useRouter } from "next/navigation";
import { useEffect, useState, useTransition } from "react";
import { punch } from "@/app/(app)/home/actions";
import { Icon } from "@/components/Icon";
import { useToast } from "@/components/shell/Toasts";

const pad = (n: number) => String(n).padStart(2, "0");

export interface TimeCardProps {
  on: boolean;
  openSince: string | null; // ISO
  closedMinutes: number;
  shiftText: string;
  ticks: string[];
  targetHours: number;
  log: { t: string; l: string; d: string }[];
}

/** Prototype check-in card: live timer, 9h progress track, punch log. */
export function TimeCard(p: TimeCardProps) {
  const router = useRouter();
  const toast = useToast();
  const [busy, start] = useTransition();
  const [now, setNow] = useState<number | null>(null);
  useEffect(() => {
    const tick = () => setNow(Date.now());
    tick();
    const t = setInterval(tick, 1000);
    return () => clearInterval(t);
  }, []);

  const liveMs = p.on && p.openSince && now ? Math.max(0, now - Date.parse(p.openSince)) : 0;
  const secs = Math.floor(p.closedMinutes * 60 + liveMs / 1000);
  const target = p.targetHours * 3600;
  const pct = Math.min(1, secs / target);
  const left = Math.max(0, target - secs);

  const go = () =>
    start(async () => {
      const r = await punch(p.on ? "OUT" : "IN");
      if (r.ok) toast(r.msg!);
      else toast(r.error, "bad");
      router.refresh();
    });

  return (
    <section className="card timecard span-7 enter" style={{ ["--i" as string]: 1 }} aria-label="Check in and check out">
      <span className="spot"></span>
      <div className="aur"></div>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-start", gap: 12, position: "relative" }}>
        <div><span className="eyebrow">Today&apos;s shift</span><div style={{ fontWeight: 600, marginTop: 2 }}>{p.shiftText}</div></div>
        <span className={`chip ${p.on ? "ok" : "neutral"}`}>{p.on ? "Checked in" : "Checked out"}</span>
      </div>
      <div style={{ position: "relative" }}>
        <div className="bigtime" aria-label="Worked today" suppressHydrationWarning>
          {pad(Math.floor(secs / 3600))}<span>:</span>{pad(Math.floor((secs % 3600) / 60))}<span>:</span>{pad(secs % 60)}
        </div>
        <div className="faint" style={{ marginTop: 6, fontSize: 13 }}>worked today · {Math.round(pct * 100)}% of a {p.targetHours}h shift</div>
      </div>
      <div style={{ position: "relative" }}>
        <div className="shift-track" role="progressbar" aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.round(pct * 100)} aria-label="Shift progress">
          <div className="fill" style={{ width: `${pct * 100}%` }}></div>
          <div className="knob" style={{ left: `${pct * 100}%` }}></div>
        </div>
        <div className="shift-ticks" style={{ marginTop: 10 }}>{p.ticks.map((t) => <span key={t}>{t}</span>)}</div>
      </div>
      <div style={{ display: "flex", alignItems: "center", gap: 14, flexWrap: "wrap", position: "relative" }}>
        <div className="punch">
          {!p.on && <span className="halo"></span>}
          <button className="btn btn-gold btn-lg" style={{ minWidth: 160 }} disabled={busy} onClick={go}>
            <Icon n={p.on ? "logout" : "sunrise"} s={16} />{busy ? "Saving…" : p.on ? "Check out" : "Check in"}
          </button>
        </div>
        <span className="muted" style={{ fontSize: 13 }}>
          {p.on ? (pct >= 1 ? "Shift target reached" : `${Math.floor(left / 3600)}h ${pad(Math.floor((left % 3600) / 60))}m left in your shift`) : "Not tracking right now"}
        </span>
      </div>
      {p.log.length > 0 && (
        <div className="timeline" style={{ position: "relative" }}>
          {p.log.map((e, i) => (
            <div className="t" key={i}><b>{e.t}</b><span>{e.l}</span><span className="faint" style={{ marginLeft: "auto", fontSize: 12.5 }}>{e.d}</span></div>
          ))}
        </div>
      )}
    </section>
  );
}
