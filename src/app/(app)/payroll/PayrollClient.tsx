"use client";

import { useState, useSyncExternalStore, useTransition } from "react";
import { Icon } from "@/components/Icon";
import { useToast } from "@/components/shell/Toasts";
import { SortTh } from "@/components/table/Table";
import { useQuery } from "@/components/table/useQuery";
import { ConfirmDialog, Modal } from "@/components/ui/Modal";
import { avc, inr, initials, ymLabel } from "@/lib/format";
import type { PayView } from "@/lib/payroll/view";
import { approveAndClose, resetAdjustment, saveAdjustment } from "./actions";

// "Hide amounts" is a per-viewer preference kept in localStorage (shared across payroll pages).
const KEY = "jo_hide_amounts";
const listeners = new Set<() => void>();
const readHidden = () => { try { return localStorage.getItem(KEY) === "1"; } catch { return false; } };
function useHidden(): [boolean, (v: boolean) => void] {
  const hidden = useSyncExternalStore((cb) => { listeners.add(cb); return () => listeners.delete(cb); }, readHidden, () => false);
  return [hidden, (v) => { try { localStorage.setItem(KEY, v ? "1" : "0"); } catch {} listeners.forEach((l) => l()); }];
}
const MASK = "₹ ••,•••";

export function MonthSeg({ months, value }: { months: string[]; value: string }) {
  const { set } = useQuery();
  return (
    <div className="seg" role="group" aria-label="Payroll month">
      {months.map((x) => <button key={x} type="button" className={value === x ? "on" : undefined} aria-pressed={value === x} onClick={() => set({ m: x })}>{ymLabel(x).slice(0, 3)} {x.slice(2, 4)}</button>)}
    </div>
  );
}

export function PayrollKpis({ tot }: { tot: { n: number; earned: number; lop: number; tds: number; net: number } }) {
  const [hidden, setHidden] = useHidden();
  const m = (v: number) => (hidden ? MASK : inr(v));
  return (
    <>
      <div style={{ display: "flex", justifyContent: "flex-end", marginBottom: -8 }}>
        <button className="btn btn-sm" onClick={() => setHidden(!hidden)}><Icon n={hidden ? "eye" : "eyeoff"} s={14} />{hidden ? "Show amounts" : "Hide amounts"}</button>
      </div>
      <div className="kpis">
        <div className="card kpi enter" style={{ ["--i" as string]: 3 }}><span className="spot"></span><span className="l">Employees</span><span className="v num">{tot.n}</span></div>
        <div className="card kpi enter" style={{ ["--i" as string]: 4 }}><span className="spot"></span><span className="l">Gross earned</span><span className="v num">{m(tot.earned)}</span></div>
        <div className="card kpi enter" style={{ ["--i" as string]: 5 }}><span className="spot"></span><span className="l" style={{ color: "var(--bad)" }}>Loss-of-pay days</span><span className="v num">{tot.lop}</span></div>
        <div className="card kpi enter" style={{ ["--i" as string]: 6 }}><span className="spot"></span><span className="l">TDS to deposit</span><span className="v num">{m(tot.tds)}</span></div>
        <div className="card kpi enter kpi-accent" style={{ ["--i" as string]: 7 }}><span className="spot"></span><span className="l">Net payout</span><span className="v num">{m(tot.net)}</span></div>
      </div>
    </>
  );
}

export function CloseButton({ month, closed, locked, count }: { month: string; closed: boolean; locked: boolean; count: number }) {
  const toast = useToast();
  const [ask, setAsk] = useState(false);
  const [busy, start] = useTransition();
  const { set } = useQuery();
  if (closed) return <button className="btn btn-gold" disabled>Closed ✓</button>;
  return (
    <>
      <button className="btn btn-gold" disabled={!locked || busy} title={locked ? undefined : "Attendance isn't locked yet"} onClick={() => setAsk(true)}>{busy ? "Closing…" : "Approve & close"}</button>
      {ask && (
        <ConfirmDialog title={`Close ${ymLabel(month)} payroll`} confirm="Approve & close" busy={busy} onClose={() => setAsk(false)}
          body={<>This publishes {count} payslips and freezes them. Later changes to salary, regime or attendance won&apos;t change them — corrections go into the next month as arrears.</>}
          onConfirm={() => start(async () => { const r = await approveAndClose(month); toast(r.ok ? r.msg! : r.error, r.ok ? "ok" : "bad"); setAsk(false); if (r.ok) set({ m: month }) /* stay on the month just closed */; })} />
      )}
    </>
  );
}

