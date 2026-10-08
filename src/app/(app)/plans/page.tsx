import type { Metadata } from "next";
import { PageHeader } from "@/components/PageHeader";
import type { PlanRow } from "@/lib/api-types";
import { requirePage } from "@/lib/auth";
import { apiData } from "@/lib/backend";
import { inr } from "@/lib/format";
import { MODULE_LABELS } from "@/lib/subscription";
import { PlanButton } from "./PlansClient";

export const metadata: Metadata = { title: "Plans" };

/** The super admin's plan catalogue: what a business can subscribe to. */
export default async function PlansPage() {
  await requirePage("plans");
  const plans = await apiData<PlanRow[]>("/api/platform/plans");

  return (
    <div className="col">
      <PageHeader eyebrow="Platform" title={<>Subscription <em>plans</em></>} sub="Plans you assign to businesses. Changing a plan applies to every business on it; the price charged is kept per subscription.">
        <PlanButton />
      </PageHeader>
      <section className="card enter" style={{ ["--i" as string]: 1 }}>
        <div className="table-wrap">
          <table className="t">
            <thead>
              <tr><th>Plan</th><th className="r">Price</th><th>Period</th><th>People</th><th>Modules</th><th className="r">Businesses</th><th>Status</th><th></th></tr>
            </thead>
            <tbody>
              {plans.map((p) => (
                <tr key={p.id}>
                  <td><b>{p.name}</b>{p.description && <><br /><small className="muted">{p.description}</small></>}</td>
                  <td className="r num">{inr(p.pricePaise)}</td>
                  <td>{p.periodMonths} month{p.periodMonths === 1 ? "" : "s"}</td>
                  <td>{p.maxEmployees ? `Up to ${p.maxEmployees}` : "No limit"}</td>
                  <td>{p.modules.length ? p.modules.map((m) => <span key={m} className="chip gold">{MODULE_LABELS[m] ?? m}</span>) : <span className="muted">Core HR</span>}</td>
                  <td className="r num">{p.businesses}</td>
                  <td><span className={`chip ${p.active ? "ok" : "neutral"}`}>{p.active ? "Offered" : "Retired"}</span></td>
                  <td className="r"><PlanButton plan={p} /></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {!plans.length && <div className="empty"><b className="display">No plans yet</b><span className="muted">Create a plan before giving a business its subscription.</span></div>}
      </section>
    </div>
  );
}
