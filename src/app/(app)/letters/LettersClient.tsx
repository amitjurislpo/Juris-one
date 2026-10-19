"use client";

import { useRouter } from "next/navigation";
import { useEffect, useRef, useState, useTransition } from "react";
import { Icon } from "@/components/Icon";
import { useToast } from "@/components/shell/Toasts";
import { Modal } from "@/components/ui/Modal";
import type { LetterField, LetterTemplateRow, LetterType } from "@/lib/api-types";
import { useT } from "@/lib/i18n/client";
import { createTemplate, issueLetter, previewLetter, templateDetail, updateTemplate } from "./actions";

export const TYPE_LABEL: Record<LetterType, string> = {
  OFFER: "Offer letter", APPOINTMENT: "Appointment letter", EXPERIENCE: "Experience letter", RELIEVING: "Relieving letter", EMPLOYMENT_VERIFICATION: "Employment verification",
};

export interface EmployeeChoice { id: string; code: string; name: string; designation: string; status: string; exitDate: string | null }

/** Pick a template and an employee, preview the text, issue it. */
export function IssueLetter({ templates, employees, canSalary }: { templates: LetterTemplateRow[]; employees: EmployeeChoice[]; canSalary: boolean }) {
  const t = useT();
  const toast = useToast();
  const router = useRouter();
  const [f, setF] = useState({ templateId: "", employeeId: "", lastWorkingDate: "" });
  const [preview, setPreview] = useState<{ subject: string; text: string } | null>(null);
  const [err, setErr] = useState("");
  const [busy, start] = useTransition();
  const usable = templates.filter((t) => t.active);
  const change = (patch: Partial<typeof f>) => { setF({ ...f, ...patch }); setPreview(null); setErr(""); };
  const ready = f.templateId && f.employeeId;

  const show = () => start(async () => {
    const r = await previewLetter(f);
    if (r.ok) setPreview(r.data!); else { setErr(r.error); setPreview(null); }
  });
  const issue = () => start(async () => {
    const r = await issueLetter(f);
    if (r.ok) { toast(r.msg!); setPreview(null); setF({ ...f, lastWorkingDate: "" }); router.refresh(); } else setErr(r.error);
  });

  return (
    <section className="card enter" style={{ ["--i" as string]: 1, padding: 18 }}>
      <div className="card-h"><h3>{t("Issue a letter")}</h3></div>
      <div style={{ display: "flex", gap: 12, flexWrap: "wrap", alignItems: "flex-end" }}>
        <label className="field" style={{ minWidth: 240 }}>{t("Template")}
          <select value={f.templateId} onChange={(e) => change({ templateId: e.target.value })}>
            <option value="">{t("Choose…")}</option>
            {usable.map((tp) => <option key={tp.id} value={tp.id} disabled={tp.needsSalary && !canSalary}>{tp.name}{tp.needsSalary ? ` ${canSalary ? t("(states salary)") : t("(needs salary access)")}` : ""}</option>)}
          </select>
        </label>
        <label className="field" style={{ minWidth: 240 }}>{t("Employee")}
          <select value={f.employeeId} onChange={(e) => change({ employeeId: e.target.value })}>
            <option value="">{t("Choose…")}</option>
            {employees.map((e) => <option key={e.id} value={e.id}>{e.name} · {e.code}{e.status === "EXITED" ? ` ${t("(exited)")}` : ""}</option>)}
          </select>
        </label>
        <label className="field">{t("Last working day")} <span className="hint">{t("only if the letter uses it")}</span>
          <input type="date" value={f.lastWorkingDate} onChange={(e) => change({ lastWorkingDate: e.target.value })} />
        </label>
        <button className="btn" disabled={!ready || busy} onClick={show}><Icon n="eye" s={14} />{t("Preview")}</button>
        <button className="btn btn-gold" disabled={!ready || busy} onClick={issue}><Icon n="upload" s={14} />{t("Issue letter")}</button>
      </div>
      {err && <div className="err" role="alert" style={{ marginTop: 12 }}>{err}</div>}
      {preview && (
        <div style={{ marginTop: 16, borderTop: "1px solid var(--line)", paddingTop: 14 }}>
          <span className="eyebrow">{t("Preview, nothing is saved yet")}</span>
          <h3 style={{ margin: "6px 0 10px" }}>{preview.subject}</h3>
          <div style={{ whiteSpace: "pre-wrap", lineHeight: 1.55 }}>{preview.text}</div>
        </div>
      )}
    </section>
  );
}

