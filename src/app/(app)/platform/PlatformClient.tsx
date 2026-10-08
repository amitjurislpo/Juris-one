"use client";

import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";
import { Icon } from "@/components/Icon";
import { useToast } from "@/components/shell/Toasts";
import { ConfirmDialog, Modal } from "@/components/ui/Modal";
import type { BusinessProfile, PlanRow } from "@/lib/api-types";
import { inr } from "@/lib/format";
import {
  addOwner, addSubscription, cancelSubscription, createBusiness, grantModule, revokeModule, setBusinessStatus, setOwnerAccess, updateBusiness,
  type BusinessInput, type GrantInput, type OwnerInput, type SubscriptionInput,
} from "./actions";

const TIME_ZONES = ["Asia/Kolkata", "America/New_York", "America/Chicago", "America/Denver", "America/Los_Angeles"];

const blankProfile: BusinessInput = { name: "", legalName: "", code: "", employeePrefix: "", timeZone: "Asia/Kolkata", contactEmail: "", contactPhone: "", address: "", gstin: "" };

function ProfileFields({ f, set, lockPrefix }: { f: BusinessInput; set: (f: BusinessInput) => void; lockPrefix?: boolean }) {
  const v = (k: keyof BusinessInput) => f[k] ?? "";
  const on = (k: keyof BusinessInput) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>) => set({ ...f, [k]: e.target.value });
  return (
    <>
      <div className="grid2">
        <label className="field">Business name *<input value={v("name")} onChange={on("name")} maxLength={120} placeholder="e.g. Acme Advisors" autoFocus /></label>
        <label className="field">Legal name <span className="hint">optional</span><input value={v("legalName")} onChange={on("legalName")} maxLength={160} placeholder="Acme Advisors LLP" /></label>
      </div>
      <div className="grid3">
        <label className="field">Short name <span className="hint">auto if empty</span><input value={v("code")} onChange={on("code")} maxLength={40} placeholder="acme-advisors" /></label>
        <label className="field">Employee ID prefix <span className="hint">2–5 letters</span>
          <input value={v("employeePrefix")} onChange={on("employeePrefix")} maxLength={5} placeholder="AA" disabled={lockPrefix} title={lockPrefix ? "Fixed once the business has people" : undefined} />
        </label>
        <label className="field">Time zone
          <select value={v("timeZone")} onChange={on("timeZone")}>{TIME_ZONES.map((z) => <option key={z}>{z}</option>)}</select>
        </label>
      </div>
      <div className="grid3">
        <label className="field">Contact email<input type="email" value={v("contactEmail")} onChange={on("contactEmail")} maxLength={200} /></label>
        <label className="field">Phone<input value={v("contactPhone")} onChange={on("contactPhone")} maxLength={30} /></label>
        <label className="field">GSTIN<input value={v("gstin")} onChange={on("gstin")} maxLength={15} placeholder="22AAAAA0000A1Z5" /></label>
      </div>
      <label className="field">Address<input value={v("address")} onChange={on("address")} maxLength={400} /></label>
    </>
  );
}

function OwnerFields({ o, set }: { o: OwnerInput; set: (o: OwnerInput) => void }) {
  return (
    <div className="grid3">
      <label className="field">Name *<input value={o.name} onChange={(e) => set({ ...o, name: e.target.value })} maxLength={120} /></label>
      <label className="field">Email (signs in with it) *<input type="email" value={o.email} onChange={(e) => set({ ...o, email: e.target.value })} maxLength={200} /></label>
      <label className="field">Password <span className="hint">empty = email an invite</span>
        <input type="text" value={o.password ?? ""} onChange={(e) => set({ ...o, password: e.target.value })} autoComplete="new-password" />
      </label>
    </div>
  );
}

function SubscriptionFields({ s, set, plans }: { s: SubscriptionInput; set: (s: SubscriptionInput) => void; plans: PlanRow[] }) {
  const plan = plans.find((p) => p.id === s.planId);
  return (
    <>
      <div className="grid2">
        <label className="field">Plan
          <select value={s.planId} onChange={(e) => set({ ...s, planId: e.target.value })}>
            <option value="">— choose a plan —</option>
            {plans.filter((p) => p.active).map((p) => <option key={p.id} value={p.id}>{p.name} · {inr(p.pricePaise)} / {p.periodMonths} mo</option>)}
          </select>
        </label>
        <label className="field">Price charged (₹) <span className="hint">{plan ? `plan price ${inr(plan.pricePaise)}` : "defaults to the plan"}</span>
          <input type="number" min={0} step="0.01" value={s.price ?? ""} onChange={(e) => set({ ...s, price: e.target.value === "" ? null : Number(e.target.value) })} />
        </label>
      </div>
      <div className="grid3">
        <label className="field">Starts on <span className="hint">default today</span><input type="date" value={s.startsOn ?? ""} onChange={(e) => set({ ...s, startsOn: e.target.value })} /></label>
        <label className="field">Ends on <span className="hint">default: plan period</span><input type="date" value={s.endsOn ?? ""} onChange={(e) => set({ ...s, endsOn: e.target.value })} /></label>
        <label className="field">Notes<input value={s.notes ?? ""} onChange={(e) => set({ ...s, notes: e.target.value })} maxLength={300} placeholder="Invoice no., payment ref." /></label>
      </div>
    </>
  );
}

