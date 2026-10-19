"use client";

import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";
import { Icon } from "@/components/Icon";
import { useToast } from "@/components/shell/Toasts";
import { Modal } from "@/components/ui/Modal";
import { getFormOptions, type FormOptions } from "../employees/actions";
import { getExitPolicy, saveExitPolicy, startExit, type ExitPolicyView } from "./actions";

/** HR starts an exit for someone (termination or other), optionally with the last day and a shorter notice. */
export function StartExitButton() {
  const toast = useToast();
  const router = useRouter();
  const [open, setOpen] = useState(false);
  const [opt, setOpt] = useState<FormOptions | null>(null);
  const [f, setF] = useState({ employeeId: "", kind: "TERMINATION" as "TERMINATION" | "OTHER" | "RESIGNATION", reason: "", lastWorkingDay: "", noticeDays: "", noticeReason: "" });
  const [err, setErr] = useState("");
  const [busy, start] = useTransition();
  const close = () => { setOpen(false); setErr(""); };
  const on = (k: keyof typeof f) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>) => { setF({ ...f, [k]: e.target.value }); setErr(""); };
  const openIt = async () => {
    setOpen(true);
    if (!opt) { const r = await getFormOptions(); if (r.ok) setOpt(r.data!); else setErr(r.error); }
  };
  const save = () => start(async () => {
    const r = await startExit({ employeeId: f.employeeId, kind: f.kind, reason: f.reason, lastWorkingDay: f.lastWorkingDay || undefined,
      noticeDays: f.noticeDays === "" ? undefined : Number(f.noticeDays), noticeReason: f.noticeReason || undefined });
    if (r.ok) { toast(r.msg!); close(); router.push(`/exits/${r.data!.id}`); } else setErr(r.error);
  });
  return (
    <>
      <button className="btn btn-gold btn-lg" onClick={() => void openIt()}><Icon n="plus" s={16} />Start an exit</button>
      {open && (
        <Modal title="Start an exit" eyebrow="For a termination, or a resignation you are recording for the employee" maxWidth={640} onClose={close}
          footer={<><button className="btn btn-ghost" onClick={close}>Cancel</button><button className="btn btn-gold" disabled={busy || !f.employeeId || f.reason.trim().length < 3} onClick={save}>{busy ? "Starting…" : "Start exit"}</button></>}>
          <div className="col" style={{ gap: 14 }}>
            <div className="grid2">
              <label className="field">Employee *
                <select value={f.employeeId} onChange={on("employeeId")}><option value="">—</option>{opt?.managers.map((m) => <option key={m.id} value={m.id}>{m.name} — {m.title}</option>)}</select>
              </label>
              <label className="field">Type *
                <select value={f.kind} onChange={on("kind")}><option value="TERMINATION">Termination</option><option value="RESIGNATION">Resignation (recorded by HR)</option><option value="OTHER">Other</option></select>
              </label>
            </div>
            <label className="field">Reason *<textarea value={f.reason} onChange={on("reason")} rows={3} maxLength={1000} /></label>
            <div className="grid3">
              <label className="field">Last working day <span className="hint">can be set later</span><input type="date" value={f.lastWorkingDay} onChange={on("lastWorkingDay")} /></label>
              <label className="field">Notice days <span className="hint">blank = policy</span><input type="number" min={0} max={365} value={f.noticeDays} onChange={on("noticeDays")} /></label>
              <label className="field">Why shorter / waived<input value={f.noticeReason} onChange={on("noticeReason")} maxLength={500} /></label>
            </div>
            {err && <div className="err" role="alert">{err}</div>}
          </div>
        </Modal>
      )}
    </>
  );
}

/** The business's exit policy: notice, leave types paid out, gratuity. */
export function ExitPolicyButton() {
  const toast = useToast();
  const [open, setOpen] = useState(false);
  const [p, setP] = useState<ExitPolicyView | null>(null);
  const [err, setErr] = useState("");
  const [busy, start] = useTransition();
  const openIt = async () => {
    setOpen(true);
    const r = await getExitPolicy();
    if (r.ok) setP(r.data!); else setErr(r.error);
  };
  const save = () => p && start(async () => {
    const r = await saveExitPolicy({ noticeDays: p.noticeDays, encashableLeaveTypes: p.encashableLeaveTypes, gratuityEnabled: p.gratuityEnabled, gratuityMinYears: p.gratuityMinYears, gratuityCapRupees: p.gratuityCapRupees });
    if (r.ok) { toast(r.msg!); setOpen(false); } else setErr(r.error);
  });
  return (
    <>
      <button className="btn" onClick={() => void openIt()}><Icon n="sliders" s={14} />Exit policy</button>
      {open && (
        <Modal title="Exit policy" eyebrow="Applies to new exits and to settlements not yet approved" size="sm" onClose={() => setOpen(false)}
          footer={<><button className="btn btn-ghost" onClick={() => setOpen(false)}>Cancel</button><button className="btn btn-gold" disabled={busy || !p} onClick={save}>{busy ? "Saving…" : "Save"}</button></>}>
          {p ? (
            <div className="col" style={{ gap: 14 }}>
              <label className="field">Notice period (days)<input type="number" min={0} max={365} value={p.noticeDays} onChange={(e) => { setP({ ...p, noticeDays: Number(e.target.value) }); setErr(""); }} /></label>
              <div className="field">Leave types paid out at exit
                <div style={{ display: "flex", flexWrap: "wrap", gap: "6px 16px" }}>
                  {p.leaveTypes.map((t) => (
                    <label key={t.code} style={{ display: "flex", alignItems: "center", gap: 6, fontWeight: 400 }}>
                      <input type="checkbox" checked={p.encashableLeaveTypes.includes(t.code)}
                        onChange={(e) => setP({ ...p, encashableLeaveTypes: e.target.checked ? [...p.encashableLeaveTypes, t.code] : p.encashableLeaveTypes.filter((x) => x !== t.code) })} />{t.name}
                    </label>
                  ))}
                </div>
              </div>
              <label style={{ display: "flex", alignItems: "center", gap: 8 }}>
                <input type="checkbox" checked={p.gratuityEnabled} onChange={(e) => setP({ ...p, gratuityEnabled: e.target.checked })} />
                Pay gratuity <span className="muted">(15/26 × last Basic × completed years)</span>
              </label>
              {p.gratuityEnabled && (
                <div className="grid2">
                  <label className="field">Minimum years of service<input type="number" min={1} max={50} value={p.gratuityMinYears} onChange={(e) => setP({ ...p, gratuityMinYears: Number(e.target.value) })} /></label>
                  <label className="field">Ceiling (₹)<input type="number" min={0} value={p.gratuityCapRupees} onChange={(e) => setP({ ...p, gratuityCapRupees: Number(e.target.value) })} /></label>
                </div>
              )}
              {err && <div className="err" role="alert">{err}</div>}
            </div>
          ) : <p className="muted">{err || "Loading…"}</p>}
        </Modal>
      )}
    </>
  );
}
