"use client";

import { useState, useTransition } from "react";
import { Icon } from "@/components/Icon";
import { useToast } from "@/components/shell/Toasts";
import { ConfirmDialog, Modal } from "@/components/ui/Modal";
import { createDepartment, deleteDepartment } from "./actions";

export function NewDepartmentButton({ by, heads }: { by: string; heads: { id: string; name: string; title: string }[] }) {
  const toast = useToast();
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
      <button className="btn btn-gold btn-lg" onClick={() => setOpen(true)}><Icon n="plus" s={16} />New department</button>
      {open && (
        <Modal title="New department" eyebrow={`Created by ${by}`} size="sm" onClose={close}
          footer={<><button className="btn btn-ghost" onClick={close}>Cancel</button><button className="btn btn-gold" disabled={busy} onClick={save}>{busy ? "Creating…" : "Create department"}</button></>}>
          <div className="col" style={{ gap: 14 }}>
            <label className="field">Department name *
              <input value={f.name} onChange={(e) => { setF({ ...f, name: e.target.value }); setErr(""); }} placeholder="e.g. Intellectual Property" maxLength={80} autoFocus onKeyDown={(e) => e.key === "Enter" && save()} />
              {tried && !f.name.trim() && <span className="err">Name is required</span>}
            </label>
            <label className="field">Department head <span className="hint">optional</span>
              <select value={f.headId} onChange={(e) => setF({ ...f, headId: e.target.value })}>
                <option value="">Not assigned yet</option>
                {heads.map((h) => <option key={h.id} value={h.id}>{h.name} — {h.title}</option>)}
              </select>
            </label>
            <label className="field">Description <span className="hint">optional</span>
              <input value={f.description} onChange={(e) => setF({ ...f, description: e.target.value })} placeholder="What this team does" maxLength={300} />
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
  const [ask, setAsk] = useState(false);
  const [busy, start] = useTransition();
  return (
    <>
      <button className="btn btn-sm btn-ghost" onClick={() => setAsk(true)} aria-label={`Delete ${name}`}><Icon n="trash" s={14} /></button>
      {ask && (
        <ConfirmDialog title="Delete department" danger confirm="Delete" busy={busy} onClose={() => setAsk(false)}
          body={<>Delete <b>{name}</b>? It has no current employees. Past employees keep it on their record.</>}
          onConfirm={() => start(async () => { const r = await deleteDepartment(id); toast(r.ok ? r.msg! : r.error, "bad"); setAsk(false); })} />
      )}
    </>
  );
}
