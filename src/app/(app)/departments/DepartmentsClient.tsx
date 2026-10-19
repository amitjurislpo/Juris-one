"use client";

import { useState, useTransition } from "react";
import { Icon } from "@/components/Icon";
import { useToast } from "@/components/shell/Toasts";
import { ConfirmDialog, Modal } from "@/components/ui/Modal";
import { useT } from "@/lib/i18n/client";
import { createDepartment, deleteDepartment } from "./actions";

export function NewDepartmentButton({ by, heads }: { by: string; heads: { id: string; name: string; title: string }[] }) {
  const toast = useToast();
  const t = useT();
  const [open, setOpen] = useState(false);
  const [f, setF] = useState({ name: "", headId: "", description: "" });
  const [tried, setTried] = useState(false);
  const [err, setErr] = useState("");
  const [busy, start] = useTransition();
  const close = () => { setOpen(false); setTried(false); setErr(""); setF({ name: "", headId: "", description: "" }); };
  const save = () => {
    setTried(true);
    if (!f.name.trim()) return;
    start(async () => {
      const r = await createDepartment(f);
      if (r.ok) { toast(r.msg!); close(); }
      else setErr(r.error);
    });
  };
  return (
    <>
      <button className="btn btn-gold btn-lg" onClick={() => setOpen(true)}><Icon n="plus" s={16} />{t("New department")}</button>
      {open && (
        <Modal title={t("New department")} eyebrow={t("Created by {name}", { name: by })} size="sm" onClose={close}
          footer={<><button className="btn btn-ghost" onClick={close}>{t("Cancel")}</button><button className="btn btn-gold" disabled={busy} onClick={save}>{busy ? t("Creating…") : t("Create department")}</button></>}>
          <div className="col" style={{ gap: 14 }}>
            <label className="field">{t("Department name *")}
              <input value={f.name} onChange={(e) => { setF({ ...f, name: e.target.value }); setErr(""); }} placeholder={t("e.g. Intellectual Property")} maxLength={80} autoFocus onKeyDown={(e) => e.key === "Enter" && save()} />
              {tried && !f.name.trim() && <span className="err">{t("Name is required")}</span>}
            </label>
            <label className="field">{t("Department head")} <span className="hint">{t("optional")}</span>
              <select value={f.headId} onChange={(e) => setF({ ...f, headId: e.target.value })}>
                <option value="">{t("Not assigned yet")}</option>
                {heads.map((h) => <option key={h.id} value={h.id}>{h.name} — {h.title}</option>)}
              </select>
            </label>
            <label className="field">{t("Description")} <span className="hint">{t("optional")}</span>
              <input value={f.description} onChange={(e) => setF({ ...f, description: e.target.value })} placeholder={t("What this team does")} maxLength={300} />
            </label>
            {err && <div className="err" role="alert">{err}</div>}
          </div>
        </Modal>
      )}
    </>
  );
}

export function DepartmentActions({ id, name }: { id: string; name: string }) {
  const toast = useToast();
  const t = useT();
  const [ask, setAsk] = useState(false);
  const [busy, start] = useTransition();
  const delParts = t("Delete {name}? It has no current employees. Past employees keep it on their record.", { name: "\u0000" }).split("\u0000");
  return (
    <>
      <button className="btn btn-sm btn-ghost" onClick={() => setAsk(true)} aria-label={t("Delete {name}", { name })}><Icon n="trash" s={14} /></button>
      {ask && (
        <ConfirmDialog title={t("Delete department")} danger confirm={t("Delete")} busy={busy} onClose={() => setAsk(false)}
          body={<>{delParts[0]}<b>{name}</b>{delParts[1]}</>}
          onConfirm={() => start(async () => { const r = await deleteDepartment(id); toast(r.ok ? r.msg! : r.error, "bad"); setAsk(false); })} />
      )}
    </>
  );
}
