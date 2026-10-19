import type { Metadata } from "next";
import { PageHeader } from "@/components/PageHeader";
import type { PlanRow } from "@/lib/api-types";
import { requirePage } from "@/lib/auth";
import { apiData } from "@/lib/backend";
import { inr } from "@/lib/format";
import { getT } from "@/lib/i18n/server";
import { MODULE_LABELS } from "@/lib/subscription";
import { PlanButton } from "./PlansClient";

export const metadata: Metadata = { title: "Plans" };

/** The super admin's plan catalogue: what a business can subscribe to. */
export default async function PlansPage() {
  await requirePage("plans");
  const t = await getT();
  const plans = await apiData<PlanRow[]>("/api/platform/plans");

  return (
    <div className="col">
      <PageHeader eyebrow={t("Platform")} title={<>{t("Subscription")} <em>{t("plans")}</em></>} sub={t("Plans you assign to businesses. Changing a plan applies to every business on it; the price charged is kept per subscription.")}>
        <PlanButton />
      </PageHeader>
      <section className="card enter" style={{ ["--i" as string]: 1 }}>
        <div className="table-wrap">
          <table className="t">
            <thead>
              <tr><th>{t("Plan")}</th><th className="r">{t("Price")}</th><th>{t("Period")}</th><th>{t("People")}</th><th>{t("Modules")}</th><th className="r">{t("Businesses")}</th><th>{t("Status")}</th><th></th></tr>
            </thead>
            <tbody>
              {plans.map((p) => (
                <tr key={p.id}>
                  <td><b>{p.name}</b>{p.description && <><br /><small className="muted">{p.description}</small></>}</td>
                  <td className="r num">{inr(p.pricePaise)}</td>
                  <td>{t(p.periodMonths === 1 ? "{n} month" : "{n} months", { n: p.periodMonths })}</td>
                  <td>{p.maxEmployees ? t("Up to {n}", { n: p.maxEmployees }) : t("No limit")}</td>
                  <td>{p.modules.length ? p.modules.map((m) => <span key={m} className="chip gold">{t(MODULE_LABELS[m] ?? m)}</span>) : <span className="muted">{t("Core HR")}</span>}</td>
                  <td className="r num">{p.businesses}</td>
                  <td><span className={`chip ${p.active ? "ok" : "neutral"}`}>{p.active ? t("Offered") : t("Retired")}</span></td>
                  <td className="r"><PlanButton plan={p} /></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {!plans.length && <div className="empty"><b className="display">{t("No plans yet")}</b><span className="muted">{t("Create a plan before giving a business its subscription.")}</span></div>}
      </section>
    </div>
  );
}
