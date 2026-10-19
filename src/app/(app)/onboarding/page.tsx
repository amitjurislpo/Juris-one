import type { Metadata } from "next";
import Link from "next/link";
import { Icon } from "@/components/Icon";
import { PageHeader } from "@/components/PageHeader";
import { getT } from "@/lib/i18n/server";
import { requirePage } from "@/lib/auth";
import { apiData } from "@/lib/backend";
import { fmtDY } from "@/lib/format";
import { DefaultDocumentsButton, StartOnboardingButton } from "./OnboardingClient";
import { ONBOARDING_STATUS } from "./status";

export const metadata: Metadata = { title: "Onboarding" };

interface Row {
  id: string; candidateName: string; personalEmail: string; designation: string | null; department: string | null; joiningDate: string | null;
  status: keyof typeof ONBOARDING_STATUS; updatedAt: string; documents: number; verified: number; employeeCode: string | null;
}

/** HR: candidates being onboarded by link, open ones first. */
export default async function OnboardingPage() {
  await requirePage("onboarding");
  const t = await getT();
  const rows = await apiData<Row[]>("/api/onboardings");

  return (
    <div className="col">
      <PageHeader eyebrow={t("People ops")} title={<>{t("On")}<em>{t("boarding")}</em></>}
        sub={t("Send a new hire a link on their personal email. They fill in their details and upload documents; you verify them and add the employee.")}>
        <DefaultDocumentsButton />
        <StartOnboardingButton />
      </PageHeader>
      <section className="card enter" style={{ ["--i" as string]: 1 }}>
        <div className="table-wrap">
          <table className="t">
            <thead><tr><th>{t("Candidate")}</th><th className="hide-sm">{t("Job")}</th><th>{t("Joining")}</th><th>{t("Documents")}</th><th>{t("Status")}</th><th className="hide-sm">{t("Last change")}</th></tr></thead>
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
                  <td className="num">{t("{verified}/{total} verified", { verified: r.verified, total: r.documents })}</td>
                  <td><span className={`chip ${ONBOARDING_STATUS[r.status].cls}`}>{t(ONBOARDING_STATUS[r.status].text)}</span>{r.employeeCode && <small className="muted mono"> {r.employeeCode}</small>}</td>
                  <td className="muted hide-sm">{fmtDY(r.updatedAt.slice(0, 10))}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {!rows.length && <div className="empty"><b className="display">{t("No onboardings yet")}</b><span className="muted">{t("Start one with “New onboarding”.")}</span></div>}
      </section>
    </div>
  );
}
