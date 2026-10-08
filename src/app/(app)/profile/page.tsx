import type { Metadata } from "next";
import { PageHeader } from "@/components/PageHeader";
import { requirePage } from "@/lib/auth";
import { apiData } from "@/lib/backend";
import { fmtDY } from "@/lib/format";
import { ProfileForm, type MyProfile } from "./ProfileForm";

export const metadata: Metadata = { title: "My profile" };

/** Everyone's own record: job details (read-only), and personal details they keep up to date themselves. */
export default async function ProfilePage() {
  await requirePage("profile");
  const p = await apiData<MyProfile>("/api/profile");
  const ro: [string, string | null][] = [
    ["Employee ID", p.code], ["Work email", p.email], ["Phone", p.phone], ["Designation", p.designation], ["Department", p.department], ["Role", p.role],
    ["Reporting manager", p.manager], ["Date of joining", fmtDY(p.dateOfJoining)], ["Joining office", p.joiningOffice], ["Work location", p.location],
    ["Shift", p.shift], ["PAN", p.pan], ["Aadhaar", p.aadhaar], ["Bank account", p.bank],
  ];

  return (
    <div className="col">
      <PageHeader eyebrow="Account" title={p.firstName} em={[p.middleName, p.lastName].filter(Boolean).join(" ")}
        sub="Keep your personal details up to date. Your name, work email, phone, IDs and bank details are changed by HR." />
      <div className="grid2 enter" style={{ ["--i" as string]: 1, alignItems: "start" }}>
        <section className="card" style={{ padding: 18 }}>
          <div className="card-h"><h3>Job &amp; records</h3><small className="muted">Managed by HR</small></div>
          {ro.map(([l, v]) => <div key={l} className="kv"><span className="muted">{l}</span><b style={{ textAlign: "right" }}>{v || "—"}</b></div>)}
        </section>
        <ProfileForm p={p} />
      </div>
      {p.documents.length > 0 && (
        <section className="card enter" style={{ ["--i" as string]: 2, padding: 18 }}>
          <div className="card-h"><h3>My documents</h3><small className="muted">Verified during onboarding</small></div>
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
