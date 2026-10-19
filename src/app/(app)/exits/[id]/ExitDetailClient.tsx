"use client";

import { useState, useTransition } from "react";
import { Icon } from "@/components/Icon";
import { useToast } from "@/components/shell/Toasts";
import { ConfirmDialog, Modal } from "@/components/ui/Modal";
import { fmtDY, inr } from "@/lib/format";
import { useT } from "@/lib/i18n/client";
import { addClearanceItem, approveSettlement, clearItem, saveNotice, saveSettlement, setExitStatus } from "../actions";

type Run = (fn: () => Promise<{ ok: boolean; msg?: string; error?: string }>, done?: () => void) => void;

function useRun(): { run: Run; busy: boolean } {
  const toast = useToast();
  const [busy, start] = useTransition();
  const run: Run = (fn, done) => start(async () => {
    const r = await fn();
    toast(r.ok ? r.msg! : r.error!, r.ok ? undefined : "bad");
    if (r.ok) done?.();
  });
  return { run, busy };
}

// ───────────────────────── notice ─────────────────────────

/** HR agrees the last working day and may shorten or waive the notice (a reason is required then). */
export function NoticeButton({ id, lastWorkingDay, noticeDays, policyDays }: { id: string; lastWorkingDay: string; noticeDays: number; policyDays: number }) {
  const t = useT();
  const [open, setOpen] = useState(false);
  const [day, setDay] = useState(lastWorkingDay);
  const [days, setDays] = useState(String(noticeDays));
  const [reason, setReason] = useState("");
  const [err, setErr] = useState("");
  const { run, busy } = useRun();
  const shorter = Number(days) < policyDays;
  const save = () => run(async () => {
    const r = await saveNotice(id, { lastWorkingDay: day, noticeDays: Number(days), reason: shorter ? reason : undefined });
    if (!r.ok) setErr(r.error);
    return r;
  }, () => setOpen(false));
  return (
    <>
      <button className="btn" onClick={() => setOpen(true)}><Icon n="cal" s={14} />{t("Last day & notice")}</button>
      {open && (
        <Modal title={t("Last working day and notice")} eyebrow={t("Policy notice: {n} days", { n: policyDays })} size="sm" onClose={() => setOpen(false)}
          footer={<><button className="btn btn-ghost" onClick={() => setOpen(false)}>{t("Cancel")}</button><button className="btn btn-gold" disabled={busy || !day || (shorter && reason.trim().length < 3)} onClick={save}>{busy ? t("Saving…") : t("Save")}</button></>}>
          <div className="col" style={{ gap: 14 }}>
            <label className="field">{t("Last working day *")}<input type="date" value={day} onChange={(e) => { setDay(e.target.value); setErr(""); }} /></label>
            <label className="field">{t("Notice days")} <span className="hint">{t("0 waives it")}</span><input type="number" min={0} max={365} value={days} onChange={(e) => { setDays(e.target.value); setErr(""); }} /></label>
            {shorter && <label className="field">{t("Why is the notice shortened or waived? *")}<input value={reason} onChange={(e) => { setReason(e.target.value); setErr(""); }} maxLength={500} /></label>}
            {err && <div className="err" role="alert">{err}</div>}
          </div>
        </Modal>
      )}
    </>
  );
}

// ───────────────────────── status actions ─────────────────────────

