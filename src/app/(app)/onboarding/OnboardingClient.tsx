"use client";

import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";
import { Icon } from "@/components/Icon";
import { useToast } from "@/components/shell/Toasts";
import { ConfirmDialog, Modal } from "@/components/ui/Modal";
import { getFormOptions, type FormOptions } from "../employees/actions";
import {
  addOnboardedEmployee, decideDocument, getOnboardingDefaults, resendOnboarding, saveOnboardingDefaults, setOnboardingStatus, startOnboarding, updateOnboarding,
  type PlanInput,
} from "./actions";
import { DEFAULT_REQUIRED, DOC_KINDS } from "./status";

const LOCATIONS = [["IN_IST", "India · IST"], ["US_ET", "US East · ET"], ["US_CT", "US Central · CT"], ["US_PT", "US West · PT"]] as const;

/** Loads the people form options (departments, roles, managers) when a dialog opens. */
function useOptions() {
  const [opt, setOpt] = useState<FormOptions | null>(null);
  const [err, setErr] = useState("");
  const load = async () => {
    if (opt) return;
    const r = await getFormOptions();
    if (r.ok) setOpt(r.data!); else setErr(r.error);
  };
  return { opt, err, load };
}

function PlanFields({ p, set, opt }: { p: PlanInput; set: (p: PlanInput) => void; opt: FormOptions | null }) {
  const on = (k: keyof PlanInput) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) => set({ ...p, [k]: e.target.value });
  const req = p.requiredDocs ?? DEFAULT_REQUIRED;
  return (
    <>
      <div className="grid2">
        <label className="field">Candidate name *<input value={p.candidateName} onChange={on("candidateName")} maxLength={120} autoFocus /></label>
        <label className="field">Personal email * <span className="hint">the link goes here</span><input type="email" value={p.personalEmail} onChange={on("personalEmail")} maxLength={200} /></label>
      </div>
      <div className="grid3">
        <label className="field">Designation<input value={p.designation ?? ""} onChange={on("designation")} maxLength={120} placeholder="e.g. Associate" /></label>
        <label className="field">Department
          <select value={p.departmentId ?? ""} onChange={on("departmentId")}><option value="">—</option>{opt?.departments.map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}</select>
        </label>
        <label className="field">Joining date<input type="date" value={p.joiningDate ?? ""} onChange={on("joiningDate")} /></label>
      </div>
      <div className="grid3">
        <label className="field">Role
          <select value={p.roleId ?? ""} onChange={on("roleId")}><option value="">—</option>{opt?.roles.map((r) => <option key={r.id} value={r.id}>{r.name}</option>)}</select>
        </label>
        <label className="field">Reporting manager
          <select value={p.managerId ?? ""} onChange={on("managerId")}><option value="">—</option>{opt?.managers.map((m) => <option key={m.id} value={m.id}>{m.name} — {m.title}</option>)}</select>
        </label>
        <label className="field">Location
          <select value={p.location ?? "IN_IST"} onChange={on("location")}>{LOCATIONS.map(([v, l]) => <option key={v} value={v}>{l}</option>)}</select>
        </label>
      </div>
      <div className="field">Documents the candidate must upload
        <div style={{ display: "flex", flexWrap: "wrap", gap: "6px 16px" }}>
          {Object.entries(DOC_KINDS).map(([k, l]) => (
            <label key={k} style={{ display: "flex", alignItems: "center", gap: 6, fontWeight: 400 }}>
              <input type="checkbox" checked={req.includes(k)} onChange={(e) => set({ ...p, requiredDocs: e.target.checked ? [...req, k] : req.filter((x) => x !== k) })} />{l}
            </label>
          ))}
        </div>
      </div>
    </>
  );
}

const blank: PlanInput = { candidateName: "", personalEmail: "", designation: "", departmentId: "", roleId: "", managerId: "", joiningDate: "", location: "IN_IST", requiredDocs: DEFAULT_REQUIRED };

