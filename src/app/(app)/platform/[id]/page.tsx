import type { Metadata } from "next";
import Link from "next/link";
import { Icon } from "@/components/Icon";
import { PageHeader } from "@/components/PageHeader";
import type { BusinessProfile, ModuleGrant, PlanRow, SubscriptionCurrent, SubscriptionRow } from "@/lib/api-types";
import { requirePage } from "@/lib/auth";
import { apiData } from "@/lib/backend";
import { fmtDY, inr } from "@/lib/format";
import { getT } from "@/lib/i18n/server";
import { MODULE_LABELS, subscriptionChip } from "@/lib/subscription";
import {
  AddOwnerButton, AddSubscriptionButton, BusinessStatusButton, CancelSubscriptionButton, EditBusinessButton, GrantJurisTrackButton, OwnerAccessSwitch,
  RevokeGrantButton,
} from "../PlatformClient";

export const metadata: Metadata = { title: "Business" };

interface Detail {
  business: BusinessProfile & { id: string; status: "ACTIVE" | "SUSPENDED"; createdAt: string };
  owners: { id: string; code: string; name: string; email: string; access: boolean; activated: boolean; status: string }[];
  subscription: SubscriptionCurrent;
  subscriptions: SubscriptionRow[];
  modules: ModuleGrant[];
}

const GRANT_STATE: Record<ModuleGrant["state"], { cls: string; text: string }> = {
  ACTIVE: { cls: "ok", text: "Active" }, UPCOMING: { cls: "info", text: "Upcoming" }, ENDED: { cls: "neutral", text: "Ended" }, REVOKED: { cls: "neutral", text: "Revoked" },
};