/** Accept → start clearance → settlement → close, and cancel. Closing marks the employee as a past employee. */
export function ExitActions({ id, status, name, hasLastDay }: { id: string; status: string; name: string; hasLastDay: boolean }) {
  const t = useT();
  const [ask, setAsk] = useState<null | "cancel" | "close">(null);
  const [comment, setComment] = useState("");
  const { run, busy } = useRun();
  const go = (to: "NOTICE" | "CLEARANCE" | "FNF_PENDING" | "CLOSED" | "CANCELLED", c?: string) => run(() => setExitStatus(id, to, c), () => { setAsk(null); setComment(""); });
  return (
    <>
      {(status === "RESIGNED" || status === "INITIATED") && <button className="btn btn-gold" disabled={busy || !hasLastDay} title={hasLastDay ? undefined : t("Set the last working day first")} onClick={() => go("NOTICE")}><Icon n="check" s={14} />{t("Accept & start notice")}</button>}
      {status === "NOTICE" && <button className="btn btn-gold" disabled={busy} onClick={() => go("CLEARANCE")}>{t("Start clearance")}</button>}
      {status === "CLEARANCE" && <button className="btn btn-gold" disabled={busy} onClick={() => go("FNF_PENDING")}>{t("Clearance complete → settlement")}</button>}
      {status === "SETTLED" && <button className="btn btn-gold" disabled={busy} onClick={() => setAsk("close")}>{t("Close exit")}</button>}
      {["RESIGNED", "INITIATED", "NOTICE", "CLEARANCE", "FNF_PENDING"].includes(status) && <button className="btn btn-ghost" onClick={() => setAsk("cancel")}>{t("Cancel exit")}</button>}
      {ask === "cancel" && (
        <Modal title={t("Cancel this exit")} eyebrow={name} size="sm" onClose={() => setAsk(null)}
          footer={<><button className="btn btn-ghost" onClick={() => setAsk(null)}>{t("Keep it")}</button><button className="btn btn-gold" disabled={busy || comment.trim().length < 3} onClick={() => go("CANCELLED", comment)}>{t("Cancel exit")}</button></>}>
          <label className="field">{t("Why? *")} <span className="hint">{t("the employee is told")}</span><textarea value={comment} onChange={(e) => setComment(e.target.value)} rows={3} maxLength={1000} autoFocus /></label>
        </Modal>
      )}
      {ask === "close" && (
        <ConfirmDialog title={t("Close the exit")} confirm={t("Mark as past employee")} danger busy={busy} onClose={() => setAsk(null)}
          body={<>{t("Mark")} <b>{name}</b> {t("as a past employee? Their access is removed and they are signed out everywhere. This follows the settlement and can't be undone.")}</>}
          onConfirm={() => go("CLOSED")} />
      )}
    </>
  );
}

// ───────────────────────── clearance ─────────────────────────

export function ClearanceRow({ exitId, item, editable }: {
  exitId: string; editable: boolean;
  item: { id: string; dept: string; deptLabel: string; title: string; cleared: boolean; remarks: string | null; clearedAt: string | null; clearedBy: string | null };
}) {
  const t = useT();
  const [open, setOpen] = useState(false);
  const [remarks, setRemarks] = useState(item.remarks ?? "");
  const { run, busy } = useRun();
  const set = (cleared: boolean) => run(() => clearItem(exitId, item.id, cleared, remarks.trim() || undefined), () => setOpen(false));
  return (
    <div className="kv">
      <span>
        <Icon n={item.cleared ? "check" : "clock"} s={14} /> {item.title} <small className="muted">· {item.deptLabel}</small>
        {item.remarks && <><br /><small className="muted">{item.remarks}</small></>}
        {item.cleared && item.clearedAt && <><br /><small className="faint">{item.clearedBy ? t("Cleared {date} by {name}", { date: fmtDY(item.clearedAt.slice(0, 10)), name: item.clearedBy }) : t("Cleared {date}", { date: fmtDY(item.clearedAt.slice(0, 10)) })}</small></>}
      </span>
      {editable ? (
        <button className="btn btn-sm" onClick={() => setOpen(true)}>{item.cleared ? t("Reopen") : t("Clear")}</button>
      ) : <span className={`chip ${item.cleared ? "ok" : "neutral"}`}>{item.cleared ? t("Cleared") : t("Pending")}</span>}
      {open && (
        <Modal title={item.cleared ? t("Reopen item") : t("Clear item")} eyebrow={item.title} size="sm" onClose={() => setOpen(false)}
          footer={<><button className="btn btn-ghost" onClick={() => setOpen(false)}>{t("Cancel")}</button><button className="btn btn-gold" disabled={busy} onClick={() => set(!item.cleared)}>{item.cleared ? t("Reopen") : t("Mark cleared")}</button></>}>
          <label className="field">{t("Remarks")} <span className="hint">{t("optional")}</span><textarea value={remarks} onChange={(e) => setRemarks(e.target.value)} rows={3} maxLength={300} autoFocus /></label>
        </Modal>
      )}
    </div>
  );
}

