"use client";

import { useState, useTransition } from "react";
import { useToast } from "@/components/shell/Toasts";
import { Modal } from "@/components/ui/Modal";
import { dowOf } from "@/lib/attendance/engine";
import { fmtDL, fmtH } from "@/lib/format";
import { cancelRegularization, requestRegularization, type RegInput } from "./actions";

export interface CalDay {
  date: string;
  status: string;
  label: string;
  late: boolean;
  lateWaived: boolean;
  hours: number;
  today: boolean;
  future: boolean;
  firstIn: string | null;
  lastOut: string | null;
  live: boolean;
  missedCheckout: boolean;
  offDayWork: boolean;
  lopDays: number;
  leaveDays: number;
  regularized: boolean;
}

export interface RegView { id: string; date: string; kind: string; status: string; by: string | null; reason: string | null }

const CLS: Record<string, string> = { PRESENT: "present", HALF_DAY: "present", ABSENT: "absent", HOLIDAY: "holiday", LEAVE: "leave", WEEKLY_OFF: "off" };
const CHIP: Record<string, string> = { PRESENT: "ok", HALF_DAY: "warn", ABSENT: "bad", HOLIDAY: "gold", LEAVE: "info" };
const KIND: Record<string, string> = { ABSENT: "Absent", LATE_CHECKIN: "Late check-in", MISSED_CHECKOUT: "Missed check-out", HOLIDAY_WORK: "Holiday work (comp-off)" };

function countsAs(d: CalDay) {
  if (d.future) return "—";
  switch (d.status) {
    case "PRESENT": return d.regularized ? "Paid day (regularized)" : "Paid day";
    case "HALF_DAY": return "Half day · ½ loss of pay";
    case "ABSENT": return "Loss of pay";
    case "HOLIDAY": return "Paid holiday";
    case "WEEKLY_OFF": return "Weekly off";
    case "LEAVE": return d.lopDays ? (d.leaveDays ? `Leave ${d.leaveDays}d · LOP ${d.lopDays}d` : "Unpaid leave") : "Paid leave";
    case "PENDING": return d.live ? "Day in progress" : "—";
    default: return "—";
  }
}

function explain(d: CalDay) {
  if (d.status === "ABSENT") return d.missedCheckout ? "Check-out is missing, so the hours don't add up to a working day. Ask for a regularization with your real check-out time." : "No check-in and no leave applied. It stays loss of pay unless HR, Finance or Admin approves a regularization before payroll locks.";
  if (d.status === "HALF_DAY") return "Between 4 and 8 hours worked, so half the day is loss of pay.";
  if (d.late) return "Checked in after the grace period. Three late marks in a month count as half a day of loss of pay.";
  if (d.lateWaived) return "A late check-in here was regularized — it doesn't count as a late mark.";
  if (d.offDayWork) return "You worked on a day off. Request a comp-off to get a day back.";
  return "Nothing to do for this day.";
}

