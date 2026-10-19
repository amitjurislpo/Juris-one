"use client";

import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";
import { Icon } from "@/components/Icon";
import { useToast } from "@/components/shell/Toasts";
import { ConfirmDialog, Modal } from "@/components/ui/Modal";
import type { BusinessProfile, PlanRow } from "@/lib/api-types";
import { inr } from "@/lib/format";
import { useT } from "@/lib/i18n/client";
import {
  addOwner, addSubscription, cancelSubscription, createBusiness, grantModule, revokeModule, setBusinessStatus, setOwnerAccess, updateBusiness,
  type BusinessInput, type GrantInput, type OwnerInput, type SubscriptionInput,
} from "./actions";

const TIME_ZONES = ["Asia/Kolkata", "America/New_York", "America/Chicago", "America/Denver", "America/Los_Angeles"];

const blankProfile: BusinessInput = { name: "", legalName: "", code: "", employeePrefix: "", timeZone: "Asia/Kolkata", contactEmail: "", contactPhone: "", address: "", gstin: "" };

function ProfileFields({ f, set, lockPrefix }: { f: BusinessInput; set: (f: BusinessInput) => void; lockPrefix?: boolean }) {
  const t = useT();
  const v = (k: keyof BusinessInput) => f[k] ?? "";
  const on = (k: keyof BusinessInput) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>) => set({ ...f, [k]: e.target.value });
  return (
    <>
      <div className="grid2">
        <label className="field">{t("Business name *")}<input value={v("name")} onChange={on("name")} maxLength={120} placeholder={t("e.g. Acme Advisors")} autoFocus /></label>
        <label className="field">{t("Legal name")} <span className="hint">{t("optional")}</span><input value={v("legalName")} onChange={on("legalName")} maxLength={160} placeholder="Acme Advisors LLP" /></label>
      </div>
      <div className="grid3">
        <label className="field">{t("Short name")} <span className="hint">{t("auto if empty")}</span><input value={v("code")} onChange={on("code")} maxLength={40} placeholder="acme-advisors" /></label>
        <label className="field">{t("Employee ID prefix")} <span className="hint">{t("2–5 letters")}</span>
          <input value={v("employeePrefix")} onChange={on("employeePrefix")} maxLength={5} placeholder="AA" disabled={lockPrefix} title={lockPrefix ? t("Fixed once the business has people") : undefined} />
        </label>
        <label className="field">{t("Time zone")}
          <select value={v("timeZone")} onChange={on("timeZone")}>{TIME_ZONES.map((z) => <option key={z}>{z}</option>)}</select>
        </label>
      </div>
      <div className="grid3">
        <label className="field">{t("Contact email")}<input type="email" value={v("contactEmail")} onChange={on("contactEmail")} maxLength={200} /></label>
        <label className="field">{t("Phone")}<input value={v("contactPhone")} onChange={on("contactPhone")} maxLength={30} /></label>
        <label className="field">{t("GSTIN")}<input value={v("gstin")} onChange={on("gstin")} maxLength={15} placeholder="22AAAAA0000A1Z5" /></label>
      </div>
      <label className="field">{t("Address")}<input value={v("address")} onChange={on("address")} maxLength={400} /></label>
    </>
  );
}

function OwnerFields({ o, set }: { o: OwnerInput; set: (o: OwnerInput) => void }) {
  const t = useT();
  return (
    <div className="grid3">
      <label className="field">{t("Name *")}<input value={o.name} onChange={(e) => set({ ...o, name: e.target.value })} maxLength={120} /></label>
      <label className="field">{t("Email (signs in with it) *")}<input type="email" value={o.email} onChange={(e) => set({ ...o, email: e.target.value })} maxLength={200} /></label>
      <label className="field">{t("Password")} <span className="hint">{t("empty = email an invite")}</span>
        <input type="text" value={o.password ?? ""} onChange={(e) => set({ ...o, password: e.target.value })} autoComplete="new-password" />
      </label>
    </div>
  );
}

function SubscriptionFields({ s, set, plans }: { s: SubscriptionInput; set: (s: SubscriptionInput) => void; plans: PlanRow[] }) {
  const t = useT();
  const plan = plans.find((p) => p.id === s.planId);
  return (
    <>
      <div className="grid2">
        <label className="field">{t("Plan")}
          <select value={s.planId} onChange={(e) => set({ ...s, planId: e.target.value })}>
            <option value="">{t("— choose a plan —")}</option>
            {plans.filter((p) => p.active).map((p) => <option key={p.id} value={p.id}>{p.name} · {inr(p.pricePaise)} / {t("{n} mo", { n: p.periodMonths })}</option>)}
          </select>
        </label>
        <label className="field">{t("Price charged (₹)")} <span className="hint">{plan ? t("plan price {price}", { price: inr(plan.pricePaise) }) : t("defaults to the plan")}</span>
          <input type="number" min={0} step="0.01" value={s.price ?? ""} onChange={(e) => set({ ...s, price: e.target.value === "" ? null : Number(e.target.value) })} />
        </label>
      </div>
      <div className="grid3">
        <label className="field">{t("Starts on")} <span className="hint">{t("default today")}</span><input type="date" value={s.startsOn ?? ""} onChange={(e) => set({ ...s, startsOn: e.target.value })} /></label>
        <label className="field">{t("Ends on")} <span className="hint">{t("default: plan period")}</span><input type="date" value={s.endsOn ?? ""} onChange={(e) => set({ ...s, endsOn: e.target.value })} /></label>
        <label className="field">{t("Notes")}<input value={s.notes ?? ""} onChange={(e) => set({ ...s, notes: e.target.value })} maxLength={300} placeholder={t("Invoice no., payment ref.")} /></label>
      </div>
    </>
  );
}

