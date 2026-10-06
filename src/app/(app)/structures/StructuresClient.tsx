"use client";

import { useState, useTransition } from "react";
import { Icon } from "@/components/Icon";
import { useToast } from "@/components/shell/Toasts";
import { Drawer, Modal } from "@/components/ui/Modal";
import { fmtDY, inr } from "@/lib/format";
import { splitGross } from "@/lib/payroll/engine";
import { addStructure } from "./actions";

export interface Rev { from: string; basic: number; hra: number; lta: number; special: number; reason: string }
const REASONS = [["JOINING", "Joining"], ["APPRAISAL", "Appraisal"], ["PROMOTION", "Promotion"], ["CORRECTION", "Correction"]] as const;

export function StructureButton({ emp, current, minFrom }: { emp: { id: string; code: string; name: string; title: string; doj: string }; current: Rev | null; minFrom: string }) {
  const toast = useToast();
  const [open, setOpen] = useState(false);
  const r = (p: number) => String(Math.round(p / 100));
  const init = () => ({
    from: current ? "" : minFrom, reason: current ? "APPRAISAL" : "JOINING",
    target: current ? r(current.basic + current.hra + current.lta + current.special) : "",
    basic: current ? r(current.basic) : "", hra: current ? r(current.hra) : "", lta: current ? r(current.lta) : "", special: current ? r(current.special) : "",
  });
  const [f, setF] = useState(init);
  const [err, setErr] = useState("");
  const [busy, start] = useTransition();
  const n = (s: string) => Math.max(0, Math.round(Number(s) || 0));
  const g = n(f.basic) + n(f.hra) + n(f.lta) + n(f.special);
  const pf = Math.round(0.12 * Math.min(n(f.basic), 15000));
  const split = () => { const s = splitGross(n(f.target) * 100); setF({ ...f, basic: r(s.basic), hra: r(s.hra), lta: r(s.lta), special: r(s.special) }); };
  const save = () => start(async () => {
    setErr("");
    const res = await addStructure({ employeeId: emp.id, effectiveFrom: f.from, reason: f.reason as "JOINING" | "APPRAISAL" | "PROMOTION" | "CORRECTION", basic: n(f.basic), hra: n(f.hra), lta: n(f.lta), special: n(f.special) });
    if (res.ok) { toast(res.msg!); setOpen(false); } else setErr(res.error);
  });
  return (
    <>
      <button className={`btn btn-sm${current ? "" : " btn-gold"}`} onClick={() => { setF(init()); setErr(""); setOpen(true); }}><Icon n={current ? "edit" : "plus"} s={14} />{current ? "Revise" : "Set up"}</button>
      {open && (
        <Modal title={`${current ? "Revise salary" : "Set salary structure"} · ${emp.name}`} eyebrow={`${emp.code} · ${emp.title}`} maxWidth={640} onClose={() => setOpen(false)}
          footer={<><button className="btn btn-ghost" onClick={() => setOpen(false)}>Cancel</button><button className="btn btn-gold" disabled={!g || !f.from || busy} onClick={save}>{busy ? "Saving…" : "Save structure"}</button></>}>
          <div className="col" style={{ gap: 16 }}>
            <div className="grid3">
              <label className="field">Effective from<input type="date" min={minFrom} value={f.from} onChange={(e) => setF({ ...f, from: e.target.value })} /></label>
              <label className="field">Reason<select value={f.reason} onChange={(e) => setF({ ...f, reason: e.target.value })}>{REASONS.map(([v, l]) => <option key={v} value={v}>{l}</option>)}</select></label>
              <label className="field">Target gross / month (₹)
                <span style={{ display: "flex", gap: 6 }}><input type="number" min={0} value={f.target} onChange={(e) => setF({ ...f, target: e.target.value })} style={{ flex: 1, minWidth: 0 }} /><button type="button" className="btn btn-sm" onClick={split}>Split</button></span>
              </label>
            </div>
            <div className="grid2">
              <label className="field">Basic (₹)<input type="number" min={0} value={f.basic} onChange={(e) => setF({ ...f, basic: e.target.value })} /></label>
              <label className="field">House rent allowance (₹)<input type="number" min={0} value={f.hra} onChange={(e) => setF({ ...f, hra: e.target.value })} /></label>
              <label className="field">Leave travel allowance (₹)<input type="number" min={0} value={f.lta} onChange={(e) => setF({ ...f, lta: e.target.value })} /></label>
              <label className="field">Special allowance (₹)<input type="number" min={0} value={f.special} onChange={(e) => setF({ ...f, special: e.target.value })} /></label>
            </div>
            <div className="summary">
              <div><small>Monthly gross</small><b>{inr(g * 100)}</b></div>
              <div><small>Employee PF</small><b>{inr(pf * 100)}</b></div>
              <div><small>Annual CTC (incl. employer PF)</small><b>{inr((g + pf) * 1200)}</b></div>
            </div>
            <p className="faint" style={{ margin: 0, fontSize: 12.5 }}>“Split” uses Basic 50% · HRA 20% · LTA 5% · Special for the rest. Older structures stay in history; payroll picks the one effective on the last day of each month.</p>
            {err && <p className="err" role="alert" style={{ margin: 0 }}>{err}</p>}
          </div>
        </Modal>
      )}
    </>
  );
}

export function HistoryButton({ name, revs, today }: { name: string; revs: Rev[]; today: string }) {
  const [open, setOpen] = useState(false);
  const current = revs.find((r) => r.from <= today);
  return (
    <>
      <button className="btn btn-sm btn-ghost" style={{ marginLeft: 6 }} onClick={() => setOpen(true)}>{revs.length} revisions</button>
      {open && (
        <Drawer label="Revision history" onClose={() => setOpen(false)}>
          <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
            <div><span className="eyebrow">Revision history</span><h2 className="display" style={{ margin: "4px 0 0", fontSize: 28 }}>{name}</h2></div>
            <button className="icon-btn" onClick={() => setOpen(false)} aria-label="Close"><Icon n="x" s={16} /></button>
          </div>
          {revs.map((s) => (
            <div className="card pad" key={s.from} style={{ boxShadow: "none" }}>
              <div style={{ display: "flex", justifyContent: "space-between" }}><b>{fmtDY(s.from)}</b><span className={`chip ${s === current ? "ok" : s.from > today ? "info" : "neutral"}`}>{s === current ? "Current" : s.from > today ? "Upcoming" : s.reason[0] + s.reason.slice(1).toLowerCase()}</span></div>
              <div className="kv"><span className="muted">Monthly gross</span><b className="num">{inr(s.basic + s.hra + s.lta + s.special)}</b></div>
              <div className="faint" style={{ fontSize: 12.5 }}>Basic {inr(s.basic)} · HRA {inr(s.hra)} · LTA {inr(s.lta)} · Special {inr(s.special)}</div>
            </div>
          ))}
        </Drawer>
      )}
    </>
  );
}