/** One business: its details, sub admins and subscription periods. */
export default async function BusinessPage(props: PageProps<"/platform/[id]">) {
  await requirePage("platform");
  const t = await getT();
  const { id } = await props.params;
  const [d, plans] = await Promise.all([apiData<Detail>(`/api/platform/businesses/${encodeURIComponent(id)}`), apiData<PlanRow[]>("/api/platform/plans")]);
  const b = d.business;
  const s = d.subscription;
  const chip = subscriptionChip(s);
  const chipText = (x: SubscriptionCurrent) => !x || x.state === "NONE" ? t("No plan") : x.state === "UPCOMING" ? t("Starts {date}", { date: fmtDY(x.startsOn!) }) : x.state === "EXPIRED" ? t("Ended {date}", { date: fmtDY(x.endsOn!) }) : x.daysLeft !== null && x.daysLeft <= 7 ? (x.daysLeft === 0 ? t("Ends today") : t(x.daysLeft === 1 ? "{n} day left" : "{n} days left", { n: x.daysLeft })) : t("Until {date}", { date: fmtDY(x.endsOn!) });
  const planHasTrack = !!s.planName && (plans.find((p) => p.name === s.planName)?.modules.includes("JURIS_TRACK") ?? false);
  const today = new Date().toISOString().slice(0, 10);
  const profile: BusinessProfile = {
    name: b.name, legalName: b.legalName, code: b.code, employeePrefix: b.employeePrefix, gstin: b.gstin, address: b.address,
    contactEmail: b.contactEmail, contactPhone: b.contactPhone, timeZone: b.timeZone,
  };

  return (
    <div className="col">
      <PageHeader eyebrow={<><Link href="/platform" className="linkish">{t("Businesses")}</Link> / {b.code}</>} title={b.name}
        sub={<>{t("Created {date} · employee IDs {prefix}-001 · {tz}", { date: fmtDY(b.createdAt.slice(0, 10)), prefix: b.employeePrefix, tz: b.timeZone })}</>}>
        <EditBusinessButton id={b.id} profile={profile} hasPeople={s.employees > 0} />
        <BusinessStatusButton id={b.id} name={b.name} status={b.status} />
      </PageHeader>

      <div className="kpis enter" style={{ ["--i" as string]: 1 }}>
        <div className="card kpi"><span className="l">{t("Status")}</span><span><span className={`chip ${b.status === "ACTIVE" ? "ok" : "bad"}`}>{b.status === "ACTIVE" ? t("Active") : t("Suspended")}</span></span></div>
        <div className="card kpi"><span className="l">{t("Plan")}</span><span className="v" style={{ fontSize: 24 }}>{s.planName ?? t("None")}</span><span className={`chip ${chip.cls}`} style={{ alignSelf: "flex-start" }}>{chipText(s)}</span></div>
        <div className="card kpi"><span className="l">{t("People")}</span><span className="v num">{s.employees}{s.maxEmployees ? <span className="muted" style={{ fontSize: 18 }}> / {s.maxEmployees}</span> : null}</span></div>
        <div className="card kpi"><span className="l">{t("Modules")}</span><span>{s.modules.length ? s.modules.map((m) => <span key={m} className="chip gold" style={{ marginRight: 6 }}>{t(MODULE_LABELS[m] ?? m)}</span>) : <span className="muted">{t("Core HR only")}</span>}</span></div>
      </div>

      <div className="grid2 enter" style={{ ["--i" as string]: 2, alignItems: "start" }}>
        <section className="card" style={{ padding: 18 }}>
          <div className="card-h"><h3>{t("Sub admins")}</h3><AddOwnerButton id={b.id} /></div>
          {d.owners.map((o) => (
            <div key={o.id} className="kv" style={{ alignItems: "center" }}>
              <span><b>{o.name}</b> <span className="muted mono" style={{ fontSize: 12 }}>{o.code}</span><br /><small className="muted">{o.email}{o.activated ? "" : ` · ${t("invite sent")}`}</small></span>
              <OwnerAccessSwitch businessId={b.id} ownerId={o.id} name={o.name} on={o.access} />
            </div>
          ))}
          {!d.owners.length && <p className="muted">{t("No sub admin yet — add one so someone can run this business.")}</p>}
        </section>

        <section className="card" style={{ padding: 18 }}>
          <div className="card-h"><h3>{t("Business details")}</h3></div>
          <div className="kv"><span className="muted">{t("Legal name")}</span><b>{b.legalName ?? "—"}</b></div>
          <div className="kv"><span className="muted">{t("GSTIN")}</span><b className="mono">{b.gstin ?? "—"}</b></div>
          <div className="kv"><span className="muted">{t("Contact")}</span><b>{[b.contactEmail, b.contactPhone].filter(Boolean).join(" · ") || "—"}</b></div>
          <div className="kv"><span className="muted">{t("Address")}</span><b style={{ textAlign: "right" }}>{b.address ?? "—"}</b></div>
        </section>
      </div>

      <section className="card enter" style={{ ["--i" as string]: 3 }}>
        <div className="card-h" style={{ padding: "16px 18px 0" }}>
          <div>
            <h3>{t("Juris Track")}</h3>
            <small className="muted">{planHasTrack ? t("Included in the {plan} plan. You can also grant it on its own.", { plan: s.planName! }) : t("Not in the plan. Grant it on its own: complimentary or paid.")}</small>
          </div>
          <GrantJurisTrackButton id={b.id} />
        </div>
        {d.modules.length > 0 ? (
          <div className="table-wrap">
            <table className="t">
              <thead><tr><th>{t("Type")}</th><th>{t("From")}</th><th>{t("To")}</th><th className="r">{t("Price")}</th><th>{t("Status")}</th><th className="hide-sm">{t("Notes")}</th><th></th></tr></thead>
              <tbody>
                {d.modules.map((g) => (
                  <tr key={g.id}>
                    <td><b>{g.source === "PAID" ? t("Paid") : t("Complimentary")}</b></td>
                    <td>{fmtDY(g.startsOn)}</td>
                    <td>{g.endsOn ? fmtDY(g.endsOn) : <span className="muted">{t("No end")}</span>}</td>
                    <td className="r num">{g.source === "PAID" ? inr(g.pricePaise) : t("Free")}</td>
                    <td><span className={`chip ${GRANT_STATE[g.state].cls}`}>{t(GRANT_STATE[g.state].text)}</span></td>
                    <td className="muted hide-sm">{g.notes ?? ""}</td>
                    <td className="r">{(g.state === "ACTIVE" || g.state === "UPCOMING") && <RevokeGrantButton businessId={b.id} grantId={g.id} label={t(g.source === "PAID" ? "paid Juris Track from {date}" : "complimentary Juris Track from {date}", { date: fmtDY(g.startsOn) })} />}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : <p className="muted" style={{ padding: "0 18px 16px", margin: 0 }}>{t("No separate grants.")}</p>}
      </section>

      <section className="card enter" style={{ ["--i" as string]: 4 }}>
        <div className="card-h" style={{ padding: "16px 18px 0" }}>
          <h3>{t("Subscriptions")}</h3>
          <AddSubscriptionButton id={b.id} plans={plans} label={s.state === "NONE" ? t("Add subscription") : t("Renew / add period")} />
        </div>
        <div className="table-wrap">
          <table className="t">
            <thead><tr><th>{t("Plan")}</th><th>{t("From")}</th><th>{t("To")}</th><th className="r">{t("Price")}</th><th>{t("Status")}</th><th className="hide-sm">{t("Notes")}</th><th></th></tr></thead>
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
                    <td>{x.status === "CANCELLED" ? <span className="chip neutral">{t("Cancelled")}</span> : covers ? <span className="chip ok">{t("Current")}</span> : past ? <span className="chip neutral">{t("Ended")}</span> : <span className="chip info">{t("Upcoming")}</span>}</td>
                    <td className="muted hide-sm">{x.notes ?? ""}</td>
                    <td className="r">{x.status === "ACTIVE" && !past && <CancelSubscriptionButton businessId={b.id} subscriptionId={x.id} label={t("{plan} ({from} – {to})", { plan: x.planName, from: fmtDY(x.startsOn), to: fmtDY(x.endsOn) })} />}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
        {!d.subscriptions.length && <div className="empty"><Icon n="receipt" s={20} /><span className="muted">{t("No subscription yet. Until one starts, people can sign in but can't use JurisOne.")}</span></div>}
      </section>
    </div>
  );
}
