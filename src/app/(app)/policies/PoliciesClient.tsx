"use client";

import { useRouter } from "next/navigation";
import { useRef, useState, useTransition } from "react";
import { Icon } from "@/components/Icon";
import { useToast } from "@/components/shell/Toasts";
import { ConfirmDialog, Modal } from "@/components/ui/Modal";
import type { PolicyDetail } from "@/lib/api-types";
import { useT } from "@/lib/i18n/client";
import { createPolicy, deletePolicy, remindPolicy, removePolicyFile, setPolicyStatus, updatePolicy } from "./actions";

export function NewPolicyButton() {
  const toast = useToast();
  const t = useT();
  const router = useRouter();
  const [open, setOpen] = useState(false);
  const [title, setTitle] = useState("");
  const [err, setErr] = useState("");
  const [busy, start] = useTransition();
  const close = () => { setOpen(false); setErr(""); setTitle(""); };
  const save = () => start(async () => {
    const r = await createPolicy({ title, body: "", requiresAck: true });
    if (r.ok) { toast(r.msg!); close(); router.push(`/policies/${r.data!.id}`); } else setErr(r.error);
  });
  return (
    <>
      <button className="btn btn-gold btn-lg" onClick={() => setOpen(true)}><Icon n="plus" s={16} />{t("New policy")}</button>
      {open && (
        <Modal title={t("New policy")} eyebrow={t("Starts as a draft")} size="sm" onClose={close}
          footer={<><button className="btn btn-ghost" onClick={close}>{t("Cancel")}</button><button className="btn btn-gold" disabled={busy} onClick={save}>{busy ? t("Creating…") : t("Create draft")}</button></>}>
          <div className="col" style={{ gap: 14 }}>
            <label className="field">{t("Title *")}<input value={title} onChange={(e) => { setTitle(e.target.value); setErr(""); }} maxLength={160} placeholder={t("e.g. Code of conduct")} autoFocus
              onKeyDown={(e) => e.key === "Enter" && save()} /></label>
            {err && <div className="err" role="alert">{err}</div>}
          </div>
        </Modal>
      )}
    </>
  );
}

/** The draft: title, text, "must be accepted" and the PDF. */
export function PolicyEditor({ p }: { p: PolicyDetail }) {
  const toast = useToast();
  const t = useT();
  const router = useRouter();
  const [f, setF] = useState({ title: p.title, body: p.body, requiresAck: p.requiresAck });
  const [err, setErr] = useState("");
  const [busy, start] = useTransition();
  const [uploading, setUploading] = useState(false);
  const fileIn = useRef<HTMLInputElement>(null);
  const archived = p.status === "ARCHIVED";
  const dirty = f.title !== p.title || f.body !== p.body || f.requiresAck !== p.requiresAck;
  const save = () => start(async () => {
    const r = await updatePolicy(p.id, f);
    if (r.ok) toast(r.msg!); else setErr(r.error);
  });
  const upload = async (file: File) => {
    setUploading(true);
    const fd = new FormData();
    fd.set("file", file);
    try {
      const res = await fetch(`/api/policies/${p.id}/file`, { method: "PUT", body: fd });
      const j = await res.json().catch(() => ({}));
      if (!res.ok) toast(j.detail ?? t("Upload failed"), "bad"); else toast(j.msg ?? t("Attached"));
      router.refresh();
    } finally { setUploading(false); }
  };
  return (
    <section className="card enter" style={{ ["--i" as string]: 2, padding: 18 }}>
      <div className="card-h"><h3>{t("Draft")}</h3>{dirty && <span className="chip warn nodot">{t("Not saved")}</span>}</div>
      <div className="col" style={{ gap: 14 }}>
        <label className="field">{t("Title")}<input value={f.title} disabled={archived} onChange={(e) => { setF({ ...f, title: e.target.value }); setErr(""); }} maxLength={160} /></label>
        <label className="field">{t("Policy text")} <span className="hint">{t("plain text; a blank line starts a new paragraph")}</span>
          <textarea value={f.body} disabled={archived} onChange={(e) => { setF({ ...f, body: e.target.value }); setErr(""); }} rows={14} style={{ fontFamily: "inherit", lineHeight: 1.55 }} />
        </label>
        <div style={{ display: "flex", gap: 16, alignItems: "center", flexWrap: "wrap" }}>
          <label style={{ display: "flex", gap: 8, alignItems: "center" }}>
            <input type="checkbox" checked={f.requiresAck} disabled={archived} onChange={(e) => setF({ ...f, requiresAck: e.target.checked })} />
            {t("Everyone must accept it before using JurisOne")}
          </label>
          <span style={{ flex: 1 }} />
          {p.fileName ? (
            <span style={{ display: "flex", gap: 6, alignItems: "center" }}>
              <a className="linkish" href={`/api/policies/${p.id}/file`} target="_blank" rel="noopener"><Icon n="doc" s={14} /> {p.fileName}</a>
              {!archived && <button className="btn btn-sm btn-ghost" aria-label={t("Remove the PDF")} onClick={() => start(async () => { const r = await removePolicyFile(p.id); toast(r.ok ? r.msg! : r.error, r.ok ? undefined : "bad"); })}><Icon n="x" s={13} /></button>}
            </span>
          ) : <span className="muted">{t("No PDF attached")}</span>}
          {!archived && (
            <>
              <button className="btn btn-sm" disabled={uploading} onClick={() => fileIn.current?.click()}><Icon n="upload" s={14} />{uploading ? t("Uploading…") : p.fileName ? t("Replace PDF") : t("Attach PDF")}</button>
              <input ref={fileIn} type="file" accept="application/pdf" hidden onChange={(e) => { const file = e.target.files?.[0]; if (file) void upload(file); e.target.value = ""; }} />
            </>
          )}
        </div>
        {err && <div className="err" role="alert">{err}</div>}
        {!archived && <div><button className="btn btn-gold" disabled={busy || !dirty} onClick={save}>{busy ? t("Saving…") : t("Save draft")}</button></div>}
      </div>
    </section>
  );
}