export function AddItemButton({ id }: { id: string }) {
  const t = useT();
  const [open, setOpen] = useState(false);
  const [dept, setDept] = useState("HR");
  const [title, setTitle] = useState("");
  const { run, busy } = useRun();
  return (
    <>
      <button className="btn btn-sm btn-ghost" onClick={() => setOpen(true)}><Icon n="plus" s={13} />{t("Add item")}</button>
      {open && (
        <Modal title={t("Add a checklist item")} size="sm" onClose={() => setOpen(false)}
          footer={<><button className="btn btn-ghost" onClick={() => setOpen(false)}>{t("Cancel")}</button><button className="btn btn-gold" disabled={busy || title.trim().length < 3} onClick={() => run(() => addClearanceItem(id, dept, title), () => { setOpen(false); setTitle(""); })}>{t("Add")}</button></>}>
          <div className="col" style={{ gap: 14 }}>
            <label className="field">{t("Who clears it")}
              <select value={dept} onChange={(e) => setDept(e.target.value)}><option value="HR">{t("HR")}</option><option value="FINANCE">{t("Finance")}</option><option value="IT">{t("IT / assets")}</option><option value="MANAGER">{t("Manager")}</option></select>
            </label>
            <label className="field">{t("Item *")}<input value={title} onChange={(e) => setTitle(e.target.value)} maxLength={120} autoFocus /></label>
          </div>
        </Modal>
      )}
    </>
  );
}

// ───────────────────────── settlement (amounts) ─────────────────────────

interface Line { label: string; amount: number }
interface Result {
  daysInMonth: number; eligibleDays: number; lopDays: number; paidDays: number; salaryAlreadyPaid: boolean;
  components: { label: string; rate: number; earned: number }[]; salaryEarned: number; pf: number; pt: number; perDayRate: number; encashableDays: number;
  leaveEncashment: number; serviceYears: number; gratuityEligible: boolean; gratuity: number; noticeRequiredDays: number; noticeServedDays: number;
  noticeShortfallDays: number; noticeRecovery: number; additions: Line[]; additionsTotal: number; deductions: Line[]; deductionsTotal: number;
  taxableGross: number; tds: number; totalEarnings: number; totalDeductions: number; net: number;
}
export interface SettlementView {
  status: "NONE" | "DRAFT" | "APPROVED"; exitStatus: string; canDraft: boolean; canApprove: boolean; additions: Line[]; deductions: Line[]; note: string | null;
  blocker?: string | null; result?: Result; month?: string; netPaise?: number; approvedBy?: string; approvedAt?: string; draftedBy?: string | null;
}

interface Row { label: string; rupees: string }
const toRows = (l: Line[]): Row[] => l.map((x) => ({ label: x.label, rupees: String(x.amount / 100) }));
const toLines = (r: Row[]): Line[] => r.filter((x) => x.label.trim() || x.rupees.trim()).map((x) => ({ label: x.label.trim(), amount: Math.round(Number(x.rupees || 0) * 100) }));