const clean = <T extends object>(o: T): T => Object.fromEntries(Object.entries(o).filter(([, v]) => v !== "" && v !== null && v !== undefined)) as T;

export function NewBusinessButton({ plans }: { plans: PlanRow[] }) {
  const toast = useToast();
  const router = useRouter();
  const [open, setOpen] = useState(false);
  const [b, setB] = useState<BusinessInput>(blankProfile);
  const [s, setS] = useState<SubscriptionInput>({ planId: "" });
  const [o, setO] = useState<OwnerInput>({ name: "", email: "", password: "" });
  const [err, setErr] = useState("");
  const [busy, start] = useTransition();
  const close = () => { setOpen(false); setErr(""); setB(blankProfile); setS({ planId: "" }); setO({ name: "", email: "", password: "" }); };
  const save = () => start(async () => {
    const r = await createBusiness({ business: clean(b), subscription: s.planId ? clean(s) : undefined, owner: o.name || o.email ? clean(o) : undefined });
    if (r.ok) { toast(r.msg!); close(); router.push(`/platform/${r.data!.id}`); } else setErr(r.error);
  });
  return (
    <>
      <button className="btn btn-gold btn-lg" onClick={() => setOpen(true)}><Icon n="plus" s={16} />New business</button>
      {open && (
        <Modal title="New business" eyebrow="Platform" maxWidth={760} onClose={close}
          footer={<><button className="btn btn-ghost" onClick={close}>Cancel</button><button className="btn btn-gold" disabled={busy} onClick={save}>{busy ? "Creating…" : "Create business"}</button></>}>
          <div className="col" style={{ gap: 14 }}>
            <ProfileFields f={b} set={(x) => { setB(x); setErr(""); }} />
            <h4 style={{ margin: "6px 0 0" }}>Subscription</h4>
            <SubscriptionFields s={s} set={(x) => { setS(x); setErr(""); }} plans={plans} />
            <h4 style={{ margin: "6px 0 0" }}>Sub admin <span className="hint muted">— runs the business; optional, can be added later</span></h4>
            <OwnerFields o={o} set={(x) => { setO(x); setErr(""); }} />
            {err && <div className="err" role="alert">{err}</div>}
          </div>
        </Modal>
      )}
    </>
  );
}

export function EditBusinessButton({ id, profile, hasPeople }: { id: string; profile: BusinessProfile; hasPeople: boolean }) {
  const toast = useToast();
  const [open, setOpen] = useState(false);
  const init: BusinessInput = Object.fromEntries(Object.entries(profile).map(([k, v]) => [k, v ?? ""])) as unknown as BusinessInput;
  const [b, setB] = useState<BusinessInput>(init);
  const [err, setErr] = useState("");
  const [busy, start] = useTransition();
  const save = () => start(async () => {
    const r = await updateBusiness(id, b);
    if (r.ok) { toast(r.msg!); setOpen(false); } else setErr(r.error);
  });
  return (
    <>
      <button className="btn" onClick={() => { setB(init); setOpen(true); }}><Icon n="edit" s={14} />Edit details</button>
      {open && (
        <Modal title="Business details" eyebrow={profile.name} maxWidth={760} onClose={() => setOpen(false)}
          footer={<><button className="btn btn-ghost" onClick={() => setOpen(false)}>Cancel</button><button className="btn btn-gold" disabled={busy} onClick={save}>{busy ? "Saving…" : "Save"}</button></>}>
          <div className="col" style={{ gap: 14 }}>
            <ProfileFields f={b} set={(x) => { setB(x); setErr(""); }} lockPrefix={hasPeople} />
            {err && <div className="err" role="alert">{err}</div>}
          </div>
        </Modal>
      )}
    </>
  );
}

