"use client";

import { useState, useTransition } from "react";
import { Icon } from "@/components/Icon";
import { useToast } from "@/components/shell/Toasts";
import { ConfirmDialog } from "@/components/ui/Modal";
import { useT } from "@/lib/i18n/client";
import { decideLeave, decideRegularization } from "./actions";

export function DecideButtons({ id, kind, who }: { id: string; kind: "leave" | "reg"; who: string }) {
  const t = useT();
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
  const declineParts = t("Decline {who}’s request? They’ll get an email.", { who: "\u0000" }).split("\u0000");
  const declineBody = <>{declineParts[0]}<b>{who}</b>{declineParts[1]}</>;
  return (
    <div className="acts">
      <button className="btn btn-danger" disabled={busy} onClick={() => setDeclining(true)}>{t("Decline")}</button>
      <button className="btn btn-gold" disabled={busy} onClick={() => decide(true)} aria-label={t("Approve {who}", { who })}><Icon n="ok" s={16} />{t("Approve")}</button>
      {declining && (
        <ConfirmDialog title={t("Decline request")} danger confirm={t("Decline")} busy={busy} onClose={() => setDeclining(false)} body={declineBody} onConfirm={() => decide(false)}>
          <label className="field" style={{ marginTop: 14 }}>{t("Note to the employee")} <span className="hint">{t("optional")}</span><input value={note} maxLength={300} onChange={(e) => setNote(e.target.value)} placeholder={t("e.g. Please apply after the filing deadline")} /></label>
        </ConfirmDialog>
      )}
    </div>
  );
}
