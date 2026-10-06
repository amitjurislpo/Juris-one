"use client";

import { useRef, useState, useTransition } from "react";
import { Icon } from "@/components/Icon";
import { useToast } from "@/components/shell/Toasts";
import { ConfirmDialog } from "@/components/ui/Modal";
import { addDays, dowOf } from "@/lib/attendance/engine";
import { MONTHS } from "@/lib/format";
import { addHoliday, deleteHoliday } from "./actions";

type Region = "IN" | "US" | "ALL";
interface H { k: string; n: string; region: Region }
const RCLS: Record<Region, string> = { IN: "r-India", US: "r-US", ALL: "r-All" };
const RNAME: Record<Region, string> = { IN: "India", US: "US", ALL: "Everyone" };

/** Month calendar (tap a date to pre-fill) + add form — prototype tpl-holidays. */
export function HolidayPlanner({ holidays, today }: { holidays: H[]; today: string }) {
  const toast = useToast();
  const [ym, setYm] = useState(today.slice(0, 7));
  const [f, setF] = useState<{ date: string; name: string; region: Region }>({ date: "", name: "", region: "IN" });
  const [tried, setTried] = useState(false);
  const [err, setErr] = useState("");
  const [busy, start] = useTransition();
  const nameRef = useRef<HTMLInputElement>(null);

  const move = (n: number) => setYm(new Date(Date.UTC(Number(ym.slice(0, 4)), Number(ym.slice(5)) - 1 + n, 1)).toISOString().slice(0, 7));
  const first = `${ym}-01`;
  const n = new Date(Date.UTC(Number(ym.slice(0, 4)), Number(ym.slice(5)), 0)).getUTCDate();
  const lead = (dowOf(first) + 6) % 7;

  const add = () => {
    setTried(true);
    setErr("");
    if (!f.date || !f.name.trim()) return;
    start(async () => {
      const r = await addHoliday(f);
      if (r.ok) { toast(r.msg!); setF({ date: "", name: "", region: f.region }); setTried(false); }
      else setErr(r.error);
    });
  };

  return (
    <div className="row" style={{ alignItems: "flex-start" }}>
      <section className="card pad enter" style={{ ["--i" as string]: 1, flex: "1 1 380px", minWidth: 0 }}>
        <span className="spot"></span>
        <div className="card-h">
          <h3>{MONTHS[Number(ym.slice(5)) - 1]} {ym.slice(0, 4)}</h3>
          <div className="row" style={{ gap: 6 }}>
            <button className="icon-btn" onClick={() => move(-1)} aria-label="Previous month"><Icon n="chevL" s={16} /></button>
            <button className="icon-btn" onClick={() => move(1)} aria-label="Next month"><Icon n="chevR" s={16} /></button>
          </div>
        </div>
        <div className="hcal">
          {["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"].map((w) => <span className="w" key={w}>{w}</span>)}
          {Array.from({ length: lead }, (_, i) => <span key={`b${i}`}></span>)}
          {Array.from({ length: n }, (_, i) => {
            const k = addDays(first, i);
            const hs = holidays.filter((h) => h.k === k);
            const wk = [0, 6].includes(dowOf(k));
            return (
              <button key={k} type="button" className={[wk && "wk", k === today && "today", hs.length && "has", f.date === k && "sel"].filter(Boolean).join(" ")}
                title={hs.map((h) => `${h.n} (${RNAME[h.region]})`).join(", ") || "Add a holiday on this date"}
                onClick={() => { setF({ ...f, date: k }); requestAnimationFrame(() => nameRef.current?.focus()); }}>
                <b>{i + 1}</b>
                {hs.map((h) => <small key={h.n + h.region} className={RCLS[h.region]}>{h.n}</small>)}
              </button>
            );
          })}
        </div>
        <p className="faint" style={{ margin: "12px 0 0", fontSize: 12.5 }}>Tap a date to add a holiday on it.</p>
      </section>
      <section className="card pad enter" style={{ ["--i" as string]: 2, flex: "1 1 320px" }}>
        <span className="spot"></span>
        <div className="card-h"><h3>Add holiday</h3></div>
        <div className="col" style={{ gap: 14 }}>
          <label className="field">Date *<input type="date" value={f.date} onChange={(e) => setF({ ...f, date: e.target.value })} /></label>
          <label className="field">Holiday name *<input ref={nameRef} value={f.name} maxLength={80} onChange={(e) => setF({ ...f, name: e.target.value })} placeholder="e.g. Diwali" onKeyDown={(e) => e.key === "Enter" && add()} /></label>
          <div className="field">Applies to
            <div className="seg" style={{ width: "100%" }} role="group" aria-label="Applies to">
              {(["IN", "US", "ALL"] as Region[]).map((r) => (
                <button key={r} type="button" className={f.region === r ? "on" : undefined} aria-pressed={f.region === r} onClick={() => setF({ ...f, region: r })}>{r === "ALL" ? "Everyone" : `${RNAME[r]} team`}</button>
              ))}
            </div>
          </div>
          {tried && (!f.date || !f.name.trim()) && <p className="err" style={{ margin: 0 }}>Pick a date and enter a name.</p>}
          {err && <p className="err" role="alert" style={{ margin: 0 }}>{err}</p>}
          <button className="btn btn-gold" disabled={busy} onClick={add}><Icon n="plus" s={15} />Add to calendar</button>
          <p className="faint" style={{ margin: 0, fontSize: 12.5 }}>Employees see it under “Upcoming holidays”. Holidays aren&apos;t counted as leave or loss of pay.</p>
        </div>
      </section>
    </div>
  );
}

export function DeleteHoliday({ id, name }: { id: string; name: string }) {
  const toast = useToast();
  const [ask, setAsk] = useState(false);
  const [busy, start] = useTransition();
  return (
    <>
      <button className="btn btn-sm btn-ghost" onClick={() => setAsk(true)} aria-label={`Remove ${name}`}><Icon n="trash" s={14} /></button>
      {ask && (
        <ConfirmDialog title="Remove holiday" danger confirm="Remove" busy={busy} onClose={() => setAsk(false)} body={<>Remove <b>{name}</b> from the calendar?</>}
          onConfirm={() => start(async () => { const r = await deleteHoliday(id); toast(r.ok ? r.msg! : r.error, "bad"); setAsk(false); })} />
      )}
    </>
  );
}
