"use client";

import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";
import { Icon } from "@/components/Icon";
import { PhoneInput } from "@/components/ui/PhoneInput";
import { fmtDY } from "@/lib/format";
import { useT } from "@/lib/i18n/client";
import { removeCandidateDocument, saveCandidateForm, submitOnboarding, type CandidateForm } from "../actions";

export interface CandidateDoc { id: string; kind: string; label: string; fileName: string; status: "PENDING" | "VERIFIED" | "REJECTED"; comment: string | null }
export interface CandidateView {
  candidateName: string; personalEmail: string; businessName: string; designation: string | null; joiningDate: string | null; india: boolean;
  status: string; editable: boolean; hrComment: string | null; linkExpiresAt: string; form: CandidateForm;
  documents: CandidateDoc[]; checklist: { kind: string; label: string; needed: number; uploaded: number }[]; kinds: Record<string, string>;
}

const DOC_CHIP = { PENDING: { cls: "neutral", text: "Uploaded" }, VERIFIED: { cls: "ok", text: "Verified" }, REJECTED: { cls: "bad", text: "Please replace" } } as const;

/** The candidate's guided form: details, documents, submit. */
export function CandidateClient({ token, v }: { token: string; v: CandidateView }) {
  const t = useT();
  const router = useRouter();
  const [f, setF] = useState<CandidateForm>(v.form);
  const [msg, setMsg] = useState<{ ok: boolean; text: string } | null>(null);
  const [busy, start] = useTransition();
  const [uploading, setUploading] = useState(false);
  const [kind, setKind] = useState(v.checklist.find((c) => c.uploaded < c.needed)?.kind ?? "OTHER");
  const ro = !v.editable;
  const set = (k: keyof CandidateForm) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>) => { setF({ ...f, [k]: e.target.value }); setMsg(null); };
  const field = (k: keyof CandidateForm, label: string, opts: { type?: string; ph?: string; req?: boolean; max?: number } = {}) => (
    <label className="field">{t(label)}{opts.req ? " *" : ""}
      <input type={opts.type ?? "text"} value={f[k]} onChange={set(k)} placeholder={opts.ph ? t(opts.ph) : undefined} maxLength={opts.max ?? 120} disabled={ro} />
    </label>
  );
  const save = (then?: () => Promise<void>) => start(async () => {
    const r = await saveCandidateForm(token, f);
    if (!r.ok) { setMsg({ ok: false, text: r.error }); return; }
    if (then) await then(); else setMsg({ ok: true, text: t("Saved. You can come back to this link until it expires.") });
  });
  const submit = () => save(async () => {
    const r = await submitOnboarding(token);
    setMsg({ ok: r.ok, text: r.ok ? r.msg! : r.error });
    if (r.ok) router.refresh();
  });
  const upload = async (file: File) => {
    setUploading(true);
    setMsg(null);
    const fd = new FormData();
    fd.set("kind", kind);
    fd.set("file", file);
    try {
      const res = await fetch(`/api/onboarding/${encodeURIComponent(token)}/documents`, { method: "POST", body: fd });
      const j = await res.json().catch(() => ({}));
      setMsg({ ok: res.ok, text: res.ok ? j.msg ?? t("Uploaded") : j.detail ?? t("Upload failed") });
      router.refresh();
    } finally { setUploading(false); }
  };
  const remove = (id: string) => start(async () => {
    const r = await removeCandidateDocument(token, id);
    if (!r.ok) setMsg({ ok: false, text: r.error });
    router.refresh();
  });

  return (
    <div className="col" style={{ gap: 18 }}>
      <header>
        <span className="eyebrow">{v.businessName}</span>
        <h1 className="display" style={{ fontSize: 34, margin: "4px 0 6px" }}>{t("Welcome, {name}", { name: f.firstName || v.candidateName.split(" ")[0] })}</h1>
        <p className="muted" style={{ margin: 0 }}>
          {v.designation
            ? (v.joiningDate ? t("You're joining as {role} on {date}.", { role: v.designation, date: fmtDY(v.joiningDate) }) : t("You're joining as {role}.", { role: v.designation }))
            : (v.joiningDate ? t("You're joining the team on {date}.", { date: fmtDY(v.joiningDate) }) : t("You're joining the team."))}
          {" "}{t("Fill in your details and upload your documents; HR will review them. Your link works until {date}.", { date: fmtDY(v.linkExpiresAt.slice(0, 10)) })}
        </p>
      </header>

      {v.hrComment && <div className="card" role="alert" style={{ padding: 16, background: "var(--warnbg)", borderColor: "transparent" }}><b>{t("HR asked for changes:")}</b> {v.hrComment}</div>}
      {ro && <div className="card" style={{ padding: 16, background: "var(--okbg)", borderColor: "transparent" }}><b>{t("Submitted.")}</b> {t("HR is reviewing your details and will contact you if anything else is needed.")}</div>}

      <section className="card" style={{ padding: 20 }}>
        <div className="card-h"><h3>{t("1 · About you")}</h3></div>
        <div className="col" style={{ gap: 12 }}>
          <div className="grid3">{field("firstName", "First name", { req: true, max: 60 })}{field("middleName", "Middle name", { max: 60 })}{field("lastName", "Last name", { req: true, max: 60 })}</div>
          <div className="grid3">
            {field("fatherName", "Father's name", { req: true })}
            {field("dateOfBirth", "Date of birth", { type: "date", req: true })}
            <label className="field">{t("Gender")}
              <select value={f.gender} onChange={set("gender")} disabled={ro}><option value="">{t("Prefer not to say")}</option><option value="Female">{t("Female")}</option><option value="Male">{t("Male")}</option><option value="Other">{t("Other")}</option></select>
            </label>
          </div>
          <div className="field">{t("Mobile number *")}
            <PhoneInput label="Mobile number" code={f.phoneCountry || "+91"} number={f.phone} disabled={ro} onChange={(c, n) => { setF({ ...f, phoneCountry: c, phone: n }); setMsg(null); }} />
          </div>
          <label className="field">{t("Current address *")}<textarea value={f.currentAddress} onChange={set("currentAddress")} rows={2} maxLength={500} disabled={ro} /></label>
          <label className="field">{t("Permanent address")} <span className="hint">{t("if different")}</span><textarea value={f.permanentAddress} onChange={set("permanentAddress")} rows={2} maxLength={500} disabled={ro} /></label>
        </div>
      </section>

      <section className="card" style={{ padding: 20 }}>
        <div className="card-h"><h3>{t("2 · Identity & bank")}</h3><small className="muted">{t("Stored encrypted")}</small></div>
        <div className="col" style={{ gap: 12 }}>
          {v.india && <div className="grid3">{field("pan", "PAN (optional)", { ph: "ABCDE1234F", max: 10 })}{field("aadhaar", "Aadhaar (optional)", { ph: "12 digits", max: 14 })}{field("uan", "UAN", { ph: "if you have PF already", max: 12 })}</div>}
          <div className="grid3">{field("bankName", "Bank", { req: true, max: 80 })}{field("bankAccount", "Account number", { req: true, max: 18 })}{v.india ? field("ifsc", "IFSC", { req: true, ph: "HDFC0001234", max: 11 }) : <span />}</div>
        </div>
      </section>

      <section className="card" style={{ padding: 20 }}>
        <div className="card-h"><h3>{t("3 · Previous job & emergency contact")}</h3></div>
        <div className="col" style={{ gap: 12 }}>
          <div className="grid2">{field("previousEmployer", "Previous employer")}{field("previousDesignation", "Your designation there")}</div>
          <div className="grid2">{field("emergencyName", "Emergency contact", { req: true })}{field("emergencyRelation", "Relationship", { max: 40 })}</div>
          <div className="field">{t("Their phone *")}
            <PhoneInput label="Emergency contact phone" code={f.emergencyPhoneCountry || "+91"} number={f.emergencyPhone} disabled={ro}
              onChange={(c, n) => { setF({ ...f, emergencyPhoneCountry: c, emergencyPhone: n }); setMsg(null); }} />
          </div>
        </div>
      </section>

      <section className="card" style={{ padding: 20 }}>
        <div className="card-h"><h3>{t("4 · Documents")}</h3><small className="muted">{t("PDF, JPG or PNG · up to 10 MB each")}</small></div>
        <ul style={{ listStyle: "none", padding: 0, margin: "0 0 12px", display: "flex", flexDirection: "column", gap: 6 }}>
          {v.checklist.map((c) => (
            <li key={c.kind} style={{ display: "flex", gap: 8, alignItems: "center" }}>
              <span className={`chip ${c.uploaded >= c.needed ? "ok" : "warn"}`}>{c.uploaded}/{c.needed}</span>{c.label}
            </li>
          ))}
        </ul>
        {v.documents.length > 0 && (
          <div className="table-wrap" style={{ marginBottom: 12 }}>
            <table className="t">
              <tbody>
                {v.documents.map((d) => (
                  <tr key={d.id}>
                    <td><b>{d.label}</b><br /><a className="linkish" href={`/api/onboarding/${encodeURIComponent(token)}/documents/${d.id}/file`} target="_blank" rel="noopener">{d.fileName}</a></td>
                    <td><span className={`chip ${DOC_CHIP[d.status].cls}`}>{t(DOC_CHIP[d.status].text)}</span>{d.comment && <><br /><small className="muted">{d.comment}</small></>}</td>
                    <td className="r">{!ro && d.status !== "VERIFIED" && <button className="btn btn-sm btn-ghost" disabled={busy} onClick={() => remove(d.id)} aria-label={t("Remove {name}", { name: d.fileName })}><Icon n="trash" s={14} /></button>}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {!ro && (
          <div style={{ display: "flex", gap: 8, alignItems: "flex-end", flexWrap: "wrap" }}>
            <label className="field" style={{ minWidth: 260, flex: 1 }}>{t("Document type")}
              <select value={kind} onChange={(e) => setKind(e.target.value)}>{Object.entries(v.kinds).map(([k, l]) => <option key={k} value={k}>{l}</option>)}</select>
            </label>
            <label className="btn" style={{ cursor: uploading ? "wait" : "pointer" }}>
              <Icon n="upload" s={14} />{uploading ? t("Uploading…") : t("Choose file")}
              <input type="file" accept="application/pdf,image/png,image/jpeg" hidden disabled={uploading} onChange={(e) => { const file = e.target.files?.[0]; if (file) void upload(file); e.target.value = ""; }} />
            </label>
          </div>
        )}
      </section>

      {msg && <div className={msg.ok ? "card" : "err"} role={msg.ok ? "status" : "alert"} style={msg.ok ? { padding: 12, background: "var(--okbg)", borderColor: "transparent" } : undefined}>{msg.text}</div>}
      {!ro && (
        <div style={{ display: "flex", gap: 8, justifyContent: "flex-end", flexWrap: "wrap" }}>
          <button className="btn" disabled={busy} onClick={() => save()}>{busy ? t("Saving…") : t("Save for later")}</button>
          <button className="btn btn-gold" disabled={busy} onClick={submit}><Icon n="check" s={15} />{t("Submit to HR")}</button>
        </div>
      )}
    </div>
  );
}
