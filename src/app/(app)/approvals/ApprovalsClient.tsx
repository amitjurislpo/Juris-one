"use client";

import { useState, useTransition } from "react";
import { Icon } from "@/components/Icon";
import { useToast } from "@/components/shell/Toasts";
import { ConfirmDialog } from "@/components/ui/Modal";
import { decideLeave, decideRegularization } from "./actions";

export function DecideButtons({ id, kind, who }: { id: string; kind: "leave" | "reg"; who: string }) {
  const toast = useToast();
  const [busy, start] = useTransition();
  const [declining, setDeclining] = useState(false);
  const [note, setNote] = useState("");
  const decide = (approve: boolean) =>
    start(async () => {
      const r = await (kind === "leave" ? decideLeave : decideRegularization)({ id, approve, note: note || undefined });
      toast(r.ok ? r.msg! : r.error, r.ok && approve ? "ok" : "bad");
      setDeclining(false);
    });
  return (
    <div className="acts">
      <button className="btn btn-danger" disabled={busy} onClick={() => setDeclining(true)}>Decline</button>
      <button className="btn btn-gold" disabled={busy} onClick={() => decide(true)} aria-label={`Approve ${who}`}><Icon n="ok" s={16} />Approve</button>
      {declining && (
        <ConfirmDialog title="Decline request" danger confirm="Decline" busy={busy} onClose={() => setDeclining(false)} body={<>Decline <b>{who}</b>’s request? They&apos;ll get an email.</>} onConfirm={() => decide(false)}>
          <label className="field" style={{ marginTop: 14 }}>Note to the employee <span className="hint">optional</span><input value={note} maxLength={300} onChange={(e) => setNote(e.target.value)} placeholder="e.g. Please apply after the filing deadline" /></label>
        </ConfirmDialog>
      )}
    </div>
  );
}
