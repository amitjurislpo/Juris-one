import type { Metadata } from "next";
import Link from "next/link";
import { Icon } from "@/components/Icon";
import { PageHeader } from "@/components/PageHeader";
import { requirePage } from "@/lib/auth";
import { apiData } from "@/lib/backend";
import { fmtDY } from "@/lib/format";
import { StartOnboardingButton } from "./OnboardingClient";
import { ONBOARDING_STATUS } from "./status";

export const metadata: Metadata = { title: "Onboarding" };

interface Row {
  id: string; candidateName: string; personalEmail: string; designation: string | null; department: string | null; joiningDate: string | null;
  status: keyof typeof ONBOARDING_STATUS; updatedAt: string; documents: number; verified: number; employeeCode: string | null;
}

/** HR: candidates being onboarded by link, open ones first. */
export default async function OnboardingPage() {
  await requirePage("onboarding");
  const rows = await apiData<Row[]>("/api/onboardings");

  return (
    <div className="col">
      <PageHeader eyebrow="People ops" title={<>On<em>boarding</em></>}
        sub="Send a new hire a link on their personal email. They fill in their details and upload documents; you verify them and add the employee.">
        <StartOnboardingButton />
      </PageHeader>
      <section className="card enter" style={{ ["--i" as string]: 1 }}>
        <div className="table-wrap">
          <table className="t">
            <thead><tr><th>Candidate</th><th className="hide-sm">Job</th><th>Joining</th><th>Documents</th><th>Status</th><th className="hide-sm">Last change</th></tr></thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.id}>
                  <td>
                    <Link href={`/onboarding/${r.id}`} className="person" style={{ textDecoration: "none", color: "inherit" }}>
                      <span className="kpi-ico" style={{ width: 34, height: 34, background: "var(--accentsoft)", color: "var(--accent)" }}><Icon n="user" s={16} /></span>
                      <div><b>{r.candidateName}</b><small>{r.personalEmail}</small></div>
                    </Link>
                  </td>
                  <td className="hide-sm">{r.designation ?? "—"}<br /><small className="muted">{r.department ?? ""}</small></td>
                  <td>{r.joiningDate ? fmtDY(r.joiningDate) : <span className="muted">—</span>}</td>
                  <td className="num">{r.verified}/{r.documents} verified</td>
                  <td><span className={`chip ${ONBOARDING_STATUS[r.status].cls}`}>{ONBOARDING_STATUS[r.status].text}</span>{r.employeeCode && <small className="muted mono"> {r.employeeCode}</small>}</td>
                  <td className="muted hide-sm">{fmtDY(r.updatedAt.slice(0, 10))}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {!rows.length && <div className="empty"><b className="display">No onboardings yet</b><span className="muted">Start one with “New onboarding”.</span></div>}
      </section>
    </div>
  );
}
