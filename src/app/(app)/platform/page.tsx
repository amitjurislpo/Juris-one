import type { Metadata } from "next";
import { Icon } from "@/components/Icon";
import { PageHeader } from "@/components/PageHeader";
import { requirePage } from "@/lib/auth";
import { apiData } from "@/lib/backend";
import { fmtDY } from "@/lib/format";

export const metadata: Metadata = { title: "Businesses" };

interface BusinessRow { id: string; name: string; code: string; employeePrefix: string; status: "ACTIVE" | "SUSPENDED"; employees: number; owners: number; createdAt: string }

/** The super admin's console: every business on the platform. */
export default async function PlatformPage() {
  await requirePage("platform");
  const rows = await apiData<BusinessRow[]>("/api/platform/businesses");

  return (
    <div className="col">
      <PageHeader eyebrow="Platform" title={<>Busi<em>nesses</em></>} sub="Every business on JurisOne. Each one has its own people, policies, payroll and data." />
      <div className="toolbar enter" style={{ ["--i" as string]: 1 }}>
        <span className="tcount">{rows.length} business{rows.length === 1 ? "" : "es"}</span>
      </div>
      <section className="card enter" style={{ ["--i" as string]: 2 }}>
        <div className="table-wrap">
          <table className="t">
            <thead>
              <tr>
                <th>Business</th>
                <th>Employee IDs</th>
                <th className="r">People</th>
                <th className="r">Sub admins</th>
                <th>Status</th>
                <th>Created on</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r, i) => (
                <tr key={r.id} style={{ animationDelay: `${i * 30}ms` }}>
                  <td>
                    <div className="person">
                      <span className="kpi-ico" style={{ width: 34, height: 34, background: "var(--accentsoft)", color: "var(--accent)" }}><Icon n="building" s={16} /></span>
                      <div><b>{r.name}</b><small>{r.code}</small></div>
                    </div>
                  </td>
                  <td className="mono muted">{r.employeePrefix}-001</td>
                  <td className="r num">{r.employees}</td>
                  <td className="r num">{r.owners}</td>
                  <td><span className={`chip ${r.status === "ACTIVE" ? "ok" : "warn"}`}>{r.status === "ACTIVE" ? "Active" : "Suspended"}</span></td>
                  <td className="muted">{fmtDY(r.createdAt.slice(0, 10))}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {!rows.length && (
          <div className="empty"><b className="display">No businesses yet</b><span className="muted">Businesses and their sub admins are created here.</span></div>
        )}
      </section>
    </div>
  );
}
