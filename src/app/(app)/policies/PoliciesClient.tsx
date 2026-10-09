"use client";

import { useRouter } from "next/navigation";
import { useRef, useState, useTransition } from "react";
import { Icon } from "@/components/Icon";
import { useToast } from "@/components/shell/Toasts";
import { ConfirmDialog, Modal } from "@/components/ui/Modal";
import type { PolicyDetail } from "@/lib/api-types";
import { createPolicy, deletePolicy, remindPolicy, removePolicyFile, setPolicyStatus, updatePolicy } from "./actions";

export function NewPolicyButton() {
  const toast = useToast();
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
      <button className="btn btn-gold btn-lg" onClick={() => setOpen(true)}><Icon n="plus" s={16} />New policy</button>
      {open && (
        <Modal title="New policy" eyebrow="Starts as a draft" size="sm" onClose={close}
          footer={<><button className="btn btn-ghost" onClick={close}>Cancel</button><button className="btn btn-gold" disabled={busy} onClick={save}>{busy ? "Creating…" : "Create draft"}</button></>}>
          <div className="col" style={{ gap: 14 }}>
            <label className="field">Title *<input value={title} onChange={(e) => { setTitle(e.target.value); setErr(""); }} maxLength={160} placeholder="e.g. Code of conduct" autoFocus
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
      if (!res.ok) toast(j.detail ?? "Upload failed", "bad"); else toast(j.msg ?? "Attached");
      router.refresh();
    } finally { setUploading(false); }
  };
  return (
    <section className="card enter" style={{ ["--i" as string]: 2, padding: 18 }}>
      <div className="card-h"><h3>Draft</h3>{dirty && <span className="chip warn nodot">Not saved</span>}</div>
      <div className="col" style={{ gap: 14 }}>
        <label className="field">Title<input value={f.title} disabled={archived} onChange={(e) => { setF({ ...f, title: e.target.value }); setErr(""); }} maxLength={160} /></label>
        <label className="field">Policy text <span className="hint">plain text; a blank line starts a new paragraph</span>
          <textarea value={f.body} disabled={archived} onChange={(e) => { setF({ ...f, body: e.target.value }); setErr(""); }} rows={14} style={{ fontFamily: "inherit", lineHeight: 1.55 }} />
        </label>
        <div style={{ display: "flex", gap: 16, alignItems: "center", flexWrap: "wrap" }}>
          <label style={{ display: "flex", gap: 8, alignItems: "center" }}>
            <input type="checkbox" checked={f.requiresAck} disabled={archived} onChange={(e) => setF({ ...f, requiresAck: e.target.checked })} />
            Everyone must accept it before using JurisOne
          </label>
          <span style={{ flex: 1 }} />
          {p.fileName ? (
            <span style={{ display: "flex", gap: 6, alignItems: "center" }}>
              <a className="linkish" href={`/api/policies/${p.id}/file`} target="_blank" rel="noopener"><Icon n="doc" s={14} /> {p.fileName}</a>
              {!archived && <button className="btn btn-sm btn-ghost" aria-label="Remove the PDF" onClick={() => start(async () => { const r = await removePolicyFile(p.id); toast(r.ok ? r.msg! : r.error, r.ok ? undefined : "bad"); })}><Icon n="x" s={13} /></button>}
            </span>
          ) : <span className="muted">No PDF attached</span>}
          {!archived && (
            <>
              <button className="btn btn-sm" disabled={uploading} onClick={() => fileIn.current?.click()}><Icon n="upload" s={14} />{uploading ? "Uploading…" : p.fileName ? "Replace PDF" : "Attach PDF"}</button>
              <input ref={fileIn} type="file" accept="application/pdf" hidden onChange={(e) => { const file = e.target.files?.[0]; if (file) void upload(file); e.target.value = ""; }} />
            </>
          )}
        </div>
        {err && <div className="err" role="alert">{err}</div>}
        {!archived && <div><button className="btn btn-gold" disabled={busy || !dirty} onClick={save}>{busy ? "Saving…" : "Save draft"}</button></div>}
      </div>
    </section>
  );
}

/** Publish / archive / delete, reminders and the evidence export. */
export function PolicyActions({ p }: { p: PolicyDetail }) {
  const toast = useToast();
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
      {p.versions.length > 0 && <a className="btn" href={`/api/policies/${p.id}/acknowledgements/export`} download><Icon n="download" s={14} />Evidence (CSV)</a>}
      {p.versions.length > 0 && <a className="btn" href={`/api/policies/${p.id}/acknowledgements/export?format=pdf`} download><Icon n="doc" s={14} />Evidence (PDF)</a>}
      {p.status === "PUBLISHED" && p.requiresAck && <button className="btn" disabled={busy} onClick={() => run(() => remindPolicy(p.id))}><Icon n="bell" s={14} />Remind</button>}
      {p.status === "PUBLISHED" && <button className="btn btn-ghost" onClick={() => setAsk("archive")}>Archive</button>}
      {p.version === 0 && <button className="btn btn-ghost" onClick={() => setAsk("delete")} aria-label="Delete draft"><Icon n="trash" s={14} /></button>}
      {canPublish && <button className="btn btn-gold" onClick={() => setAsk("publish")}><Icon n="upload" s={14} />{p.version === 0 ? "Publish" : p.hasChanges ? `Publish version ${p.version + 1}` : "Publish again"}</button>}
      {ask === "publish" && (
        <ConfirmDialog title={p.hasChanges ? `Publish version ${p.version + 1}` : "Publish again"} confirm="Publish" busy={busy} onClose={() => setAsk(null)}
          body={<>Publish the saved draft of <b>{p.title}</b>? It can&apos;t be changed afterwards (a change makes a new version).
            {p.requiresAck ? " Everyone except owners will have to read and accept it before they can use JurisOne." : " It is for information only — nobody has to accept it."}
            {" "}Unsaved edits in the editor are not included.</>}
          onConfirm={() => run(() => setPolicyStatus(p.id, "PUBLISHED"))} />
      )}
      {ask === "archive" && (
        <ConfirmDialog title="Archive policy" confirm="Archive" busy={busy} onClose={() => setAsk(null)}
          body={<>Archive <b>{p.title}</b>? Employees will no longer see it or be asked to accept it. Its versions and acceptances are kept.</>}
          onConfirm={() => run(() => setPolicyStatus(p.id, "ARCHIVED"))} />
      )}
      {ask === "delete" && (
        <ConfirmDialog title="Delete draft" danger confirm="Delete" busy={busy} onClose={() => setAsk(null)}
          body={<>Delete the draft <b>{p.title}</b>? It has never been published.</>}
          onConfirm={() => run(() => deletePolicy(p.id), () => router.push("/policies"))} />
      )}
    </>
  );
}
