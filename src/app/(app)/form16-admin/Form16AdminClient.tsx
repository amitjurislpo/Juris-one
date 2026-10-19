"use client";

import { useState, useTransition } from "react";
import { Icon } from "@/components/Icon";
import { useToast } from "@/components/shell/Toasts";
import { ConfirmDialog } from "@/components/ui/Modal";
import type { Form16Overview } from "@/lib/api-types";
import { useT } from "@/lib/i18n/client";
import { publishForm16, saveDeductor } from "./actions";

/** Publish Form 16 for the year (only offered when every month is closed). */
export function PublishButton({ fy, pending }: { fy: string; pending: number }) {
  const t = useT();
  const toast = useToast();
  const [ask, setAsk] = useState(false);
  const [busy, start] = useTransition();
  const run = () => start(async () => {
    const r = await publishForm16(fy);
    toast(r.ok ? r.msg! : r.error, r.ok ? undefined : "bad");
    setAsk(false);
  });
  return (
    <>
      <button className="btn btn-gold" disabled={pending === 0} onClick={() => setAsk(true)}><Icon n="upload" s={14} />{pending === 0 ? t("All published") : pending === 1 ? t("Publish 1 Form 16") : t("Publish {n} Form 16s", { n: pending })}</button>
      {ask && (
        <ConfirmDialog title={t("Publish Form 16 for FY {fy}", { fy })} confirm={t("Publish")} busy={busy} onClose={() => setAsk(false)}
          body={t("Build and publish Form 16 for the {n} employee(s) who don't have one for FY {fy} yet, from the closed payslips and each person's tax profile. Published forms can't be changed or removed, and each employee is told. Forms already published are left as they are.", { n: pending, fy })}
          onConfirm={run} />
      )}
    </>
  );
}

/** The deductor's TAN and PAN, printed on every Form 16. */
export function DeductorForm({ d, canEdit }: { d: Form16Overview["deductor"]; canEdit: boolean }) {
  const t = useT();
  const toast = useToast();
  const [f, setF] = useState({ tan: d.tan ?? "", pan: d.pan ?? "" });
  const [err, setErr] = useState("");
  const [busy, start] = useTransition();
  const dirty = f.tan !== (d.tan ?? "") || f.pan !== (d.pan ?? "");
  const save = () => start(async () => {
    const r = await saveDeductor(f);
    if (r.ok) { toast(r.msg!); setErr(""); } else setErr(r.error);
  });
  return (
    <div className="col" style={{ gap: 12 }}>
      <div className="muted" style={{ fontSize: 13 }}>
        <b>{d.name}</b>{d.address ? <> · {d.address}</> : null}{d.gstin ? <> · GSTIN {d.gstin}</> : null}
        <div className="faint">{t("Name, address and GSTIN come from the business profile.")}</div>
      </div>
      <div style={{ display: "flex", gap: 12, flexWrap: "wrap", alignItems: "flex-end" }}>
        <label className="field" style={{ minWidth: 180 }}>TAN <span className="hint">{t("optional")}</span>
          <input value={f.tan} disabled={!canEdit} maxLength={10} placeholder="ABCD12345E" onChange={(e) => { setF({ ...f, tan: e.target.value.toUpperCase() }); setErr(""); }} />
        </label>
        <label className="field" style={{ minWidth: 180 }}>{t("PAN of the deductor")} <span className="hint">{t("optional")}</span>
          <input value={f.pan} disabled={!canEdit} maxLength={10} placeholder="ABCDE1234F" onChange={(e) => { setF({ ...f, pan: e.target.value.toUpperCase() }); setErr(""); }} />
        </label>
        {canEdit && <button className="btn" disabled={busy || !dirty} onClick={save}>{busy ? t("Saving…") : t("Save")}</button>}
      </div>
      {err && <div className="err" role="alert">{err}</div>}
    </div>
  );
}
