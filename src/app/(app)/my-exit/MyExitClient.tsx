"use client";

import { useState, useTransition } from "react";
import { Icon } from "@/components/Icon";
import { useToast } from "@/components/shell/Toasts";
import { ConfirmDialog, Modal } from "@/components/ui/Modal";
import { resign, withdrawResignation } from "./actions";

/** Submit a resignation: why, and the last working day you would like. */
export function ResignButton({ noticeDays, today }: { noticeDays: number; today: string }) {
  const toast = useToast();
  const [open, setOpen] = useState(false);
  const [reason, setReason] = useState("");
  const [day, setDay] = useState("");
  const [err, setErr] = useState("");
  const [busy, start] = useTransition();
  const close = () => { setOpen(false); setErr(""); };
  const save = () => start(async () => {
    const r = await resign({ reason, requestedLastDay: day });
    if (r.ok) { toast(r.msg!); close(); } else setErr(r.error);
  });
  return (
    <>
      <button className="btn btn-gold btn-lg" onClick={() => setOpen(true)}><Icon n="logout" s={16} />Resign</button>
      {open && (
        <Modal title="Submit your resignation" eyebrow={`Your notice period is ${noticeDays} days`} size="sm" onClose={close}
          footer={<><button className="btn btn-ghost" onClick={close}>Cancel</button><button className="btn btn-gold" disabled={busy || reason.trim().length < 3 || !day} onClick={save}>{busy ? "Submitting…" : "Submit resignation"}</button></>}>
          <div className="col" style={{ gap: 14 }}>
            <label className="field">Reason *<textarea value={reason} onChange={(e) => { setReason(e.target.value); setErr(""); }} rows={4} maxLength={1000} autoFocus /></label>
            <label className="field">Requested last working day * <span className="hint">HR confirms it</span>
              <input type="date" value={day} min={today} onChange={(e) => { setDay(e.target.value); setErr(""); }} />
            </label>
            {err && <div className="err" role="alert">{err}</div>}
          </div>
        </Modal>
      )}
    </>
  );
}

/** Take the resignation back (until clearance starts). */
export function WithdrawButton() {
  const toast = useToast();
  const [open, setOpen] = useState(false);
  const [note, setNote] = useState("");
  const [busy, start] = useTransition();
  const run = () => start(async () => {
    const r = await withdrawResignation(note.trim() || undefined);
    toast(r.ok ? r.msg! : r.error, r.ok ? undefined : "bad");
    if (r.ok) setOpen(false);
  });
  return (
    <>
      <button className="btn" onClick={() => setOpen(true)}>Withdraw resignation</button>
      {open && (
        <ConfirmDialog title="Withdraw your resignation" confirm="Withdraw" busy={busy} onClose={() => setOpen(false)} onConfirm={run}
          body="You will stay on and your exit will be closed. You can only withdraw until clearance starts.">
          <label className="field" style={{ marginTop: 12 }}>Note for HR <span className="hint">optional</span><input value={note} onChange={(e) => setNote(e.target.value)} maxLength={500} /></label>
        </ConfirmDialog>
      )}
    </>
  );
}
