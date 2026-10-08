import type { Metadata } from "next";
import { PageHeader } from "@/components/PageHeader";
import type { BusinessProfile, SubscriptionCurrent, SubscriptionRow } from "@/lib/api-types";
import { requirePage } from "@/lib/auth";
import { apiData } from "@/lib/backend";
import { fmtDY, inr } from "@/lib/format";
import { MODULE_LABELS, subscriptionChip } from "@/lib/subscription";
import { ExportButtons } from "./ExportButtons";

export const metadata: Metadata = { title: "Subscription" };

interface Data { business: BusinessProfile; subscription: SubscriptionCurrent; history: SubscriptionRow[] }

/** The business owner's plan, its dates and the employee export (available even after the subscription ends). */
export default async function SubscriptionPage() {
  await requirePage("subscription");
  const d = await apiData<Data>("/api/business/subscription");
  const s = d.subscription;
  const chip = subscriptionChip(s);
  const today = new Date().toISOString().slice(0, 10);

  return (
    <div className="col">
      <PageHeader eyebrow="Administration" title={<>Subscrip<em>tion</em></>} sub={`${d.business.name}'s JurisOne plan. Plans are renewed by the JurisOne team.`}>
        <ExportButtons />
      </PageHeader>

      <div className="kpis enter" style={{ ["--i" as string]: 1 }}>
        <div className="card kpi"><span className="l">Plan</span><span className="v" style={{ fontSize: 26 }}>{s.planName ?? "None"}</span><span className={`chip ${chip.cls}`} style={{ alignSelf: "flex-start" }}>{chip.text}</span></div>
        <div className="card kpi"><span className="l">Valid until</span><span className="v" style={{ fontSize: 26 }}>{s.endsOn ? fmtDY(s.endsOn) : "—"}</span><span className="s">{s.startsOn ? `from ${fmtDY(s.startsOn)}` : ""}</span></div>
        <div className="card kpi"><span className="l">People</span><span className="v num">{s.employees}</span><span className="s">{s.maxEmployees ? `of ${s.maxEmployees} on this plan` : "no limit on this plan"}</span></div>
        <div className="card kpi"><span className="l">Modules</span><span>{s.modules.length ? s.modules.map((m) => <span key={m} className="chip gold">{MODULE_LABELS[m] ?? m}</span>) : <span className="muted">Core HR</span>}</span></div>
      </div>

      {!s.active && (
        <div className="card enter" style={{ ["--i" as string]: 2, padding: 18, borderColor: "var(--bad)" }} role="alert">
          <b>{s.state === "UPCOMING" ? `Your subscription starts on ${fmtDY(s.startsOn!)}.` : "Your subscription has ended."}</b>
          <p className="muted" style={{ margin: "6px 0 0" }}>Until it is active, everyone can sign in but JurisOne is paused for your business. You can still export your employees. Contact the JurisOne team to renew.</p>
        </div>
      )}

      <section className="card enter" style={{ ["--i" as string]: 3 }}>
        <div className="card-h" style={{ padding: "16px 18px 0" }}><h3>History</h3></div>
        <div className="table-wrap">
          <table className="t">
            <thead><tr><th>Plan</th><th>From</th><th>To</th><th className="r">Price</th><th>Status</th></tr></thead>
            <tbody>
              {d.history.map((x) => (
                <tr key={x.id}>
                  <td><b>{x.planName}</b></td>
                  <td>{fmtDY(x.startsOn)}</td>
                  <td>{fmtDY(x.endsOn)}</td>
                  <td className="r num">{inr(x.pricePaise)}</td>
                  <td>{x.status === "CANCELLED" ? <span className="chip neutral">Cancelled</span> : x.endsOn < today ? <span className="chip neutral">Ended</span> : x.startsOn > today ? <span className="chip info">Upcoming</span> : <span className="chip ok">Current</span>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {!d.history.length && <div className="empty"><span className="muted">No subscription yet.</span></div>}
      </section>
    </div>
  );
}