export function StartOnboardingButton() {
  const toast = useToast();
  const router = useRouter();
  const { opt, err: optErr, load } = useOptions();
  const [open, setOpen] = useState(false);
  const [p, setP] = useState<PlanInput>(blank);
  const [err, setErr] = useState("");
  const [busy, start] = useTransition();
  const close = () => { setOpen(false); setErr(""); setP(blank); };
  const save = () => start(async () => {
    const r = await startOnboarding(p);
    if (r.ok) { toast(r.msg!); close(); router.push(`/onboarding/${r.data!.id}`); } else setErr(r.error);
  });
  return (
    <>
      <button className="btn btn-gold btn-lg" onClick={() => {
        setOpen(true);
        void load();
        // start from the business's default document list
        void getOnboardingDefaults().then((r) => { if (r.ok) setP((cur) => ({ ...cur, requiredDocs: r.data!.requiredDocs })); });
      }}><Icon n="plus" s={16} />New onboarding</button>
      {open && (
        <Modal title="New onboarding" eyebrow="The candidate gets a link valid for 7 days" maxWidth={760} onClose={close}
          footer={<><button className="btn btn-ghost" onClick={close}>Cancel</button><button className="btn btn-gold" disabled={busy} onClick={save}>{busy ? "Sending…" : "Send link"}</button></>}>
          <div className="col" style={{ gap: 14 }}>
            <PlanFields p={p} set={(x) => { setP(x); setErr(""); }} opt={opt} />
            {(err || optErr) && <div className="err" role="alert">{err || optErr}</div>}
          </div>
        </Modal>
      )}
    </>
  );
}

export function EditPlanButton({ id, plan }: { id: string; plan: PlanInput }) {
  const toast = useToast();
  const { opt, load } = useOptions();
  const [open, setOpen] = useState(false);
  const [p, setP] = useState<PlanInput>(plan);
  const [err, setErr] = useState("");
  const [busy, start] = useTransition();
  const save = () => start(async () => {
    const r = await updateOnboarding(id, p);
    if (r.ok) { toast(r.msg!); setOpen(false); } else setErr(r.error);
  });
  return (
    <>
      <button className="btn" onClick={() => { setP(plan); setOpen(true); void load(); }}><Icon n="edit" s={14} />Edit</button>
      {open && (
        <Modal title="Onboarding details" eyebrow={plan.candidateName} maxWidth={760} onClose={() => setOpen(false)}
          footer={<><button className="btn btn-ghost" onClick={() => setOpen(false)}>Cancel</button><button className="btn btn-gold" disabled={busy} onClick={save}>{busy ? "Saving…" : "Save"}</button></>}>
          <div className="col" style={{ gap: 14 }}>
            <PlanFields p={p} set={(x) => { setP(x); setErr(""); }} opt={opt} />
            {err && <div className="err" role="alert">{err}</div>}
          </div>
        </Modal>
      )}
    </>
  );
}

/** Verify / reject one document (a rejection asks for a comment the candidate sees). */
export function DocumentDecision({ id, docId, name, status }: { id: string; docId: string; name: string; status: "PENDING" | "VERIFIED" | "REJECTED" }) {
  const toast = useToast();
  const [reject, setReject] = useState(false);
  const [comment, setComment] = useState("");
  const [busy, start] = useTransition();
  const run = (s: "VERIFIED" | "REJECTED", c?: string) => start(async () => {
    const r = await decideDocument(id, docId, s, c);
    toast(r.ok ? r.msg! : r.error, r.ok ? undefined : "bad");
    if (r.ok) { setReject(false); setComment(""); }
  });
  return (
    <span style={{ display: "inline-flex", gap: 4 }}>
      {status !== "VERIFIED" && <button className="btn btn-sm" disabled={busy} onClick={() => run("VERIFIED")}><Icon n="check" s={13} />Verify</button>}
      {status !== "REJECTED" && <button className="btn btn-sm btn-ghost" disabled={busy} onClick={() => setReject(true)}>Reject</button>}
      {reject && (
        <Modal title="Reject document" eyebrow={name} size="sm" onClose={() => setReject(false)}
          footer={<><button className="btn btn-ghost" onClick={() => setReject(false)}>Cancel</button><button className="btn btn-gold" disabled={busy || comment.trim().length < 3} onClick={() => run("REJECTED", comment)}>Reject</button></>}>
          <label className="field">What should the candidate fix? *<input value={comment} onChange={(e) => setComment(e.target.value)} maxLength={300} autoFocus placeholder="e.g. The scan is blurred" /></label>
        </Modal>
      )}
    </span>
  );
}