const clean = <T extends object>(o: T): T => Object.fromEntries(Object.entries(o).filter(([, v]) => v !== "" && v !== null && v !== undefined)) as T;

export function NewBusinessButton({ plans }: { plans: PlanRow[] }) {
  const toast = useToast();
  const t = useT();
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
      <button className="btn btn-gold btn-lg" onClick={() => setOpen(true)}><Icon n="plus" s={16} />{t("New business")}</button>
      {open && (
        <Modal title={t("New business")} eyebrow={t("Platform")} maxWidth={760} onClose={close}
          footer={<><button className="btn btn-ghost" onClick={close}>{t("Cancel")}</button><button className="btn btn-gold" disabled={busy} onClick={save}>{busy ? t("Creating…") : t("Create business")}</button></>}>
          <div className="col" style={{ gap: 14 }}>
            <ProfileFields f={b} set={(x) => { setB(x); setErr(""); }} />
            <h4 style={{ margin: "6px 0 0" }}>{t("Subscription")}</h4>
            <SubscriptionFields s={s} set={(x) => { setS(x); setErr(""); }} plans={plans} />
            <h4 style={{ margin: "6px 0 0" }}>{t("Sub admin")} <span className="hint muted">{t("— runs the business; optional, can be added later")}</span></h4>
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
  const t = useT();
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
      <button className="btn" onClick={() => { setB(init); setOpen(true); }}><Icon n="edit" s={14} />{t("Edit details")}</button>
      {open && (
        <Modal title={t("Business details")} eyebrow={profile.name} maxWidth={760} onClose={() => setOpen(false)}
          footer={<><button className="btn btn-ghost" onClick={() => setOpen(false)}>{t("Cancel")}</button><button className="btn btn-gold" disabled={busy} onClick={save}>{busy ? t("Saving…") : t("Save")}</button></>}>
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
  const t = useT();
  const [ask, setAsk] = useState(false);
  const [busy, start] = useTransition();
  const suspend = status === "ACTIVE";
  return (
    <>
      <button className={`btn ${suspend ? "btn-ghost" : "btn-gold"}`} onClick={() => setAsk(true)}><Icon n={suspend ? "lock" : "ok"} s={14} />{suspend ? t("Suspend") : t("Reactivate")}</button>
      {ask && (
        <ConfirmDialog title={suspend ? t("Suspend business") : t("Reactivate business")} danger={suspend} confirm={suspend ? t("Suspend") : t("Reactivate")} busy={busy} onClose={() => setAsk(false)}
          body={suspend ? <>{t("Suspend {name}? Everyone in it is signed out at once and can't sign in until you reactivate it. Its data is kept.", { name })}</> : <>{t("Let people in {name} sign in again?", { name })}</>}
          onConfirm={() => start(async () => { const r = await setBusinessStatus(id, suspend ? "SUSPENDED" : "ACTIVE"); toast(r.ok ? r.msg! : r.error, r.ok ? undefined : "bad"); setAsk(false); })} />
      )}
    </>
  );
}

export function AddOwnerButton({ id }: { id: string }) {
  const toast = useToast();
  const t = useT();
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
      <button className="btn btn-sm" onClick={() => setOpen(true)}><Icon n="plus" s={14} />{t("Add sub admin")}</button>
      {open && (
        <Modal title={t("Add sub admin")} eyebrow={t("Runs this business")} maxWidth={720} onClose={close}
          footer={<><button className="btn btn-ghost" onClick={close}>{t("Cancel")}</button><button className="btn btn-gold" disabled={busy} onClick={save}>{busy ? t("Adding…") : t("Add sub admin")}</button></>}>
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
  const t = useT();
  const [busy, start] = useTransition();
  return (
    <button className="switch" role="switch" aria-checked={on} disabled={busy} aria-label={on ? t("Turn off access for {name}", { name }) : t("Turn on access for {name}", { name })}
      onClick={() => start(async () => { const r = await setOwnerAccess(businessId, ownerId, !on); toast(r.ok ? r.msg! : r.error, r.ok ? undefined : "bad"); })}><i></i></button>
  );
}

export function AddSubscriptionButton({ id, plans, label }: { id: string; plans: PlanRow[]; label: string }) {
  const toast = useToast();
  const t = useT();
  const [open, setOpen] = useState(false);
  const [s, setS] = useState<SubscriptionInput>({ planId: "" });
  const [err, setErr] = useState("");
  const [busy, start] = useTransition();
  const close = () => { setOpen(false); setErr(""); setS({ planId: "" }); };
  const save = () => start(async () => {
    if (!s.planId) { setErr(t("Choose a plan.")); return; }
    const r = await addSubscription(id, clean(s));
    if (r.ok) { toast(r.msg!); close(); } else setErr(r.error);
  });
  return (
    <>
      <button className="btn btn-sm btn-gold" onClick={() => setOpen(true)}><Icon n="plus" s={14} />{label}</button>
      {open && (
        <Modal title={label} eyebrow={t("Subscription")} maxWidth={720} onClose={close}
          footer={<><button className="btn btn-ghost" onClick={close}>{t("Cancel")}</button><button className="btn btn-gold" disabled={busy} onClick={save}>{busy ? t("Saving…") : t("Save")}</button></>}>
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
  const t = useT();
  const [ask, setAsk] = useState(false);
  const [busy, start] = useTransition();
  return (
    <>
      <button className="btn btn-sm btn-ghost" onClick={() => setAsk(true)} aria-label={t("Cancel {label}", { label })}><Icon n="x" s={14} /></button>
      {ask && (
        <ConfirmDialog title={t("Cancel subscription")} danger confirm={t("Cancel subscription")} busy={busy} onClose={() => setAsk(false)}
          body={<>{t("Cancel {label}? If it covers today, the business loses access straight away (the owner can still sign in and export employees).", { label })}</>}
          onConfirm={() => start(async () => { const r = await cancelSubscription(businessId, subscriptionId); toast(r.ok ? r.msg! : r.error, r.ok ? undefined : "bad"); setAsk(false); })} />
      )}
    </>
  );
}

export function GrantJurisTrackButton({ id }: { id: string }) {
  const toast = useToast();
  const t = useT();
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
      <button className="btn btn-sm" onClick={() => setOpen(true)}><Icon n="sparkle" s={14} />{t("Grant Juris Track")}</button>
      {open && (
        <Modal title={t("Grant Juris Track")} eyebrow={t("On its own, whatever the plan")} size="sm" onClose={close}
          footer={<><button className="btn btn-ghost" onClick={close}>{t("Cancel")}</button><button className="btn btn-gold" disabled={busy} onClick={save}>{busy ? t("Granting…") : t("Grant")}</button></>}>
          <div className="col" style={{ gap: 14 }}>
            <div className="field">{t("Type")}
              <div style={{ display: "flex", gap: 16 }}>
                {(["COMPLIMENTARY", "PAID"] as const).map((x) => (
                  <label key={x} style={{ display: "flex", alignItems: "center", gap: 6, fontWeight: 400 }}>
                    <input type="radio" name="source" checked={g.source === x} onChange={() => set({ source: x })} />{x === "PAID" ? t("Paid") : t("Complimentary (free)")}
                  </label>
                ))}
              </div>
            </div>
            {paid && (
              <label className="field">{t("Price paid (₹) *")}<input type="number" min={1} step="0.01" value={g.price ?? ""} onChange={(e) => set({ price: e.target.value === "" ? null : Number(e.target.value) })} /></label>
            )}
            <div className="grid2">
              <label className="field">{t("Starts on")} <span className="hint">{t("default today")}</span><input type="date" value={g.startsOn ?? ""} onChange={(e) => set({ startsOn: e.target.value })} /></label>
              <label className="field">{t("Ends on")} {paid ? "*" : <span className="hint">{t("empty = no end")}</span>}<input type="date" value={g.endsOn ?? ""} onChange={(e) => set({ endsOn: e.target.value })} /></label>
            </div>
            <label className="field">{t("Notes")}<input value={g.notes ?? ""} onChange={(e) => set({ notes: e.target.value })} maxLength={300} placeholder={t("Invoice no., reason")} /></label>
            {err && <div className="err" role="alert">{err}</div>}
          </div>
        </Modal>
      )}
    </>
  );
}

export function RevokeGrantButton({ businessId, grantId, label }: { businessId: string; grantId: string; label: string }) {
  const toast = useToast();
  const t = useT();
  const [ask, setAsk] = useState(false);
  const [busy, start] = useTransition();
  return (
    <>
      <button className="btn btn-sm btn-ghost" onClick={() => setAsk(true)} aria-label={t("Revoke {label}", { label })}><Icon n="x" s={14} /></button>
      {ask && (
        <ConfirmDialog title={t("Revoke Juris Track")} danger confirm={t("Revoke")} busy={busy} onClose={() => setAsk(false)}
          body={<>{t("Revoke {label}? Unless the business's plan includes Juris Track, it disappears from their menu straight away. The grant stays on record.", { label })}</>}
          onConfirm={() => start(async () => { const r = await revokeModule(businessId, grantId); toast(r.ok ? r.msg! : r.error, r.ok ? undefined : "bad"); setAsk(false); })} />
      )}
    </>
  );
}