export function AttendanceCalendar({ days, regs, initialSel, locked, monthLabel }: { days: CalDay[]; regs: RegView[]; initialSel: string; locked: boolean; monthLabel: string }) {
  const toast = useToast();
  const [sel, setSel] = useState(initialSel);
  const [open, setOpen] = useState(false);
  const [busy, start] = useTransition();
  const d = days.find((x) => x.date === sel) ?? days[0];
  const lead = (dowOf(days[0].date) + 6) % 7;
  const cells: (CalDay | null)[] = [...Array(lead).fill(null), ...days];
  while (cells.length % 7) cells.push(null);
  const dayRegs = regs.filter((r) => r.date === d.date);
  const pending = dayRegs.find((r) => r.status === "PENDING");
  const settled = dayRegs.find((r) => r.status === "APPROVED" || r.status === "DECLINED");

  const kinds: RegInput["kind"][] = [];
  if (d.status === "ABSENT" || d.status === "HALF_DAY") kinds.push("ABSENT");
  if (d.missedCheckout) kinds.push("MISSED_CHECKOUT");
  if (d.late) kinds.push("LATE_CHECKIN");
  if (d.offDayWork) kinds.push("HOLIDAY_WORK");
  const canRequest = !locked && !d.future && !d.live && kinds.length > 0 && !pending;

  const [f, setF] = useState<RegInput>({ date: d.date, kind: kinds[0] ?? "ABSENT", proposedIn: "", proposedOut: "", reason: "" });
  const openForm = () => { setF({ date: d.date, kind: kinds[0], proposedIn: "", proposedOut: "", reason: "" }); setOpen(true); };

  return (
    <>
      <div className="row" style={{ alignItems: "flex-start" }}>
        <section className="card pad enter" style={{ ["--i" as string]: 6, flex: "2 1 600px", minWidth: 0 }} aria-label={`Calendar, ${monthLabel}`}>
          <span className="spot" aria-hidden="true"></span>
          <div className="cal">
            {["MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"].map((w) => <span className="dow" key={w}>{w}</span>)}
            {cells.map((c, i) =>
              c ? (
                <button key={c.date} type="button" className={`d ${c.future ? "none" : CLS[c.status] ?? "none"}${sel === c.date ? " sel" : ""}${c.today ? " today" : ""}`} style={{ animationDelay: `${i * 12}ms` }}
                  onClick={() => setSel(c.date)} aria-pressed={sel === c.date} aria-label={`${fmtDL(c.date)}, ${c.label || "no record"}${c.late ? ", late" : ""}`}>
                  <span style={{ display: "flex", justifyContent: "space-between", width: "100%" }}>
                    <b>{Number(c.date.slice(8))}</b>
                    {c.late && <span className="chip warn" style={{ height: 20, fontSize: 10, padding: "0 7px" }}>LATE</span>}
                  </span>
                  <small>{c.future ? "" : c.label}</small>
                  {c.hours > 0 && <small className="num" style={{ color: "var(--text)" }}>{fmtH(c.hours)}</small>}
                </button>
              ) : (
                <div key={`o${i}`} className="d out" style={{ animationDelay: `${i * 12}ms` }}></div>
              ),
            )}
          </div>
        </section>
        <section className="card pad enter" style={{ ["--i" as string]: 7, flex: "1 1 320px", position: "sticky", top: 96 }} aria-live="polite">
          <span className="spot" aria-hidden="true"></span>
          <span className="eyebrow">Day detail</span>
          <h2 className="serif" style={{ fontSize: 30, margin: "10px 0 10px", fontWeight: 600 }}>{fmtDL(d.date)}</h2>
          <span className={`chip ${CHIP[d.status] ?? "neutral"}`}>{d.future ? "Upcoming" : d.label || "No record yet"}</span>
          <div style={{ marginTop: 16 }}>
            <div className="kv"><span className="muted">First check-in</span><b className="num">{d.firstIn ?? "—"}</b></div>
            <div className="kv"><span className="muted">Last check-out</span><b className="num">{d.lastOut ?? (d.live && d.firstIn ? "Working now" : "—")}</b></div>
            <div className="kv"><span className="muted">Worked</span><b className="num">{d.hours ? fmtH(d.hours) : "—"}</b></div>
            <div className="kv"><span className="muted">Counts as</span><b>{countsAs(d)}</b></div>
          </div>
          <p className="muted" style={{ fontSize: 13.5, lineHeight: 1.65, margin: "14px 0" }}>{d.future ? "This day hasn't happened yet." : explain(d)}</p>
          {pending && (
            <div className="col" style={{ gap: 8 }}>
              <div className="chip warn" style={{ height: "auto", padding: "10px 12px", whiteSpace: "normal" }}>{KIND[pending.kind]} regularization pending — waiting for HR, Finance or Admin</div>
              <button className="btn btn-sm btn-ghost" style={{ alignSelf: "flex-start" }} disabled={busy} onClick={() => start(async () => { const r = await cancelRegularization(pending.id); toast(r.ok ? r.msg! : r.error, r.ok ? "ok" : "bad"); })}>Withdraw request</button>
            </div>
          )}
          {!pending && settled && (
            <div className={`chip ${settled.status === "APPROVED" ? "ok" : "bad"}`} style={{ height: "auto", padding: "10px 12px", whiteSpace: "normal", marginBottom: 10 }}>
              {KIND[settled.kind]} regularization {settled.status.toLowerCase()}{settled.by ? ` by ${settled.by}` : ""}
            </div>
          )}
          {canRequest && <button className="btn btn-gold" style={{ width: "100%" }} onClick={openForm}>Request regularization</button>}
          {locked && kinds.length > 0 && !d.future && <p className="faint" style={{ fontSize: 12.5, margin: 0 }}>Attendance for this month is locked — Finance can adjust LOP in payroll.</p>}
        </section>
      </div>

      {open && (
        <Modal title="Regularize this day" eyebrow={fmtDL(d.date)} size="sm" onClose={() => setOpen(false)}
          footer={<>
            <span className="faint" style={{ fontSize: 12.5 }}>Goes to HR, Finance and Admin — any one can approve.</span>
            <div className="row" style={{ gap: 10 }}>
              <button className="btn btn-ghost" onClick={() => setOpen(false)}>Cancel</button>
              <button className="btn btn-gold" disabled={busy} onClick={() => start(async () => {
                const r = await requestRegularization(f);
                if (r.ok) { toast(r.msg!); setOpen(false); } else toast(r.error, "bad");
              })}>{busy ? "Sending…" : "Send for approval"}</button>
            </div>
          </>}>
          <div className="col" style={{ gap: 14 }}>
            <label className="field">Request type
              <select value={f.kind} onChange={(e) => setF({ ...f, kind: e.target.value as RegInput["kind"] })}>{kinds.map((k) => <option key={k} value={k}>{KIND[k]}</option>)}</select>
            </label>
            {(f.kind === "ABSENT" || f.kind === "MISSED_CHECKOUT" || f.kind === "LATE_CHECKIN") && (
              <div className="grid2">
                <label className="field">Actual check-in <span className="hint">optional</span><input type="time" value={f.proposedIn} onChange={(e) => setF({ ...f, proposedIn: e.target.value })} /></label>
                <label className="field">Actual check-out {f.kind === "MISSED_CHECKOUT" ? "*" : <span className="hint">optional</span>}<input type="time" value={f.proposedOut} onChange={(e) => setF({ ...f, proposedOut: e.target.value })} /></label>
              </div>
            )}
            <label className="field">What happened? <span className="hint">optional</span><textarea value={f.reason} maxLength={500} onChange={(e) => setF({ ...f, reason: e.target.value })} placeholder="e.g. Was on a client call, forgot to check in" /></label>
          </div>
        </Modal>
      )}
    </>
  );
}