export function PayrollTable({ rows, month, closed }: { rows: PayView[]; month: string; closed: boolean }) {
  const [hidden] = useHidden();
  const [editing, setEditing] = useState<PayView | null>(null);
  const m = (v: number) => (hidden ? "••••" : inr(v));
  return (
    <>
      <div className="table-wrap">
        <table className="t">
          <thead>
            <tr>
              <SortTh k="name" dflt="name">Employee</SortTh>
              <SortTh k="gross" dflt="name" right>Monthly gross</SortTh>
              <SortTh k="lop" dflt="name" right>LOP</SortTh>
              <SortTh k="paid" dflt="name" right>Paid days</SortTh>
              <th className="r">Bonus</th>
              <SortTh k="earned" dflt="name" right>Earned</SortTh>
              <th className="r">PF + PT</th>
              <SortTh k="tds" dflt="name" right>TDS</SortTh>
              <SortTh k="net" dflt="name" right>Net pay</SortTh>
              <th><span className="sr-only">Actions</span></th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r, i) => (
              <tr key={r.employeeId} style={{ animationDelay: `${i * 35}ms` }}>
                <td><div className="person"><span className={`avatar ${avc(initials(r.name))}`}>{initials(r.name)}</span><div><b>{r.name}</b><small>{r.code} · {r.regime === "OLD" ? "Old" : "New"} regime{r.edited ? " · edited" : ""}</small></div></div></td>
                <td className="r num muted">{m(r.monthlyGross)}</td>
                <td className="r num" style={{ color: r.lop ? "var(--bad)" : "var(--faint)" }}>{r.lop}</td>
                <td className="r num">{r.paid}/{r.days}</td>
                <td className="r num muted">{r.bonus ? m(r.bonus) : "—"}</td>
                <td className="r num">{m(r.earned)}</td>
                <td className="r num muted">{m(r.pfPt)}</td>
                <td className="r num muted">{m(r.tds)}</td>
                <td className="r num" style={{ color: "var(--accenttext)", fontWeight: 600 }}>{m(r.net)}</td>
                <td className="r">{closed ? <span className="faint" style={{ fontSize: 12 }}>Locked</span> : <button className="btn btn-sm btn-ghost" onClick={() => setEditing(r)} aria-label={`Edit payroll for ${r.name}`}><Icon n="edit" s={14} />Edit</button>}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {!rows.length && <div className="empty"><b>No one here</b><span className="muted">Nobody matches, or no one has a salary structure for this month yet.</span></div>}
      {editing && <EditPay row={editing} month={month} onClose={() => setEditing(null)} />}
    </>
  );
}

function EditPay({ row, month, onClose }: { row: PayView; month: string; onClose: () => void }) {
  const toast = useToast();
  const [busy, start] = useTransition();
  const [f, setF] = useState({ lop: String(row.lop), bonus: String(row.bonus / 100), other: String(row.other / 100), note: row.note ?? "" });
  const [err, setErr] = useState("");
  const save = () => start(async () => {
    const lop = Number(f.lop);
    const r = await saveAdjustment({ month, employeeId: row.employeeId, lopDays: lop === row.attendanceLop && !row.edited ? null : lop, bonus: Math.round(Number(f.bonus) || 0), otherDeduction: Math.round(Number(f.other) || 0), note: f.note });
    if (r.ok) { toast(r.msg!); onClose(); } else setErr(r.error);
  });
  return (
    <Modal title={`Edit payroll · ${row.name}`} eyebrow={`${row.code} · ${ymLabel(month)}`} size="sm" onClose={onClose}
      footer={<>
        {row.edited ? <button className="btn btn-ghost" disabled={busy} onClick={() => start(async () => { const r = await resetAdjustment(month, row.employeeId); toast(r.ok ? r.msg! : r.error); onClose(); })}>Reset to attendance</button> : <span></span>}
        <div className="row" style={{ gap: 10 }}><button className="btn btn-ghost" onClick={onClose}>Cancel</button><button className="btn btn-gold" disabled={busy} onClick={save}>Save changes</button></div>
      </>}>
      <div className="col" style={{ gap: 14 }}>
        <div className="grid3">
          <label className="field">LOP days<input type="number" min={0} step={0.5} max={row.days} value={f.lop} onChange={(e) => setF({ ...f, lop: e.target.value })} /><span className="hint">attendance: {row.attendanceLop}</span></label>
          <label className="field">Bonus / arrears (₹)<input type="number" min={0} step={1} value={f.bonus} onChange={(e) => setF({ ...f, bonus: e.target.value })} /></label>
          <label className="field">Other deduction (₹)<input type="number" min={0} step={1} value={f.other} onChange={(e) => setF({ ...f, other: e.target.value })} /></label>
        </div>
        <label className="field">Note <span className="hint">shown on the payroll register and payslip</span><input value={f.note} maxLength={200} onChange={(e) => setF({ ...f, note: e.target.value })} placeholder="e.g. Diwali bonus, laptop recovery" /></label>
        <p className="faint" style={{ margin: 0, fontSize: 12.5 }}>Monthly rates come from the salary structure ({inr(row.monthlyGross)} gross). To change them permanently, revise the structure.</p>
        {err && <p className="err" role="alert" style={{ margin: 0 }}>{err}</p>}
      </div>
    </Modal>
  );
}