function LineEditor({ title, rows, set }: { title: string; rows: Row[]; set: (r: Row[]) => void }) {
  const t = useT();
  return (
    <div className="field">{title}
      {rows.map((r, i) => (
        <div key={i} style={{ display: "flex", gap: 8, marginBottom: 6 }}>
          <input aria-label={t("{title} name", { title })} placeholder={t("e.g. Retention bonus")} value={r.label} maxLength={80} onChange={(e) => set(rows.map((x, j) => (j === i ? { ...x, label: e.target.value } : x)))} />
          <input aria-label={t("{title} amount in rupees", { title })} type="number" min={0} step="1" placeholder="₹" style={{ maxWidth: 140 }} value={r.rupees} onChange={(e) => set(rows.map((x, j) => (j === i ? { ...x, rupees: e.target.value } : x)))} />
          <button type="button" className="icon-btn" aria-label={t("Remove line")} onClick={() => set(rows.filter((_, j) => j !== i))}><Icon n="x" s={14} /></button>
        </div>
      ))}
      <button type="button" className="btn btn-sm btn-ghost" onClick={() => set([...rows, { label: "", rupees: "" }])}><Icon n="plus" s={13} />{t("Add line")}</button>
    </div>
  );
}

const money = (p: number) => (p < 0 ? "−" : "") + inr(Math.abs(p));

