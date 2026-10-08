"use client";

import { useState, useTransition } from "react";
import { Icon } from "@/components/Icon";
import { useToast } from "@/components/shell/Toasts";
import { Modal } from "@/components/ui/Modal";
import type { PlanRow } from "@/lib/api-types";
import { MODULE_LABELS } from "@/lib/subscription";
import { createPlan, updatePlan, type PlanInput } from "../platform/actions";

interface Form { name: string; description: string; price: string; periodMonths: string; maxEmployees: string; modules: string[]; active: boolean }

const toForm = (p?: PlanRow): Form => p
  ? { name: p.name, description: p.description ?? "", price: String(p.pricePaise / 100), periodMonths: String(p.periodMonths), maxEmployees: p.maxEmployees ? String(p.maxEmployees) : "", modules: p.modules, active: p.active }
  : { name: "", description: "", price: "", periodMonths: "12", maxEmployees: "", modules: [], active: true };

/** "New plan" (no plan) or the edit button on a plan row. */
export function PlanButton({ plan }: { plan?: PlanRow }) {
  const toast = useToast();
  const [open, setOpen] = useState(false);
  const [f, setF] = useState<Form>(toForm(plan));
  const [err, setErr] = useState("");
  const [busy, start] = useTransition();
  const set = (x: Partial<Form>) => { setF({ ...f, ...x }); setErr(""); };
  const close = () => { setOpen(false); setErr(""); };
  const save = () => {
    if (!f.name.trim()) return setErr("Enter the plan name.");
    if (f.price === "" || Number.isNaN(Number(f.price))) return setErr("Enter a price in rupees (0 or more).");
    const body: PlanInput = {
      name: f.name, description: f.description, price: Number(f.price), periodMonths: Number(f.periodMonths),
      maxEmployees: f.maxEmployees === "" ? null : Number(f.maxEmployees), modules: f.modules, active: f.active,
    };
    start(async () => {
      const r = plan ? await updatePlan(plan.id, body) : await createPlan(body);
      if (r.ok) { toast(r.msg!); close(); } else setErr(r.error);
    });
  };
  return (
    <>
      {plan
        ? <button className="btn btn-sm btn-ghost" onClick={() => { setF(toForm(plan)); setOpen(true); }} aria-label={`Edit ${plan.name}`}><Icon n="edit" s={14} /></button>
        : <button className="btn btn-gold btn-lg" onClick={() => { setF(toForm()); setOpen(true); }}><Icon n="plus" s={16} />New plan</button>}
      {open && (
        <Modal title={plan ? "Edit plan" : "New plan"} eyebrow="Platform" size="sm" onClose={close}
          footer={<><button className="btn btn-ghost" onClick={close}>Cancel</button><button className="btn btn-gold" disabled={busy} onClick={save}>{busy ? "Saving…" : plan ? "Save plan" : "Create plan"}</button></>}>
          <div className="col" style={{ gap: 14 }}>
            <label className="field">Plan name *<input value={f.name} onChange={(e) => set({ name: e.target.value })} maxLength={80} autoFocus /></label>
            <label className="field">Description <span className="hint">optional</span><input value={f.description} onChange={(e) => set({ description: e.target.value })} maxLength={300} /></label>
            <div className="grid3">
              <label className="field">Price (₹) *<input type="number" min={0} step="0.01" value={f.price} onChange={(e) => set({ price: e.target.value })} /></label>
              <label className="field">Period (months)<input type="number" min={1} max={60} value={f.periodMonths} onChange={(e) => set({ periodMonths: e.target.value })} /></label>
              <label className="field">Max people <span className="hint">empty = no limit</span><input type="number" min={1} value={f.maxEmployees} onChange={(e) => set({ maxEmployees: e.target.value })} /></label>
            </div>
            <div className="field">Modules
              {Object.entries(MODULE_LABELS).map(([k, l]) => (
                <label key={k} style={{ display: "flex", alignItems: "center", gap: 8, fontWeight: 400 }}>
                  <input type="checkbox" checked={f.modules.includes(k)} onChange={(e) => set({ modules: e.target.checked ? [...f.modules, k] : f.modules.filter((m) => m !== k) })} />{l}
                </label>
              ))}
            </div>
            <label style={{ display: "flex", alignItems: "center", gap: 8 }}>
              <input type="checkbox" checked={f.active} onChange={(e) => set({ active: e.target.checked })} />Offered for new subscriptions
            </label>
            {err && <div className="err" role="alert">{err}</div>}
          </div>
        </Modal>
      )}
    </>
  );
}
