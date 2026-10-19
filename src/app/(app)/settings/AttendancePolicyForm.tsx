"use client";

import { useState, useTransition } from "react";
import { useToast } from "@/components/shell/Toasts";
import { useT } from "@/lib/i18n/client";
import { updateAttendancePolicy, type AttendancePolicyInput } from "./actions";

const DAYS = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];

export function AttendancePolicyForm({ initial, locked }: { initial: AttendancePolicyInput; locked: boolean }) {
  const toast = useToast();
  const t = useT();
  const [f, setF] = useState(initial);
  const [err, setErr] = useState("");
  const [busy, start] = useTransition();
  const num = (k: keyof AttendancePolicyInput, step = 1) => ({
    type: "number" as const, step, value: f[k] as number,
    onChange: (e: React.ChangeEvent<HTMLInputElement>) => setF({ ...f, [k]: e.target.value === "" ? 0 : Number(e.target.value) }),
  });
  const dirty = JSON.stringify(f) !== JSON.stringify(initial);

  return (
    <section className="card pad enter">
      <span className="spot"></span>
      <div className="card-h"><div><h3>{t("Rules")}</h3><span className="faint" style={{ fontSize: 12.5 }}>{t("Changes apply to open months. Locked months keep the values they were locked with.")}</span></div></div>
      <div className="grid3">
        <label className="field">{t("Full day (hours)")}<input {...num("fullDayHours", 0.5)} min={1} max={14} /></label>
        <label className="field">{t("Half day from (hours)")}<input {...num("halfDayHours", 0.5)} min={0.5} max={12} /></label>
        <label className="field">{t("Grace after shift start (min)")}<input {...num("graceMinutes")} min={0} max={120} /></label>
        <label className="field">{t("Late marks per ½ day LOP")}<input {...num("lateMarksPerHalfDayLop")} min={1} max={31} /></label>
        <label className="field">{t("Absent marking after shift end (min)")}<input {...num("absenceJobBufferMinutes")} min={0} max={720} /></label>
        <label className="field">{t("Attendance go-live")}<input type="date" value={f.trackingStartDate} disabled={locked} onChange={(e) => setF({ ...f, trackingStartDate: e.target.value })} />{locked && <span className="hint">{t("Fixed once a month is locked")}</span>}</label>
        <label className="field">{t("Lock on day of next month")}<input {...num("lockDayOfNextMonth")} min={1} max={28} /></label>
        <label className="field">{t("Lock time (IST)")}<input type="time" value={f.lockTime} onChange={(e) => setF({ ...f, lockTime: e.target.value })} /></label>
      </div>
      <div className="field" style={{ marginTop: 14 }}>{t("Weekly off")}
        <div style={{ display: "flex", gap: 8, flexWrap: "wrap" }}>
          {DAYS.map((d, i) => {
            const on = f.weeklyOffs.includes(i);
            return <button key={d} type="button" className={`pill${on ? " on" : ""}`} aria-pressed={on} onClick={() => setF({ ...f, weeklyOffs: on ? f.weeklyOffs.filter((x) => x !== i) : [...f.weeklyOffs, i].sort() })}>{t(d)}</button>;
          })}
        </div>
      </div>
      {err && <p className="err" role="alert">{err}</p>}
      <div style={{ display: "flex", gap: 8, marginTop: 16 }}>
        <button className="btn btn-gold" disabled={!dirty || busy} onClick={() => start(async () => { setErr(""); const r = await updateAttendancePolicy(f); if (r.ok) toast(r.msg!); else setErr(r.error); })}>{busy ? t("Saving…") : t("Save policy")}</button>
        {dirty && <button className="btn btn-ghost" onClick={() => setF(initial)}>{t("Reset")}</button>}
      </div>
    </section>
  );
}
