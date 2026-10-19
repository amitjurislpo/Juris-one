import type { Metadata } from "next";
import Link from "next/link";
import { Icon } from "@/components/Icon";
import { PageHeader } from "@/components/PageHeader";
import type { PlanRow, SubscriptionCurrent } from "@/lib/api-types";
import { requirePage } from "@/lib/auth";
import { apiData } from "@/lib/backend";
import { fmtDY } from "@/lib/format";
import { getT } from "@/lib/i18n/server";
import { subscriptionChip } from "@/lib/subscription";
import { NewBusinessButton } from "./PlatformClient";

export const metadata: Metadata = { title: "Businesses" };

interface BusinessRow {
  id: string; name: string; code: string; employeePrefix: string; status: "ACTIVE" | "SUSPENDED"; employees: number; owners: number; createdAt: string;
  subscription: SubscriptionCurrent;
}

/** The super admin's console: every business on the platform. */
export default async function PlatformPage() {
  await requirePage("platform");
  const t = await getT();
  const chipText = (x: SubscriptionCurrent) => !x || x.state === "NONE" ? t("No plan") : x.state === "UPCOMING" ? t("Starts {date}", { date: fmtDY(x.startsOn!) }) : x.state === "EXPIRED" ? t("Ended {date}", { date: fmtDY(x.endsOn!) }) : x.daysLeft !== null && x.daysLeft <= 7 ? (x.daysLeft === 0 ? t("Ends today") : t(x.daysLeft === 1 ? "{n} day left" : "{n} days left", { n: x.daysLeft })) : t("Until {date}", { date: fmtDY(x.endsOn!) });
  const [rows, plans] = await Promise.all([apiData<BusinessRow[]>("/api/platform/businesses"), apiData<PlanRow[]>("/api/platform/plans")]);
  const ending = rows.filter((r) => r.subscription.state === "ACTIVE" && (r.subscription.daysLeft ?? 99) <= 7).length;
  const lapsed = rows.filter((r) => !r.subscription.active).length;

  return (
    <div className="col">
      <PageHeader eyebrow={t("Platform")} title={<>{t("Businesses")}</>} sub={t("Every business on JurisOne. Each one has its own people, policies, payroll and data.")}>
        <NewBusinessButton plans={plans} />
      </PageHeader>
      <div className="kpis enter" style={{ ["--i" as string]: 1 }}>
        <div className="card kpi"><span className="l">{t("Businesses")}</span><span className="v num">{rows.length}</span></div>
        <div className="card kpi"><span className="l">{t("People on the platform")}</span><span className="v num">{rows.reduce((n, r) => n + r.employees, 0)}</span></div>
        <div className="card kpi"><span className="l">{t("Ending within 7 days")}</span><span className="v num">{ending}</span></div>
        <div className="card kpi"><span className="l">{t("Without an active plan")}</span><span className="v num">{lapsed}</span></div>
      </div>
      <section className="card enter" style={{ ["--i" as string]: 2 }}>
        <div className="table-wrap">
          <table className="t">
            <thead>
              <tr>
                <th>{t("Business")}</th>
                <th>{t("Plan")}</th>
                <th>{t("Subscription")}</th>
                <th className="r">{t("People")}</th>
                <th className="r hide-sm">{t("Sub admins")}</th>
                <th>{t("Status")}</th>
                <th className="hide-sm">{t("Created on")}</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r, i) => {
                const chip = subscriptionChip(r.subscription);
                return (
                  <tr key={r.id} style={{ animationDelay: `${i * 30}ms` }}>
                    <td>
                      <Link href={`/platform/${r.id}`} className="person" style={{ textDecoration: "none", color: "inherit" }}>
                        <span className="kpi-ico" style={{ width: 34, height: 34, background: "var(--accentsoft)", color: "var(--accent)" }}><Icon n="building" s={16} /></span>
                        <div><b>{r.name}</b><small>{t("{code} · IDs {prefix}-001", { code: r.code, prefix: r.employeePrefix })}</small></div>
                      </Link>
                    </td>
                    <td>{r.subscription.planName ?? <span className="muted">—</span>}</td>
                    <td><span className={`chip ${chip.cls}`}>{chipText(r.subscription)}</span></td>
                    <td className="r num">{r.employees}{r.subscription.maxEmployees ? <span className="muted"> / {r.subscription.maxEmployees}</span> : null}</td>
                    <td className="r num hide-sm">{r.owners}</td>
                    <td><span className={`chip ${r.status === "ACTIVE" ? "ok" : "bad"}`}>{r.status === "ACTIVE" ? t("Active") : t("Suspended")}</span></td>
                    <td className="muted hide-sm">{fmtDY(r.createdAt.slice(0, 10))}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
        {!rows.length && (
          <div className="empty"><b className="display">{t("No businesses yet")}</b><span className="muted">{t("Create a business and its sub admin with “New business”.")}</span></div>
        )}
      </section>
    </div>
  );
}
