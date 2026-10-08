import type { Metadata } from "next";
import Link from "next/link";
import { PageHeader } from "@/components/PageHeader";
import { requirePage } from "@/lib/auth";
import { apiData } from "@/lib/backend";
import { fmtDateTime, fmtDY } from "@/lib/format";
import { AddEmployeeButton, DocumentDecision, EditPlanButton, OnboardingActions } from "../OnboardingClient";
import { ONBOARDING_STATUS, type OnboardingStatus } from "../status";

export const metadata: Metadata = { title: "Onboarding" };

interface Detail {
  id: string; candidateName: string; personalEmail: string; designation: string | null; departmentId: string | null; roleId: string | null; managerId: string | null;
  joiningDate: string | null; location: string; requiredDocs: string[]; status: OnboardingStatus; hrComment: string | null; linkExpiresAt: string | null;
  submittedAt: string | null; verifiedAt: string | null; employeeId: string | null; employeeCode: string | null;
  form: Record<string, string | null>;
  documents: { id: string; kind: string; label: string; fileName: string; mime: string; size: number; status: "PENDING" | "VERIFIED" | "REJECTED"; comment: string | null;
    uploadedAt: string; verifiedBy: string | null }[];
  checklist: { kind: string; label: string; needed: number; uploaded: number; verified: number }[];
}

const DOC = { PENDING: { cls: "warn", text: "To check" }, VERIFIED: { cls: "ok", text: "Verified" }, REJECTED: { cls: "bad", text: "Rejected" } } as const;

const FIELDS: [string, string][] = [
  ["fullName", "Full name"], ["fatherName", "Father's name"], ["dateOfBirth", "Date of birth"], ["gender", "Gender"], ["phone", "Mobile"],
  ["currentAddress", "Current address"], ["permanentAddress", "Permanent address"], ["pan", "PAN"], ["aadhaar", "Aadhaar"], ["uan", "UAN"],
  ["bankName", "Bank"], ["bankAccount", "Account"], ["ifsc", "IFSC"], ["previousEmployer", "Previous employer"], ["previousDesignation", "Previous designation"],
  ["emergencyName", "Emergency contact"], ["emergencyRelation", "Relationship"], ["emergencyPhone", "Emergency phone"],
];

/** HR's review of one onboarding: the candidate's answers (identity numbers masked), documents, decisions and "Add employee". */
export default async function OnboardingReviewPage(props: PageProps<"/onboarding/[id]">) {
  const u = await requirePage("onboarding");
  const { id } = await props.params;
  const d = await apiData<Detail>(`/api/onboardings/${encodeURIComponent(id)}`);
  const st = ONBOARDING_STATUS[d.status];
  const plan = { candidateName: d.candidateName, personalEmail: d.personalEmail, designation: d.designation ?? "", departmentId: d.departmentId ?? "",
    roleId: d.roleId ?? "", managerId: d.managerId ?? "", joiningDate: d.joiningDate ?? "", location: d.location, requiredDocs: d.requiredDocs };
  const decidable = ["SUBMITTED", "IN_PROGRESS", "CHANGES_REQUESTED", "INVITED"].includes(d.status);
  const closed = d.status === "ADDED" || d.status === "CANCELLED";

  return (
    <div className="col">
      <PageHeader eyebrow={<><Link href="/onboarding" className="linkish">Onboarding</Link> / {d.personalEmail}</>} title={d.candidateName}
        sub={<><span className={`chip ${st.cls}`}>{st.text}</span>{" "}
          {d.designation ?? "No designation yet"}{d.joiningDate ? ` · joining ${fmtDY(d.joiningDate)}` : ""}
          {d.linkExpiresAt && !closed ? ` · link valid until ${fmtDY(d.linkExpiresAt.slice(0, 10))}` : ""}
          {d.employeeCode && d.employeeId ? <> · added as <Link className="linkish" href={`/employees?q=${d.employeeCode}`}>{d.employeeCode}</Link> — turn on portal access in Employees to send their invite</> : null}</>}>
        {!closed && <EditPlanButton id={d.id} plan={plan} />}
        <OnboardingActions id={d.id} status={d.status} name={d.candidateName} />
        {d.status === "VERIFIED" && <AddEmployeeButton id={d.id} name={d.candidateName} plan={plan} />}
      </PageHeader>

      {d.hrComment && d.status === "CHANGES_REQUESTED" && (
        <div className="card enter" style={{ padding: 14, background: "var(--warnbg)", borderColor: "transparent" }}><b>Sent back:</b> {d.hrComment}</div>
      )}

      <div className="grid2 enter" style={{ ["--i" as string]: 1, alignItems: "start" }}>
        <section className="card" style={{ padding: 18 }}>
          <div className="card-h"><h3>Candidate&apos;s details</h3><small className="muted">{d.submittedAt ? `Submitted ${fmtDateTime(new Date(d.submittedAt), u.timeZone)}` : "Not submitted yet"}</small></div>
          {FIELDS.map(([k, l]) => (
            <div key={k} className="kv"><span className="muted">{l}</span><b style={{ textAlign: "right", whiteSpace: "pre-wrap" }}>{d.form[k] || "—"}</b></div>
          ))}
          <p className="faint" style={{ fontSize: 12, margin: "10px 0 0" }}>Identity and bank numbers are shown masked; check them against the documents.</p>
        </section>

        <section className="card" style={{ padding: 18 }}>
          <div className="card-h"><h3>Required documents</h3></div>
          {d.checklist.map((c) => (
            <div key={c.kind} className="kv">
              <span>{c.label}</span>
              <span className={`chip ${c.verified >= c.needed ? "ok" : c.uploaded >= c.needed ? "warn" : "neutral"}`}>{c.verified}/{c.needed} verified{c.uploaded > c.verified ? ` · ${c.uploaded - c.verified} to check` : ""}</span>
            </div>
          ))}
          {!d.checklist.length && <p className="muted">No documents required.</p>}
        </section>
      </div>

      <section className="card enter" style={{ ["--i" as string]: 2 }}>
        <div className="card-h" style={{ padding: "16px 18px 0" }}><h3>Uploaded documents</h3></div>
        <div className="table-wrap">
          <table className="t">
            <thead><tr><th>Document</th><th>File</th><th>Status</th><th className="hide-sm">Uploaded</th><th></th></tr></thead>
            <tbody>
              {d.documents.map((x) => (
                <tr key={x.id}>
                  <td><b>{x.label}</b></td>
                  <td><a className="linkish" href={`/api/onboardings/${d.id}/documents/${x.id}/file`} target="_blank" rel="noopener">{x.fileName}</a> <small className="muted">{Math.max(1, Math.round(x.size / 1024))} KB</small></td>
                  <td><span className={`chip ${DOC[x.status].cls}`}>{DOC[x.status].text}</span>{x.comment && <><br /><small className="muted">{x.comment}</small></>}{x.verifiedBy && <><br /><small className="faint">by {x.verifiedBy}</small></>}</td>
                  <td className="muted hide-sm">{fmtDateTime(new Date(x.uploadedAt), u.timeZone)}</td>
                  <td className="r">{decidable && <DocumentDecision id={d.id} docId={x.id} name={x.fileName} status={x.status} />}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {!d.documents.length && <div className="empty"><span className="muted">Nothing uploaded yet.</span></div>}
      </section>
    </div>
  );
}