/** Publish / archive / delete, reminders and the evidence export. */
export function PolicyActions({ p }: { p: PolicyDetail }) {
  const toast = useToast();
  const t = useT();
  const router = useRouter();
  const [ask, setAsk] = useState<null | "publish" | "archive" | "delete">(null);
  const [busy, start] = useTransition();
  const run = (fn: () => Promise<{ ok: boolean; msg?: string; error?: string }>, after?: () => void) => start(async () => {
    const r = await fn();
    toast(r.ok ? r.msg! : r.error!, r.ok ? undefined : "bad");
    setAsk(null);
    if (r.ok) after?.();
  });
  const canPublish = p.status !== "PUBLISHED" || p.hasChanges;
  return (
    <>
      {p.versions.length > 0 && <a className="btn" href={`/api/policies/${p.id}/acknowledgements/export`} download><Icon n="download" s={14} />{t("Evidence (CSV)")}</a>}
      {p.versions.length > 0 && <a className="btn" href={`/api/policies/${p.id}/acknowledgements/export?format=pdf`} download><Icon n="doc" s={14} />{t("Evidence (PDF)")}</a>}
      {p.status === "PUBLISHED" && p.requiresAck && <button className="btn" disabled={busy} onClick={() => run(() => remindPolicy(p.id))}><Icon n="bell" s={14} />{t("Remind")}</button>}
      {p.status === "PUBLISHED" && <button className="btn btn-ghost" onClick={() => setAsk("archive")}>{t("Archive")}</button>}
      {p.version === 0 && <button className="btn btn-ghost" onClick={() => setAsk("delete")} aria-label={t("Delete draft")}><Icon n="trash" s={14} /></button>}
      {canPublish && <button className="btn btn-gold" onClick={() => setAsk("publish")}><Icon n="upload" s={14} />{p.version === 0 ? t("Publish") : p.hasChanges ? t("Publish version {n}", { n: p.version + 1 }) : t("Publish again")}</button>}
      {ask === "publish" && (
        <ConfirmDialog title={p.hasChanges ? t("Publish version {n}", { n: p.version + 1 }) : t("Publish again")} confirm={t("Publish")} busy={busy} onClose={() => setAsk(null)}
          body={<>{t("Publish the saved draft of {title}? It can't be changed afterwards (a change makes a new version).", { title: p.title })}
            {" "}{p.requiresAck ? t("Everyone except owners will have to read and accept it before they can use JurisOne.") : t("It is for information only — nobody has to accept it.")}
            {" "}{t("Unsaved edits in the editor are not included.")}</>}
          onConfirm={() => run(() => setPolicyStatus(p.id, "PUBLISHED"))} />
      )}
      {ask === "archive" && (
        <ConfirmDialog title={t("Archive policy")} confirm={t("Archive")} busy={busy} onClose={() => setAsk(null)}
          body={<>{t("Archive {title}? Employees will no longer see it or be asked to accept it. Its versions and acceptances are kept.", { title: p.title })}</>}
          onConfirm={() => run(() => setPolicyStatus(p.id, "ARCHIVED"))} />
      )}
      {ask === "delete" && (
        <ConfirmDialog title={t("Delete draft")} danger confirm={t("Delete")} busy={busy} onClose={() => setAsk(null)}
          body={<>{t("Delete the draft {title}? It has never been published.", { title: p.title })}</>}
          onConfirm={() => run(() => deletePolicy(p.id), () => router.push("/policies"))} />
      )}
    </>
  );
}