export function BusinessStatusButton({ id, name, status }: { id: string; name: string; status: "ACTIVE" | "SUSPENDED" }) {
  const toast = useToast();
  const [ask, setAsk] = useState(false);
  const [busy, start] = useTransition();
  const suspend = status === "ACTIVE";
  return (
    <>
      <button className={`btn ${suspend ? "btn-ghost" : "btn-gold"}`} onClick={() => setAsk(true)}><Icon n={suspend ? "lock" : "ok"} s={14} />{suspend ? "Suspend" : "Reactivate"}</button>
      {ask && (
        <ConfirmDialog title={suspend ? "Suspend business" : "Reactivate business"} danger={suspend} confirm={suspend ? "Suspend" : "Reactivate"} busy={busy} onClose={() => setAsk(false)}
          body={suspend ? <>Suspend <b>{name}</b>? Everyone in it is signed out at once and can&apos;t sign in until you reactivate it. Its data is kept.</> : <>Let people in <b>{name}</b> sign in again?</>}
          onConfirm={() => start(async () => { const r = await setBusinessStatus(id, suspend ? "SUSPENDED" : "ACTIVE"); toast(r.ok ? r.msg! : r.error, r.ok ? undefined : "bad"); setAsk(false); })} />
      )}
    </>
  );
}

export function AddOwnerButton({ id }: { id: string }) {
  const toast = useToast();
  const [open, setOpen] = useState(false);
  const [o, setO] = useState<OwnerInput>({ name: "", email: "", password: "" });
  const [err, setErr] = useState("");
  const [busy, start] = useTransition();
  const close = () => { setOpen(false); setErr(""); setO({ name: "", email: "", password: "" }); };
  const save = () => start(async () => {
    const r = await addOwner(id, clean(o));
    if (r.ok) { toast(r.msg!); close(); } else setErr(r.error);
  });
  return (
    <>
      <button className="btn btn-sm" onClick={() => setOpen(true)}><Icon n="plus" s={14} />Add sub admin</button>
      {open && (
        <Modal title="Add sub admin" eyebrow="Runs this business" maxWidth={720} onClose={close}
          footer={<><button className="btn btn-ghost" onClick={close}>Cancel</button><button className="btn btn-gold" disabled={busy} onClick={save}>{busy ? "Adding…" : "Add sub admin"}</button></>}>
          <div className="col" style={{ gap: 14 }}>
            <OwnerFields o={o} set={(x) => { setO(x); setErr(""); }} />
            {err && <div className="err" role="alert">{err}</div>}
          </div>
        </Modal>
      )}
    </>
  );
}

export function OwnerAccessSwitch({ businessId, ownerId, name, on }: { businessId: string; ownerId: string; name: string; on: boolean }) {
  const toast = useToast();
  const [busy, start] = useTransition();
  return (
    <button className="switch" role="switch" aria-checked={on} disabled={busy} aria-label={`${on ? "Turn off" : "Turn on"} access for ${name}`}
      onClick={() => start(async () => { const r = await setOwnerAccess(businessId, ownerId, !on); toast(r.ok ? r.msg! : r.error, r.ok ? undefined : "bad"); })}><i></i></button>
  );
}

export function AddSubscriptionButton({ id, plans, label }: { id: string; plans: PlanRow[]; label: string }) {
  const toast = useToast();
  const [open, setOpen] = useState(false);
  const [s, setS] = useState<SubscriptionInput>({ planId: "" });
  const [err, setErr] = useState("");
  const [busy, start] = useTransition();
  const close = () => { setOpen(false); setErr(""); setS({ planId: "" }); };
  const save = () => start(async () => {
    if (!s.planId) { setErr("Choose a plan."); return; }
    const r = await addSubscription(id, clean(s));
    if (r.ok) { toast(r.msg!); close(); } else setErr(r.error);
  });
  return (
    <>
      <button className="btn btn-sm btn-gold" onClick={() => setOpen(true)}><Icon n="plus" s={14} />{label}</button>
      {open && (
        <Modal title={label} eyebrow="Subscription" maxWidth={720} onClose={close}
          footer={<><button className="btn btn-ghost" onClick={close}>Cancel</button><button className="btn btn-gold" disabled={busy} onClick={save}>{busy ? "Saving…" : "Save"}</button></>}>
          <div className="col" style={{ gap: 14 }}>
            <SubscriptionFields s={s} set={(x) => { setS(x); setErr(""); }} plans={plans} />
            {err && <div className="err" role="alert">{err}</div>}
          </div>
        </Modal>
      )}
    </>
  );
}