/** Full & final settlement: the computation, Finance's own lines, draft → approve, and the PDF statement. */
export function SettlementPanel({ exitId, s }: { exitId: string; s: SettlementView }) {
  const t = useT();
  const [adds, setAdds] = useState<Row[]>(toRows(s.additions));
  const [deds, setDeds] = useState<Row[]>(toRows(s.deductions));
  const [note, setNote] = useState(s.note ?? "");
  const [confirm, setConfirm] = useState(false);
  const { run, busy } = useRun();
  const r = s.result;
  const approved = s.status === "APPROVED";
  const save = () => run(() => saveSettlement(exitId, { additions: toLines(adds), deductions: toLines(deds), note: note.trim() || undefined }));

  return (
    <>
      <div className="card-h">
        <h3>{t("Full & final settlement")}</h3>
        {approved ? <span className="chip ok">{t("Approved")}</span> : s.status === "DRAFT" ? <span className="chip warn">{t("Draft")}</span> : <span className="chip neutral">{t("Not started")}</span>}
        {approved && <a className="btn btn-sm" href={`/api/exits/${exitId}/settlement/statement`}><Icon n="download" s={13} />{t("Statement (PDF)")}</a>}
      </div>

      {!r && <p className="muted" style={{ margin: 0 }}>{s.blocker ?? (["NOTICE", "CLEARANCE", "RESIGNED", "INITIATED"].includes(s.exitStatus) ? t("The settlement is prepared once clearance is complete.") : t("Nothing to show."))}</p>}

      {r && (
        <div className="grid2" style={{ alignItems: "start", marginTop: 8 }}>
          <div>
            <b className="muted" style={{ fontSize: 12 }}>{t("PAYABLE")}</b>
            {r.salaryAlreadyPaid
              ? <div className="kv"><span>{t("Salary for {month}", { month: s.month ?? "" })}</span><span className="muted">{t("already paid through payroll")}</span></div>
              : r.components.filter((c) => c.rate).map((c) => <div key={c.label} className="kv"><span>{c.label}</span><b>{money(c.earned)}</b></div>)}
            {!r.salaryAlreadyPaid && <div className="kv"><span className="muted">{t("{eligible} of {total} days", { eligible: r.eligibleDays, total: r.daysInMonth })}{r.lopDays ? `, ${t("{n} LOP", { n: r.lopDays })}` : ""}</span><span></span></div>}
            {(r.leaveEncashment !== 0 || r.encashableDays > 0) && <div className="kv"><span>{t("Leave encashment")} <small className="muted">{t("{n} days", { n: r.encashableDays })} × {money(r.perDayRate)}</small></span><b>{money(r.leaveEncashment)}</b></div>}
            {r.gratuity !== 0 && <div className="kv"><span>{t("Gratuity")} <small className="muted">{t("{n} years", { n: r.serviceYears })}</small></span><b>{money(r.gratuity)}</b></div>}
            {r.additions.map((l, i) => <div key={`a${i}`} className="kv"><span>{l.label}</span><b>{money(l.amount)}</b></div>)}
            <div className="kv"><b>{t("Total earnings")}</b><b>{money(r.totalEarnings)}</b></div>
          </div>
          <div>
            <b className="muted" style={{ fontSize: 12 }}>{t("DEDUCTIONS")}</b>
            {!!r.pf && <div className="kv"><span>{t("Provident fund")}</span><b>{money(r.pf)}</b></div>}
            {!!r.pt && <div className="kv"><span>{t("Professional tax")}</span><b>{money(r.pt)}</b></div>}
            {!!r.noticeRecovery && <div className="kv"><span>{t("Notice recovery")} <small className="muted">{t("{short} of {required} days short", { short: r.noticeShortfallDays, required: r.noticeRequiredDays })}</small></span><b>{money(r.noticeRecovery)}</b></div>}
            {r.deductions.map((l, i) => <div key={`d${i}`} className="kv"><span>{l.label}</span><b>{money(l.amount)}</b></div>)}
            <div className="kv"><span>{t("Income tax (TDS)")}</span><b>{money(r.tds)}</b></div>
            <div className="kv"><b>{t("Total deductions")}</b><b>{money(r.totalDeductions)}</b></div>
          </div>
        </div>
      )}

      {r && (
        <div className="card" style={{ padding: 14, marginTop: 14, background: r.net < 0 ? "var(--badbg)" : "var(--accentsoft)", borderColor: "transparent", display: "flex", justifyContent: "space-between", alignItems: "center" }}>
          <b>{r.net < 0 ? t("Recoverable from the employee") : t("Net payable to the employee")}</b>
          <b className="display" style={{ fontSize: 24 }}>{money(Math.abs(r.net))}</b>
        </div>
      )}
      {approved && s.approvedBy && <p className="faint" style={{ fontSize: 12, margin: "8px 0 0" }}>{s.approvedAt ? t("Approved by {name} on {date}. The statement is frozen.", { name: s.approvedBy, date: fmtDY(s.approvedAt.slice(0, 10)) }) : t("Approved by {name}. The statement is frozen.", { name: s.approvedBy })}</p>}

      {s.canDraft && (
        <div className="col" style={{ gap: 12, marginTop: 16 }}>
          <b className="muted" style={{ fontSize: 12 }}>{t("YOUR ADDITIONS AND DEDUCTIONS")}</b>
          <div className="grid2" style={{ alignItems: "start" }}>
            <LineEditor title={t("Additions (bonus, arrears…)")} rows={adds} set={setAdds} />
            <LineEditor title={t("Deductions (advances, damages…)")} rows={deds} set={setDeds} />
          </div>
          <label className="field">{t("Note")} <span className="hint">{t("printed on the statement")}</span><input value={note} onChange={(e) => setNote(e.target.value)} maxLength={500} /></label>
        </div>
      )}
      {(s.canDraft || s.canApprove) && (
        <div style={{ display: "flex", gap: 8, marginTop: 14 }}>
          {s.canDraft && <button className="btn" disabled={busy} onClick={save}>{busy ? t("Saving…") : t("Save & recompute")}</button>}
          {s.canApprove && <button className="btn btn-gold" disabled={busy} onClick={() => setConfirm(true)}><Icon n="check" s={14} />{t("Approve settlement")}</button>}
        </div>
      )}
      {confirm && (
        <ConfirmDialog title={t("Approve the settlement")} confirm={t("Approve")} busy={busy} onClose={() => setConfirm(false)}
          body={<>{r && r.net < 0 ? t("Approve a net recovery of") : t("Approve a net payment of")} <b>{r ? money(Math.abs(r.net)) : ""}</b>? {t("The statement is frozen, the leave balance is reduced by the days encashed, and regular payroll stops including this employee.")}</>}
          onConfirm={() => run(() => approveSettlement(exitId), () => setConfirm(false))} />
      )}
    </>
  );
}
