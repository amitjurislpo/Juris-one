import type { Metadata } from "next";
import Link from "next/link";
import { Icon } from "@/components/Icon";
import { PageHeader } from "@/components/PageHeader";
import type { BusinessProfile, PlanRow, SubscriptionCurrent, SubscriptionRow } from "@/lib/api-types";
import { requirePage } from "@/lib/auth";
import { apiData } from "@/lib/backend";
import { fmtDY, inr } from "@/lib/format";
import { MODULE_LABELS, subscriptionChip } from "@/lib/subscription";
import {
  AddOwnerButton, AddSubscriptionButton, BusinessStatusButton, CancelSubscriptionButton, EditBusinessButton, OwnerAccessSwitch,
} from "../PlatformClient";

export const metadata: Metadata = { title: "Business" };

interface Detail {
  business: BusinessProfile & { id: string; status: "ACTIVE" | "SUSPENDED"; createdAt: string };
  owners: { id: string; code: string; name: string; email: string; access: boolean; activated: boolean; status: string }[];
  subscription: SubscriptionCurrent;
  subscriptions: SubscriptionRow[];
}

/** One business: its details, sub admins and subscription periods. */
export default async function BusinessPage(props: PageProps<"/platform/[id]">) {
  await requirePage("platform");
  const { id } = await props.params;
  const [d, plans] = await Promise.all([apiData<Detail>(`/api/platform/businesses/${encodeURIComponent(id)}`), apiData<PlanRow[]>("/api/platform/plans")]);
  const b = d.business;
  const s = d.subscription;
  const chip = subscriptionChip(s);
  const today = new Date().toISOString().slice(0, 10);
  const profile: BusinessProfile = {
    name: b.name, legalName: b.legalName, code: b.code, employeePrefix: b.employeePrefix, gstin: b.gstin, address: b.address,
    contactEmail: b.contactEmail, contactPhone: b.contactPhone, timeZone: b.timeZone,
  };

  return (
    <div className="col">
      <PageHeader eyebrow={<><Link href="/platform" className="linkish">Businesses</Link> / {b.code}</>} title={b.name}
        sub={<>Created {fmtDY(b.createdAt.slice(0, 10))} · employee IDs {b.employeePrefix}-001 · {b.timeZone}</>}>
        <EditBusinessButton id={b.id} profile={profile} hasPeople={s.employees > 0} />
        <BusinessStatusButton id={b.id} name={b.name} status={b.status} />
      </PageHeader>

      <div className="kpis enter" style={{ ["--i" as string]: 1 }}>
        <div className="card kpi"><span className="l">Status</span><span><span className={`chip ${b.status === "ACTIVE" ? "ok" : "bad"}`}>{b.status === "ACTIVE" ? "Active" : "Suspended"}</span></span></div>
        <div className="card kpi"><span className="l">Plan</span><span className="v" style={{ fontSize: 24 }}>{s.planName ?? "None"}</span><span className={`chip ${chip.cls}`} style={{ alignSelf: "flex-start" }}>{chip.text}</span></div>
        <div className="card kpi"><span className="l">People</span><span className="v num">{s.employees}{s.maxEmployees ? <span className="muted" style={{ fontSize: 18 }}> / {s.maxEmployees}</span> : null}</span></div>
        <div className="card kpi"><span className="l">Modules</span><span>{s.modules.length ? s.modules.map((m) => <span key={m} className="chip gold" style={{ marginRight: 6 }}>{MODULE_LABELS[m] ?? m}</span>) : <span className="muted">Core HR only</span>}</span></div>
      </div>

      <div className="grid2 enter" style={{ ["--i" as string]: 2, alignItems: "start" }}>
        <section className="card" style={{ padding: 18 }}>
          <div className="card-h"><h3>Sub admins</h3><AddOwnerButton id={b.id} /></div>
          {d.owners.map((o) => (
            <div key={o.id} className="kv" style={{ alignItems: "center" }}>
              <span><b>{o.name}</b> <span className="muted mono" style={{ fontSize: 12 }}>{o.code}</span><br /><small className="muted">{o.email}{o.activated ? "" : " · invite sent"}</small></span>
              <OwnerAccessSwitch businessId={b.id} ownerId={o.id} name={o.name} on={o.access} />
            </div>
          ))}
          {!d.owners.length && <p className="muted">No sub admin yet — add one so someone can run this business.</p>}
        </section>

        <section className="card" style={{ padding: 18 }}>
          <div className="card-h"><h3>Business details</h3></div>
          <div className="kv"><span className="muted">Legal name</span><b>{b.legalName ?? "—"}</b></div>
          <div className="kv"><span className="muted">GSTIN</span><b className="mono">{b.gstin ?? "—"}</b></div>
          <div className="kv"><span className="muted">Contact</span><b>{[b.contactEmail, b.contactPhone].filter(Boolean).join(" · ") || "—"}</b></div>
          <div className="kv"><span className="muted">Address</span><b style={{ textAlign: "right" }}>{b.address ?? "—"}</b></div>
        </section>
      </div>

      <section className="card enter" style={{ ["--i" as string]: 3 }}>
        <div className="card-h" style={{ padding: "16px 18px 0" }}>
          <h3>Subscriptions</h3>
          <AddSubscriptionButton id={b.id} plans={plans} label={s.state === "NONE" ? "Add subscription" : "Renew / add period"} />
        </div>
        <div className="table-wrap">
          <table className="t">
            <thead><tr><th>Plan</th><th>From</th><th>To</th><th className="r">Price</th><th>Status</th><th className="hide-sm">Notes</th><th></th></tr></thead>
            <tbody>
              {d.subscriptions.map((x) => {
                const covers = x.status === "ACTIVE" && x.startsOn <= today && x.endsOn >= today;
                const past = x.endsOn < today;
                return (
                  <tr key={x.id}>
                    <td><b>{x.planName}</b></td>
                    <td>{fmtDY(x.startsOn)}</td>
                    <td>{fmtDY(x.endsOn)}</td>
                    <td className="r num">{inr(x.pricePaise)}</td>
                    <td>{x.status === "CANCELLED" ? <span className="chip neutral">Cancelled</span> : covers ? <span className="chip ok">Current</span> : past ? <span className="chip neutral">Ended</span> : <span className="chip info">Upcoming</span>}</td>
                    <td className="muted hide-sm">{x.notes ?? ""}</td>
                    <td className="r">{x.status === "ACTIVE" && !past && <CancelSubscriptionButton businessId={b.id} subscriptionId={x.id} label={`${x.planName} (${fmtDY(x.startsOn)} – ${fmtDY(x.endsOn)})`} />}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
        {!d.subscriptions.length && <div className="empty"><Icon n="receipt" s={20} /><span className="muted">No subscription yet. Until one starts, people can sign in but can&apos;t use JurisOne.</span></div>}
      </section>
    </div>
  );
}
