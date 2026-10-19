"use client";

import { useEffect, useState, useTransition } from "react";
import { Icon } from "@/components/Icon";
import { useToast } from "@/components/shell/Toasts";
import { Modal } from "@/components/ui/Modal";
import { PhoneInput } from "@/components/ui/PhoneInput";
import { stepErrors, type EmployeeInput, type StepErrors } from "@/lib/employee-schema";
import { LOCATIONS } from "@/lib/people-rules";
import { SHIFT_OPTIONS } from "@/lib/shifts";
import { useT } from "@/lib/i18n/client";
import { getFormOptions, saveEmployee, type FormOptions } from "./actions";

const STEPS = ["Personal", "Identity & address", "Job & access"] as const;

/** Add / edit employee — 3 steps (REQUIREMENTS §5.2, prototype tpl-add). */
export function EmployeeForm({ editId, onClose }: { editId?: string; onClose: () => void }) {
  const toast = useToast();
  const t = useT();
  const [opt, setOpt] = useState<FormOptions | null>(null);
  const [loadErr, setLoadErr] = useState("");
  const [f, setF] = useState<EmployeeInput | null>(null);
  const [step, setStep] = useState<1 | 2 | 3>(1);
  const [tried, setTried] = useState(false);
  const [serverErr, setServerErr] = useState("");
  const [busy, start] = useTransition();

  useEffect(() => {
    let alive = true;
    getFormOptions(editId).then((r) => {
      if (!alive) return;
      if (r.ok) { setOpt(r.data!); setF(r.data!.defaults); }
      else setLoadErr(r.error);
    });
    return () => { alive = false; };
  }, [editId]);

  const editing = !!opt?.editing;
  const errs: StepErrors = f ? stepErrors(step, f, editing, opt?.editing?.hadManager) : {};
  const india = !f?.location || f.location === "IN_IST";
  const set = <K extends keyof EmployeeInput>(k: K, v: EmployeeInput[K]) => {
    setServerErr("");
    setF((cur) => {
      if (!cur) return cur;
      const next = { ...cur, [k]: v };
      if (k === "location") next.shift = !v || v === "IN_IST" ? (cur.shift === "US_DAY" ? "IN_NIGHT" : cur.shift) : "US_DAY";
      if (k === "departmentId" && !editing && !opt?.lockedDepartmentId) {
        const head = opt?.departments.find((d) => d.id === v)?.headId;
        if (head && (!cur.managerId || opt?.departments.some((d) => d.headId === cur.managerId))) next.managerId = head;
      }
      return next;
    });
  };
  const err = (k: keyof EmployeeInput) => (tried && errs[k] ? <span className="err">{errs[k]}</span> : null);
  const onFile = (l4: string | null | undefined) => (l4 ? t("On file · ends {l4} — leave blank to keep", { l4 }) : undefined);

  const next = () => {
    setTried(true);
    if (!f || Object.keys(errs).length) return;
    setTried(false);
    if (step < 3) { setStep((step + 1) as 2 | 3); return; }
    start(async () => {
      const r = await saveEmployee(f, editId);
      if (r.ok) { toast(r.msg!); onClose(); }
      else setServerErr(r.error);
    });
  };

  const title = opt?.editing ? opt.editing.name : t("Add employee");
  const eyebrow = opt ? (opt.editing ? t("Edit · {code}", { code: opt.nextCode }) : t("New hire · {code}", { code: opt.nextCode })) : t("Loading…");

  return (
    <Modal title={title} eyebrow={eyebrow} onClose={onClose} maxWidth={760}
      footer={opt && f ? (
        <>
          <button className="btn btn-ghost" onClick={() => (step > 1 ? setStep((step - 1) as 1 | 2) : onClose())}>{step > 1 ? t("Back") : t("Cancel")}</button>
          <button className="btn btn-gold" disabled={busy} onClick={next}>{busy ? t("Saving…") : step < 3 ? t("Continue") : editing ? t("Save changes") : t("Create employee")}<Icon n="arrow" s={16} /></button>
        </>
      ) : undefined}>
      {loadErr && <div className="err" role="alert">{loadErr}</div>}
      {!opt && !loadErr && <div className="faint">{t("Loading…")}</div>}
      {opt && f && (
        <div className="col" style={{ gap: 20 }}>
          <div className="steps" aria-label={t("Step {step} of 3", { step })}>
            {STEPS.map((s, i) => (
              <span key={s} className={`st${step === i + 1 ? " on" : ""}${step > i + 1 ? " done" : ""}`} aria-current={step === i + 1 ? "step" : undefined}>
                <i>{step > i + 1 ? "✓" : i + 1}</i>{t(s)}
              </span>
            ))}
          </div>

          {step === 1 && (
            <div className="col" style={{ gap: 14, animation: "enter .4s both" }}>
              <div className="grid3">
                <label className="field">{t("First name *")}<input value={f.firstName} onChange={(e) => set("firstName", e.target.value)} placeholder={t("e.g. Meera")} maxLength={60} autoFocus />{err("firstName")}</label>
                <label className="field">{t("Middle name")}<input value={f.middleName} onChange={(e) => set("middleName", e.target.value)} maxLength={60} />{err("middleName")}</label>
                <label className="field">{t("Last name *")}<input value={f.lastName} onChange={(e) => set("lastName", e.target.value)} placeholder={t("e.g. Joshi")} maxLength={60} />{err("lastName")}</label>
              </div>
              <div className="grid2">
                <label className="field">{t("Father’s name *")}<input value={f.fatherName} onChange={(e) => set("fatherName", e.target.value)} placeholder={t("e.g. Prakash Joshi")} maxLength={120} />{err("fatherName")}</label>
                <label className="field">{t("Work email *")}<input type="email" value={f.email} onChange={(e) => set("email", e.target.value)} placeholder="meera.joshi@company.com" maxLength={200} />{err("email")}</label>
                <div className="field">{t("Phone")} {editing ? "" : "*"}
                  <PhoneInput code={f.phoneCountry || "+91"} number={f.phone} placeholder={onFile(opt.editing?.last4.phone)}
                    onChange={(c, n) => { set("phoneCountry", c); set("phone", n); }} />
                  {err("phone")}
                </div>
                <label className="field">{t("Joining office")} <span className="hint">{t("optional")}</span>
                  <input value={f.joiningOffice} onChange={(e) => set("joiningOffice", e.target.value)} placeholder={t("e.g. Noida office")} maxLength={120} />
                </label>
                <label className="field">{t("Work location")} <span className="hint">{t("optional · India if not set")}</span>
                  <select value={f.location} onChange={(e) => set("location", e.target.value)}>
                    <option value="">{t("Not set (India)")}</option>
                    {LOCATIONS.map((l) => <option key={l.v} value={l.v}>{t(l.l)}</option>)}
                  </select>
                  <span className="hint">{t("Decides payroll, holidays and the attendance time zone.")}</span>
                </label>
              </div>
            </div>
          )}

          {step === 2 && (
            <div className="col" style={{ gap: 14, animation: "enter .4s both" }}>
              {india ? (
                <div className="grid3">
                  <label className="field">PAN <span className="hint">{t("optional")}</span><input value={f.pan} onChange={(e) => set("pan", e.target.value.toUpperCase())} placeholder={onFile(opt.editing?.last4.pan) ?? "ABCDE1234F"} maxLength={10} style={{ textTransform: "uppercase" }} autoComplete="off" />{err("pan")}</label>
                  <label className="field">Aadhaar <span className="hint">{t("optional")}</span><input value={f.aadhaar} onChange={(e) => set("aadhaar", e.target.value)} placeholder={onFile(opt.editing?.last4.aadhaar) ?? "1234 5678 9012"} maxLength={14} inputMode="numeric" autoComplete="off" />{err("aadhaar")}</label>
                  <label className="field">UAN <span className="hint">{t("if they have PF already")}</span><input value={f.uan} onChange={(e) => set("uan", e.target.value)} placeholder={onFile(opt.editing?.last4.uan) ?? t("12-digit UAN")} maxLength={14} inputMode="numeric" autoComplete="off" />{err("uan")}</label>
                </div>
              ) : (
                <div className="note-box"><Icon n="globe" s={18} /><span>{t("PAN, Aadhaar and UAN apply to the India team only. US employees are paid outside Indian payroll.")}</span></div>
              )}
              <label className="field">{t("Address")}<textarea value={f.address} onChange={(e) => set("address", e.target.value)} placeholder={opt.editing?.hasAddress ? t("On file — leave blank to keep") : t("House, street, city, state, PIN / ZIP")} maxLength={500} /></label>
              <p className="faint" style={{ margin: 0, fontSize: 12.5 }}>{t("IDs, phone and address are encrypted. Only the last four digits are shown after saving.")}</p>
            </div>
          )}

          {step === 3 && (
            <div className="col" style={{ gap: 16, animation: "enter .4s both" }}>
              <div className="grid2">
                <label className="field">{t("Designation *")}<input value={f.designation} onChange={(e) => set("designation", e.target.value)} placeholder={t("e.g. Associate — Contract Review")} maxLength={120} />{err("designation")}</label>
                <label className="field">{t("Department *")}
                  <select value={f.departmentId} disabled={!!opt.lockedDepartmentId} onChange={(e) => set("departmentId", e.target.value)}>
                    <option value="" disabled>{t("Select a department")}</option>
                    {opt.departments.map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}
                  </select>
                  {opt.lockedDepartmentId && <span className="hint">{t("You can add people to your own department only")}</span>}
                  {err("departmentId")}
                </label>
                <label className="field">{t("Reporting manager")} {opt.editing && !opt.editing.hadManager ? "" : "*"}
                  <select value={f.managerId} onChange={(e) => set("managerId", e.target.value)}>
                    <option value="">{opt.editing && !opt.editing.hadManager ? t("No manager") : t("Select a manager")}</option>
                    {opt.managers.map((m) => <option key={m.id} value={m.id}>{m.name} — {m.title}</option>)}
                  </select>
                  {err("managerId")}
                </label>
                <label className="field">{t("Date of joining *")}<input type="date" value={f.doj} onChange={(e) => set("doj", e.target.value)} />{err("doj")}</label>
                <label className="field">{t("Shift")}
                  <select value={f.shift} disabled={!india} onChange={(e) => set("shift", e.target.value as EmployeeInput["shift"])}>
                    {(india ? SHIFT_OPTIONS.IN : SHIFT_OPTIONS.US).map((s) => <option key={s.v} value={s.v}>{t(s.l)}</option>)}
                  </select>
                </label>
              </div>
              <div>
                <div className="field" style={{ marginBottom: 10 }}>{t("Role")} <span className="hint">{t("decides what they can see after signing in")}</span></div>
                <div className="ltypes" role="radiogroup" aria-label={t("Role")}>
                  {opt.roles.map((r) => (
                    <button key={r.id} type="button" role="radio" aria-checked={f.roleId === r.id} className={`ltype${f.roleId === r.id ? " on" : ""}`} onClick={() => set("roleId", r.id)}>
                      <span className="tick"><Icon n="ok" s={12} /></span><b>{r.name}</b><small>{t("{n} permissions", { n: r.perms })}</small>
                    </button>
                  ))}
                </div>
                {err("roleId")}
              </div>
              {!editing && india && (
                <div style={{ display: "flex", gap: 14, alignItems: "center", padding: "14px 16px", borderRadius: 14, border: "1px dashed var(--line2)", background: "var(--accentsoft)" }}>
                  <Icon n="lock" s={20} />
                  <div><b>{t("Salary is set by Finance")}</b><div className="muted" style={{ fontSize: 13 }}>{t("After you save, Finance is notified to set up the salary structure. The new hire gets an email invite to set a password.")}</div></div>
                </div>
              )}
            </div>
          )}
          {serverErr && <div className="err" role="alert">{serverErr}</div>}
        </div>
      )}
    </Modal>
  );
}