export function CancelSubscriptionButton({ businessId, subscriptionId, label }: { businessId: string; subscriptionId: string; label: string }) {
  const toast = useToast();
  const [ask, setAsk] = useState(false);
  const [busy, start] = useTransition();
  return (
    <>
      <button className="btn btn-sm btn-ghost" onClick={() => setAsk(true)} aria-label={`Cancel ${label}`}><Icon n="x" s={14} /></button>
      {ask && (
        <ConfirmDialog title="Cancel subscription" danger confirm="Cancel subscription" busy={busy} onClose={() => setAsk(false)}
          body={<>Cancel <b>{label}</b>? If it covers today, the business loses access straight away (the owner can still sign in and export employees).</>}
          onConfirm={() => start(async () => { const r = await cancelSubscription(businessId, subscriptionId); toast(r.ok ? r.msg! : r.error, r.ok ? undefined : "bad"); setAsk(false); })} />
      )}
    </>
  );
}

export function GrantJurisTrackButton({ id }: { id: string }) {
  const toast = useToast();
  const [open, setOpen] = useState(false);
  const [g, setG] = useState<GrantInput>({ source: "COMPLIMENTARY" });
  const [err, setErr] = useState("");
  const [busy, start] = useTransition();
  const set = (x: Partial<GrantInput>) => { setG({ ...g, ...x }); setErr(""); };
  const close = () => { setOpen(false); setErr(""); setG({ source: "COMPLIMENTARY" }); };
  const paid = g.source === "PAID";
  const save = () => start(async () => {
    const r = await grantModule(id, clean(paid ? g : { ...g, price: undefined }));
    if (r.ok) { toast(r.msg!); close(); } else setErr(r.error);
  });
  return (
    <>
      <button className="btn btn-sm" onClick={() => setOpen(true)}><Icon n="sparkle" s={14} />Grant Juris Track</button>
      {open && (
        <Modal title="Grant Juris Track" eyebrow="On its own, whatever the plan" size="sm" onClose={close}
          footer={<><button className="btn btn-ghost" onClick={close}>Cancel</button><button className="btn btn-gold" disabled={busy} onClick={save}>{busy ? "Granting…" : "Grant"}</button></>}>
          <div className="col" style={{ gap: 14 }}>
            <div className="field">Type
              <div style={{ display: "flex", gap: 16 }}>
                {(["COMPLIMENTARY", "PAID"] as const).map((x) => (
                  <label key={x} style={{ display: "flex", alignItems: "center", gap: 6, fontWeight: 400 }}>
                    <input type="radio" name="source" checked={g.source === x} onChange={() => set({ source: x })} />{x === "PAID" ? "Paid" : "Complimentary (free)"}
                  </label>
                ))}
              </div>
            </div>
            {paid && (
              <label className="field">Price paid (₹) *<input type="number" min={1} step="0.01" value={g.price ?? ""} onChange={(e) => set({ price: e.target.value === "" ? null : Number(e.target.value) })} /></label>
            )}
            <div className="grid2">
              <label className="field">Starts on <span className="hint">default today</span><input type="date" value={g.startsOn ?? ""} onChange={(e) => set({ startsOn: e.target.value })} /></label>
              <label className="field">Ends on {paid ? "*" : <span className="hint">empty = no end</span>}<input type="date" value={g.endsOn ?? ""} onChange={(e) => set({ endsOn: e.target.value })} /></label>
            </div>
            <label className="field">Notes<input value={g.notes ?? ""} onChange={(e) => set({ notes: e.target.value })} maxLength={300} placeholder="Invoice no., reason" /></label>
            {err && <div className="err" role="alert">{err}</div>}
          </div>
        </Modal>
      )}
    </>
  );
}

export function RevokeGrantButton({ businessId, grantId, label }: { businessId: string; grantId: string; label: string }) {
  const toast = useToast();
  const [ask, setAsk] = useState(false);
  const [busy, start] = useTransition();
  return (
    <>
      <button className="btn btn-sm btn-ghost" onClick={() => setAsk(true)} aria-label={`Revoke ${label}`}><Icon n="x" s={14} /></button>
      {ask && (
        <ConfirmDialog title="Revoke Juris Track" danger confirm="Revoke" busy={busy} onClose={() => setAsk(false)}
          body={<>Revoke <b>{label}</b>? Unless the business&apos;s plan includes Juris Track, it disappears from their menu straight away. The grant stays on record.</>}
          onConfirm={() => start(async () => { const r = await revokeModule(businessId, grantId); toast(r.ok ? r.msg! : r.error, r.ok ? undefined : "bad"); setAsk(false); })} />
      )}
    </>
  );
}