/** Resend the link, send back, verify, cancel. */
export function OnboardingActions({ id, status, name }: { id: string; status: string; name: string }) {
  const toast = useToast();
  const [ask, setAsk] = useState<null | "back" | "cancel">(null);
  const [comment, setComment] = useState("");
  const [busy, start] = useTransition();
  const run = (fn: () => Promise<{ ok: boolean; msg?: string; error?: string }>) => start(async () => {
    const r = await fn();
    toast(r.ok ? r.msg! : r.error!, r.ok ? undefined : "bad");
    if (r.ok) { setAsk(null); setComment(""); }
  });
  const open = ["INVITED", "IN_PROGRESS", "CHANGES_REQUESTED"].includes(status);
  return (
    <>
      {open && <button className="btn" disabled={busy} onClick={() => run(() => resendOnboarding(id))}><Icon n="upload" s={14} />Send a new link</button>}
      {(status === "SUBMITTED" || status === "VERIFIED") && <button className="btn" onClick={() => setAsk("back")}>Send back</button>}
      {status === "SUBMITTED" && <button className="btn btn-gold" disabled={busy} onClick={() => run(() => setOnboardingStatus(id, "VERIFIED"))}><Icon n="check" s={14} />Mark verified</button>}
      {status !== "ADDED" && status !== "CANCELLED" && <button className="btn btn-ghost" onClick={() => setAsk("cancel")}>Cancel onboarding</button>}
      {ask === "back" && (
        <Modal title="Send back to the candidate" eyebrow={name} size="sm" onClose={() => setAsk(null)}
          footer={<><button className="btn btn-ghost" onClick={() => setAsk(null)}>Cancel</button><button className="btn btn-gold" disabled={busy || comment.trim().length < 3} onClick={() => run(() => setOnboardingStatus(id, "CHANGES_REQUESTED", comment))}>Send back</button></>}>
          <label className="field">What should they change? * <span className="hint">emailed with a new link</span>
            <textarea value={comment} onChange={(e) => setComment(e.target.value)} rows={4} maxLength={1000} autoFocus />
          </label>
        </Modal>
      )}
      {ask === "cancel" && (
        <ConfirmDialog title="Cancel onboarding" danger confirm="Cancel onboarding" busy={busy} onClose={() => setAsk(null)}
          body={<>Cancel the onboarding of <b>{name}</b>? Their link stops working. Uploaded documents are kept with this record.</>}
          onConfirm={() => run(() => setOnboardingStatus(id, "CANCELLED"))} />
      )}
    </>
  );
}