/** Create or edit a template; merge fields are inserted at the cursor. */
export function TemplateEditor({ id, fields, onClose }: { id: string | null; fields: LetterField[]; onClose: () => void }) {
  const t = useT();
  const toast = useToast();
  const router = useRouter();
  const [f, setF] = useState<{ type: LetterType; name: string; subject: string; body: string; active: boolean }>({ type: "EXPERIENCE", name: "", subject: "", body: "", active: true });
  const [err, setErr] = useState("");
  const [busy, start] = useTransition();
  const area = useRef<HTMLTextAreaElement>(null);

  useEffect(() => {
    if (id === null) return;
    let alive = true;
    void templateDetail(id).then((r) => {
      if (!alive) return;
      if (r.ok) setF({ type: r.data!.type, name: r.data!.name, subject: r.data!.subject, body: r.data!.body, active: r.data!.active });
      else setErr(r.error);
    });
    return () => { alive = false; };
  }, [id]);

  const insert = (name: string) => {
    const el = area.current;
    const tag = `{{${name}}}`;
    if (!el) return setF({ ...f, body: f.body + tag });
    const a = el.selectionStart ?? f.body.length, b = el.selectionEnd ?? a;
    setF({ ...f, body: f.body.slice(0, a) + tag + f.body.slice(b) });
    requestAnimationFrame(() => { el.focus(); el.setSelectionRange(a + tag.length, a + tag.length); });
  };
  const save = () => start(async () => {
    const r = id ? await updateTemplate(id, { name: f.name, subject: f.subject, body: f.body, active: f.active }) : await createTemplate(f);
    if (r.ok) { toast(r.msg!); router.refresh(); onClose(); } else setErr(r.error);
  });

  return (
    <Modal title={id ? t("Edit template") : t("New template")} eyebrow={t("HR letters")} maxWidth={820} onClose={onClose}
      footer={<><button className="btn btn-ghost" onClick={onClose}>{t("Cancel")}</button><button className="btn btn-gold" disabled={busy} onClick={save}>{busy ? t("Saving…") : t("Save template")}</button></>}>
      <div className="col" style={{ gap: 14 }}>
        <div style={{ display: "flex", gap: 12, flexWrap: "wrap" }}>
          {!id && (
            <label className="field">{t("Type")}
              <select value={f.type} onChange={(e) => setF({ ...f, type: e.target.value as LetterType })}>
                {(Object.keys(TYPE_LABEL) as LetterType[]).map((k) => <option key={k} value={k}>{t(TYPE_LABEL[k])}</option>)}
              </select>
            </label>
          )}
          <label className="field" style={{ flex: 1, minWidth: 200 }}>{t("Name")}<input value={f.name} maxLength={100} onChange={(e) => { setF({ ...f, name: e.target.value }); setErr(""); }} /></label>
          <label className="field" style={{ flex: 2, minWidth: 240 }}>{t("Subject line")}<input value={f.subject} maxLength={200} onChange={(e) => { setF({ ...f, subject: e.target.value }); setErr(""); }} /></label>
        </div>
        <label className="field">{t("Letter text")} <span className="hint">{t("plain text; a blank line starts a new paragraph")}</span>
          <textarea ref={area} rows={14} value={f.body} onChange={(e) => { setF({ ...f, body: e.target.value }); setErr(""); }} style={{ fontFamily: "inherit", lineHeight: 1.55, height: "auto", padding: 12 }} />
        </label>
        <div>
          <span className="eyebrow">{t("Merge fields, click to insert")}</span>
          <div style={{ display: "flex", gap: 6, flexWrap: "wrap", marginTop: 6 }}>
            {fields.map((x) => <button key={x.name} type="button" className={`chip ${x.salary ? "warn" : "neutral"} nodot`} title={x.label} style={{ cursor: "pointer" }} onClick={() => insert(x.name)}>{`{{${x.name}}}`}</button>)}
          </div>
          <p className="faint" style={{ fontSize: 12, marginTop: 8 }}>{t("Fields in amber state a salary. A template that uses one can only be issued by someone who manages salary structures, never by HR.")}</p>
        </div>
        <label style={{ display: "flex", gap: 8, alignItems: "center" }}>
          <input type="checkbox" checked={f.active} onChange={(e) => setF({ ...f, active: e.target.checked })} />{t("Available for issuing")}
        </label>
        {err && <div className="err" role="alert">{err}</div>}
      </div>
    </Modal>
  );
}

export function TemplatesTable({ templates, fields }: { templates: LetterTemplateRow[]; fields: LetterField[] }) {
  const t = useT();
  const [editing, setEditing] = useState<string | null | undefined>(undefined); // undefined = closed, null = new
  return (
    <section className="card enter" style={{ ["--i" as string]: 2 }}>
      <div className="card-h" style={{ padding: "16px 18px 0" }}>
        <h3>{t("Templates")}</h3>
        <button className="btn btn-sm" onClick={() => setEditing(null)}><Icon n="plus" s={14} />{t("New template")}</button>
      </div>
      <div className="table-wrap">
        <table className="t">
          <thead><tr><th>{t("Template")}</th><th className="hide-sm">{t("Type")}</th><th>{t("Status")}</th><th className="hide-sm">{t("Version")}</th><th></th></tr></thead>
          <tbody>
            {templates.map((tp) => (
              <tr key={tp.id}>
                <td><b>{tp.name}</b><br /><small className="muted">{tp.subject}</small></td>
                <td className="muted hide-sm">{t(TYPE_LABEL[tp.type])}</td>
                <td>
                  {tp.active ? <span className="chip ok">{t("On")}</span> : <span className="chip neutral">{t("Off")}</span>}
                  {tp.needsSalary && <span className="chip warn nodot" style={{ marginLeft: 6 }}>{t("States salary")}</span>}
                </td>
                <td className="num hide-sm">v{tp.version}</td>
                <td className="r"><button className="btn btn-sm" onClick={() => setEditing(tp.id)}>{t("Edit")}</button></td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {editing !== undefined && <TemplateEditor key={editing ?? "new"} id={editing} fields={fields} onClose={() => setEditing(undefined)} />}
    </section>
  );
}
