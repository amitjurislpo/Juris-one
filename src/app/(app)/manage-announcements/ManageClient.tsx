"use client";

import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";
import { Icon } from "@/components/Icon";
import { useToast } from "@/components/shell/Toasts";
import { ConfirmDialog, Modal } from "@/components/ui/Modal";
import type { Announcement } from "@/lib/api-types";
import { AUDIENCES } from "@/lib/assets";
import { fmtDY } from "@/lib/format";
import { useT } from "@/lib/i18n/client";
import { createAnnouncement, setAnnouncementStatus, updateAnnouncement, type AnnouncementInput } from "./actions";

const EMPTY: AnnouncementInput = { title: "", body: "", audience: "ALL", pinned: false, expiresOn: "" };

function Editor({ a, onClose }: { a?: Announcement; onClose: () => void }) {
  const t = useT();
  const toast = useToast();
  const router = useRouter();
  const [f, setF] = useState<AnnouncementInput>(a ? { title: a.title, body: a.body, audience: a.audience, pinned: a.pinned, expiresOn: a.expiresOn ?? "" } : EMPTY);
  const [err, setErr] = useState("");
  const [busy, start] = useTransition();
  const save = () => start(async () => {
    const r = a ? await updateAnnouncement(a.id, f) : await createAnnouncement(f);
    if (r.ok) { toast(r.msg ?? t("Saved")); onClose(); router.refresh(); } else setErr(r.error);
  });
  return (
    <Modal title={a ? t("Edit announcement") : t("New announcement")} eyebrow={a ? t("People are not notified again") : t("Published straight away")} onClose={onClose}
      footer={<><button className="btn btn-ghost" onClick={onClose}>{t("Cancel")}</button><button className="btn btn-gold" disabled={busy} onClick={save}>{busy ? t("Saving…") : a ? t("Save") : t("Publish")}</button></>}>
      <div className="col" style={{ gap: 14 }}>
        <label className="field">{t("Title *")}<input value={f.title} maxLength={160} autoFocus onChange={(e) => { setF({ ...f, title: e.target.value }); setErr(""); }} /></label>
        <label className="field">{t("Message *")} <span className="hint">{t("plain text; a blank line starts a new paragraph")}</span>
          <textarea rows={8} value={f.body} onChange={(e) => { setF({ ...f, body: e.target.value }); setErr(""); }} style={{ fontFamily: "inherit", lineHeight: 1.55 }} />
        </label>
        <div className="grid2">
          <label className="field">{t("For")}<select value={f.audience} onChange={(e) => setF({ ...f, audience: e.target.value })}>{Object.entries(AUDIENCES).map(([k, v]) => <option key={k} value={k}>{t(v)}</option>)}</select></label>
          <label className="field">{t("Show until")} <span className="hint">{t("optional")}</span><input type="date" value={f.expiresOn} onChange={(e) => { setF({ ...f, expiresOn: e.target.value }); setErr(""); }} /></label>
        </div>
        <label style={{ display: "flex", gap: 8, alignItems: "center" }}>
          <input type="checkbox" checked={f.pinned} onChange={(e) => setF({ ...f, pinned: e.target.checked })} />
          {t("Pin to the top")}
        </label>
        {err && <div className="err" role="alert">{err}</div>}
      </div>
    </Modal>
  );
}

export function NewAnnouncementButton() {
  const t = useT();
  const [open, setOpen] = useState(false);
  return (
    <>
      <button className="btn btn-gold btn-lg" onClick={() => setOpen(true)}><Icon n="plus" s={16} />{t("New announcement")}</button>
      {open && <Editor onClose={() => setOpen(false)} />}
    </>
  );
}

// English labels; translated at render.
function state(a: Announcement): { cls: string; text: string } {
  if (a.status === "UNPUBLISHED") return { cls: "neutral", text: "Unpublished" };
  return a.active ? { cls: "ok", text: "Showing" } : { cls: "warn", text: "Expired" };
}

export function ManageTable({ rows }: { rows: Announcement[] }) {
  const t = useT();
  const toast = useToast();
  const router = useRouter();
  const [edit, setEdit] = useState<Announcement | null>(null);
  const [ask, setAsk] = useState<Announcement | null>(null);
  const [busy, start] = useTransition();
  const toggle = (a: Announcement, status: "PUBLISHED" | "UNPUBLISHED") => start(async () => {
    const r = await setAnnouncementStatus(a.id, status);
    toast(r.ok ? r.msg ?? t("Done") : r.error, r.ok ? undefined : "bad");
    setAsk(null);
    if (r.ok) router.refresh();
  });
  return (
    <>
      <section className="card enter" style={{ ["--i" as string]: 1 }}>
        <div className="table-wrap">
          <table className="t">
            <thead><tr><th>{t("Announcement")}</th><th>{t("Status")}</th><th>{t("For")}</th><th className="hide-sm">{t("Posted")}</th><th className="hide-sm">{t("Until")}</th><th></th></tr></thead>
            <tbody>
              {rows.map((a) => (
                <tr key={a.id}>
                  <td><b>{a.title}</b>{a.pinned && <span className="chip gold nodot" style={{ marginLeft: 8 }}>{t("Pinned")}</span>}<br /><small className="muted">{a.body.length > 80 ? `${a.body.slice(0, 80)}…` : a.body}</small></td>
                  <td><span className={`chip ${state(a).cls}`}>{t(state(a).text)}</span></td>
                  <td>{t(AUDIENCES[a.audience])}</td>
                  <td className="muted hide-sm">{fmtDY(a.createdAt.slice(0, 10))}</td>
                  <td className="muted hide-sm">{a.expiresOn ? fmtDY(a.expiresOn) : "—"}</td>
                  <td style={{ whiteSpace: "nowrap", textAlign: "right" }}>
                    <button className="btn btn-sm btn-ghost" aria-label={t("Edit {title}", { title: a.title })} onClick={() => setEdit(a)}><Icon n="edit" s={14} /></button>
                    {a.status === "PUBLISHED"
                      ? <button className="btn btn-sm" onClick={() => setAsk(a)}>{t("Unpublish")}</button>
                      : <button className="btn btn-sm" disabled={busy} onClick={() => toggle(a, "PUBLISHED")}>{t("Publish again")}</button>}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {!rows.length && <div className="empty"><b className="display">{t("Nothing posted yet")}</b><span className="muted">{t("Post your first announcement — a holiday notice, a new joiner, an office update.")}</span></div>}
      </section>
      {edit && <Editor a={edit} onClose={() => setEdit(null)} />}
      {ask && (
        <ConfirmDialog title={t("Unpublish announcement")} confirm={t("Unpublish")} busy={busy} onClose={() => setAsk(null)}
          body={<>{t("Hide")} <b>{ask.title}</b> {t("from everyone? You can publish it again later.")}</>} onConfirm={() => toggle(ask, "UNPUBLISHED")} />
      )}
    </>
  );
}