/** Creates the employee from a verified onboarding (portal access off until HR turns it on). */
export function AddEmployeeButton({ id, name, plan }: { id: string; name: string; plan: PlanInput }) {
  const toast = useToast();
  const router = useRouter();
  const { opt, err: optErr, load } = useOptions();
  const [open, setOpen] = useState(false);
  const [f, setF] = useState({ workEmail: "", designation: plan.designation ?? "", departmentId: plan.departmentId ?? "", managerId: plan.managerId ?? "",
    roleId: plan.roleId ?? "", joiningDate: plan.joiningDate ?? "", location: plan.location ?? "", joiningOffice: "", shift: plan.location && plan.location !== "IN_IST" ? "US_DAY" : "IN_NIGHT" });
  const [err, setErr] = useState("");
  const [busy, start] = useTransition();
  const on = (k: keyof typeof f) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) => { setF({ ...f, [k]: e.target.value }); setErr(""); };
  const india = !f.location || f.location === "IN_IST";
  const save = () => start(async () => {
    const r = await addOnboardedEmployee(id, { ...f, shift: india ? f.shift : "US_DAY" });
    if (r.ok) { toast(r.msg!); setOpen(false); router.refresh(); } else setErr(r.error);
  });
  return (
    <>
      <button className="btn btn-gold" onClick={() => { setOpen(true); void load(); }}><Icon n="plus" s={14} />Add employee</button>
      {open && (
        <Modal title="Add employee" eyebrow={`${name} · from onboarding`} maxWidth={760} onClose={() => setOpen(false)}
          footer={<><button className="btn btn-ghost" onClick={() => setOpen(false)}>Cancel</button><button className="btn btn-gold" disabled={busy} onClick={save}>{busy ? "Adding…" : "Add employee"}</button></>}>
          <div className="col" style={{ gap: 14 }}>
            <p className="muted" style={{ margin: 0 }}>Name, identity, contact and bank details come from the candidate&apos;s form; verified documents go on their record. Portal access stays off until you turn it on — then they get the invite on their work email.</p>
            <div className="grid2">
              <label className="field">Work email * <span className="hint">they sign in with it</span><input type="email" value={f.workEmail} onChange={on("workEmail")} maxLength={200} autoFocus /></label>
              <label className="field">Designation *<input value={f.designation} onChange={on("designation")} maxLength={120} /></label>
            </div>
            <div className="grid3">
              <label className="field">Department *
                <select value={f.departmentId} onChange={on("departmentId")}><option value="">—</option>{opt?.departments.map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}</select>
              </label>
              <label className="field">Role *
                <select value={f.roleId} onChange={on("roleId")}><option value="">—</option>{opt?.roles.map((r) => <option key={r.id} value={r.id}>{r.name}</option>)}</select>
              </label>
              <label className="field">Reporting manager *
                <select value={f.managerId} onChange={on("managerId")}><option value="">—</option>{opt?.managers.map((m) => <option key={m.id} value={m.id}>{m.name} — {m.title}</option>)}</select>
              </label>
            </div>
            <div className="grid3">
              <label className="field">Joining date *<input type="date" value={f.joiningDate} onChange={on("joiningDate")} /></label>
              <label className="field">Work location <span className="hint">India if not set</span>
                <select value={f.location} onChange={on("location")}><option value="">Not set (India)</option>{LOCATIONS.map(([v, l]) => <option key={v} value={v}>{l}</option>)}</select>
              </label>
              {india ? (
                <label className="field">Shift
                  <select value={f.shift} onChange={on("shift")}><option value="IN_NIGHT">Night (US hours)</option><option value="IN_DAY">Day</option></select>
                </label>
              ) : <span />}
            </div>
            <label className="field">Joining office <span className="hint">optional</span><input value={f.joiningOffice} onChange={on("joiningOffice")} maxLength={120} placeholder="e.g. Noida office" /></label>
            {(err || optErr) && <div className="err" role="alert">{err || optErr}</div>}
          </div>
        </Modal>
      )}
    </>
  );
}

/** The business's default list of documents new onboardings ask for. */
export function DefaultDocumentsButton() {
  const toast = useToast();
  const [open, setOpen] = useState(false);
  const [docs, setDocs] = useState<string[]>(DEFAULT_REQUIRED);
  const [err, setErr] = useState("");
  const [busy, start] = useTransition();
  const openIt = () => {
    setErr("");
    setOpen(true);
    void getOnboardingDefaults().then((r) => (r.ok ? setDocs(r.data!.requiredDocs) : setErr(r.error)));
  };
  const save = () => start(async () => {
    const r = await saveOnboardingDefaults(docs);
    if (r.ok) { toast(r.msg!); setOpen(false); } else setErr(r.error);
  });
  return (
    <>
      <button className="btn" onClick={openIt}><Icon n="doc" s={14} />Default documents</button>
      {open && (
        <Modal title="Default documents" eyebrow="What new onboardings ask for" size="sm" onClose={() => setOpen(false)}
          footer={<><button className="btn btn-ghost" onClick={() => setOpen(false)}>Cancel</button><button className="btn btn-gold" disabled={busy} onClick={save}>{busy ? "Saving…" : "Save"}</button></>}>
          <div className="col" style={{ gap: 10 }}>
            <p className="muted" style={{ margin: 0 }}>You can still change the list for each candidate when you start their onboarding.</p>
            {Object.entries(DOC_KINDS).map(([k, l]) => (
              <label key={k} style={{ display: "flex", alignItems: "center", gap: 8 }}>
                <input type="checkbox" checked={docs.includes(k)} onChange={(e) => setDocs(e.target.checked ? [...docs, k] : docs.filter((x) => x !== k))} />{l}
              </label>
            ))}
            {err && <div className="err" role="alert">{err}</div>}
          </div>
        </Modal>
      )}
    </>
  );
}
