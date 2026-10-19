import type { Metadata } from "next";
import { PageHeader } from "@/components/PageHeader";
import { requirePage } from "@/lib/auth";
import { apiData } from "@/lib/backend";
import { fmtDY } from "@/lib/format";
import { getT } from "@/lib/i18n/server";
import { ProfileForm, type MyProfile } from "./ProfileForm";

export const metadata: Metadata = { title: "My profile" };

/** Everyone's own record: job details (read-only), and personal details they keep up to date themselves. */
export default async function ProfilePage() {
  await requirePage("profile");
  const t = await getT();
  const p = await apiData<MyProfile>("/api/profile");
  const ro: [string, string | null][] = [
    [t("Employee ID"), p.code], [t("Work email"), p.email], [t("Phone"), p.phone], [t("Designation"), p.designation], [t("Department"), p.department], [t("Role"), p.role],
    [t("Reporting manager"), p.manager], [t("Date of joining"), fmtDY(p.dateOfJoining)], [t("Joining office"), p.joiningOffice], [t("Work location"), p.location],
    [t("Shift"), p.shift], [t("PAN"), p.pan], [t("Aadhaar"), p.aadhaar], [t("Bank account"), p.bank],
  ];

  return (
    <div className="col">
      <PageHeader eyebrow={t("Account")} title={p.firstName} em={[p.middleName, p.lastName].filter(Boolean).join(" ")}
        sub={t("Keep your personal details up to date. Your name, work email, phone, IDs and bank details are changed by HR.")} />
      <div className="grid2 enter" style={{ ["--i" as string]: 1, alignItems: "start" }}>
        <section className="card" style={{ padding: 18 }}>
          <div className="card-h"><h3>{t("Job & records")}</h3><small className="muted">{t("Managed by HR")}</small></div>
          {ro.map(([l, v]) => <div key={l} className="kv"><span className="muted">{l}</span><b style={{ textAlign: "right" }}>{v || "—"}</b></div>)}
        </section>
        <ProfileForm p={p} />
      </div>
      {p.documents.length > 0 && (
        <section className="card enter" style={{ ["--i" as string]: 2, padding: 18 }}>
          <div className="card-h"><h3>{t("My documents")}</h3><small className="muted">{t("Verified during onboarding")}</small></div>
          {p.documents.map((d) => (
            <div key={d.id} className="kv">
              <span>{d.label}</span>
              <a className="linkish" href={`/api/profile/documents/${d.id}/file`} target="_blank" rel="noopener">{d.fileName}</a>
            </div>
          ))}
        </section>
      )}
    </div>
  );
}
