import type { Metadata } from "next";
import Link from "next/link";
import { PageHeader } from "@/components/PageHeader";
import type { PolicyDetail } from "@/lib/api-types";
import { requirePage } from "@/lib/auth";
import { apiData } from "@/lib/backend";
import { fmtDateTime, fmtDY } from "@/lib/format";
import { PolicyActions, PolicyEditor } from "../PoliciesClient";

export const metadata: Metadata = { title: "Policy" };

/** One policy: edit the draft, publish / archive, versions, and who has accepted the current version. */
export default async function PolicyPage(props: PageProps<"/policies/[id]">) {
  const u = await requirePage("policies");
  const { id } = await props.params;
  const p = await apiData<PolicyDetail>(`/api/policies/${encodeURIComponent(id)}`);
  const accepted = p.people.filter((x) => x.acknowledgedAt).length;

  return (
    <div className="col">
      <PageHeader eyebrow={<><Link href="/policies" className="linkish">Company policies</Link> / {p.version ? `version ${p.version}` : "draft"}</>} title={p.title}
        sub={p.status === "PUBLISHED" ? (p.hasChanges ? "Published — the draft below has changes that aren't published yet." : "Published. Edit the draft and publish again to make a new version.")
          : p.status === "ARCHIVED" ? "Archived — employees no longer see it. Publish it again to bring it back." : "Draft — nobody sees it until you publish it."}>
        <PolicyActions p={p} />
      </PageHeader>

      <PolicyEditor p={p} />

      {p.version > 0 && p.status === "PUBLISHED" && p.requiresAck && (
        <section className="card enter" style={{ ["--i" as string]: 3 }}>
          <div className="card-h" style={{ padding: "16px 18px 0" }}>
            <h3>Version {p.version}: {accepted} of {p.people.length} accepted</h3>
          </div>
          <div className="table-wrap">
            <table className="t">
              <thead><tr><th>Employee</th><th className="hide-sm">Department</th><th>Accepted</th></tr></thead>
              <tbody>
                {p.people.map((x) => (
                  <tr key={x.code}>
                    <td><b>{x.name}</b> <span className="muted mono" style={{ fontSize: 12 }}>{x.code}</span></td>
                    <td className="muted hide-sm">{x.department ?? "—"}</td>
                    <td>{x.acknowledgedAt ? <span className="chip ok">{fmtDateTime(new Date(x.acknowledgedAt), u.timeZone)}</span>
                      : <span className="chip warn">Not yet{x.lastVersion ? ` (accepted v${x.lastVersion})` : ""}</span>}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      )}

      {p.versions.length > 0 && (
        <section className="card enter" style={{ ["--i" as string]: 4 }}>
          <div className="card-h" style={{ padding: "16px 18px 0" }}><h3>Published versions</h3><small className="muted">A published version can never be changed.</small></div>
          <div className="table-wrap">
            <table className="t">
              <thead><tr><th>Version</th><th>Published</th><th className="hide-sm">By</th><th className="r">Accepted by</th><th className="hide-sm">Text fingerprint (SHA-256)</th><th></th></tr></thead>
              <tbody>
                {p.versions.map((v) => (
                  <tr key={v.versionId}>
                    <td><b>v{v.version}</b> <span className="muted">{v.title !== p.title ? v.title : ""}</span></td>
                    <td>{fmtDY(v.publishedAt.slice(0, 10))}</td>
                    <td className="muted hide-sm">{v.publishedBy ?? "—"}</td>
                    <td className="r num">{v.requiresAck ? v.acknowledgements : "—"}</td>
                    <td className="mono muted hide-sm" style={{ fontSize: 11.5 }} title={v.bodySha256}>{v.bodySha256.slice(0, 16)}…</td>
                    <td className="r">{v.fileName && <a className="linkish" href={`/api/policies/${p.id}/versions/${v.version}/file`} target="_blank" rel="noopener">PDF</a>}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      )}
    </div>
  );
}
