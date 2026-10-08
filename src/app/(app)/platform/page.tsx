import type { Metadata } from "next";
import Link from "next/link";
import { Icon } from "@/components/Icon";
import { PageHeader } from "@/components/PageHeader";
import type { PlanRow, SubscriptionCurrent } from "@/lib/api-types";
import { requirePage } from "@/lib/auth";
import { apiData } from "@/lib/backend";
import { fmtDY } from "@/lib/format";
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
  const [rows, plans] = await Promise.all([apiData<BusinessRow[]>("/api/platform/businesses"), apiData<PlanRow[]>("/api/platform/plans")]);
  const ending = rows.filter((r) => r.subscription.state === "ACTIVE" && (r.subscription.daysLeft ?? 99) <= 7).length;
  const lapsed = rows.filter((r) => !r.subscription.active).length;

  return (
    <div className="col">
      <PageHeader eyebrow="Platform" title={<>Busi<em>nesses</em></>} sub="Every business on JurisOne. Each one has its own people, policies, payroll and data.">
        <NewBusinessButton plans={plans} />
      </PageHeader>
      <div className="kpis enter" style={{ ["--i" as string]: 1 }}>
        <div className="card kpi"><span className="l">Businesses</span><span className="v num">{rows.length}</span></div>
        <div className="card kpi"><span className="l">People on the platform</span><span className="v num">{rows.reduce((n, r) => n + r.employees, 0)}</span></div>
        <div className="card kpi"><span className="l">Ending within 7 days</span><span className="v num">{ending}</span></div>
        <div className="card kpi"><span className="l">Without an active plan</span><span className="v num">{lapsed}</span></div>
      </div>
      <section className="card enter" style={{ ["--i" as string]: 2 }}>
        <div className="table-wrap">
          <table className="t">
            <thead>
              <tr>
                <th>Business</th>
                <th>Plan</th>
                <th>Subscription</th>
                <th className="r">People</th>
                <th className="r hide-sm">Sub admins</th>
                <th>Status</th>
                <th className="hide-sm">Created on</th>
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
                        <div><b>{r.name}</b><small>{r.code} · IDs {r.employeePrefix}-001</small></div>
                      </Link>
                    </td>
                    <td>{r.subscription.planName ?? <span className="muted">—</span>}</td>
                    <td><span className={`chip ${chip.cls}`}>{chip.text}</span></td>
                    <td className="r num">{r.employees}{r.subscription.maxEmployees ? <span className="muted"> / {r.subscription.maxEmployees}</span> : null}</td>
                    <td className="r num hide-sm">{r.owners}</td>
                    <td><span className={`chip ${r.status === "ACTIVE" ? "ok" : "bad"}`}>{r.status === "ACTIVE" ? "Active" : "Suspended"}</span></td>
                    <td className="muted hide-sm">{fmtDY(r.createdAt.slice(0, 10))}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
        {!rows.length && (
          <div className="empty"><b className="display">No businesses yet</b><span className="muted">Create a business and its sub admin with “New business”.</span></div>
        )}
      </section>
    </div>
  );
}
