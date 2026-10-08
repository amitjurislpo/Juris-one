import type { Metadata } from "next";
import { Icon } from "@/components/Icon";
import { PageHeader } from "@/components/PageHeader";
import type { PolicyToRead } from "@/lib/api-types";
import { requirePage } from "@/lib/auth";
import { apiData } from "@/lib/backend";
import { fmtDY } from "@/lib/format";
import { PolicyText } from "./PolicyText";

export const metadata: Metadata = { title: "My policies" };

/** Every current company policy, and when the signed-in user accepted it. */
export default async function MyPoliciesPage() {
  await requirePage("my-policies");
  const rows = await apiData<PolicyToRead[]>("/api/policies/mine");

  return (
    <div className="col">
      <PageHeader eyebrow="Company" title={<>My <em>policies</em></>} sub="The policies that apply to you. When a policy changes you'll be asked to read and accept the new version." />
      {rows.map((p, i) => (
        <details key={p.id} className="card enter" style={{ ["--i" as string]: i + 1, padding: "14px 18px" }}>
          <summary style={{ display: "flex", alignItems: "center", gap: 12, cursor: "pointer", listStyle: "none" }}>
            <span style={{ width: 34, height: 34, borderRadius: 9, background: "var(--accentsoft)", color: "var(--accent)", display: "grid", placeItems: "center", flex: "none" }}><Icon n="book" s={16} /></span>
            <span style={{ flex: 1, minWidth: 0 }}>
              <b>{p.title}</b><br />
              <small className="muted">Version {p.version} · published {fmtDY(p.publishedAt.slice(0, 10))}</small>
            </span>
            {p.acknowledgedAt ? <span className="chip ok">Accepted {fmtDY(p.acknowledgedAt.slice(0, 10))}</span>
              : p.requiresAck ? <span className="chip warn">To accept</span> : <span className="chip neutral">For information</span>}
            <Icon n="chevD" s={14} />
          </summary>
          <div style={{ marginTop: 14, borderTop: "1px solid var(--line)", paddingTop: 14 }}>
            {p.body ? <PolicyText body={p.body} /> : null}
            {p.fileName && (
              <a className="btn btn-sm" href={`/api/policies/${p.id}/versions/${p.version}/file`} target="_blank" rel="noopener"><Icon n="doc" s={14} />Open {p.fileName}</a>
            )}
          </div>
        </details>
      ))}
      {!rows.length && <div className="empty card"><b className="display">No policies yet</b><span className="muted">Company policies will appear here once they are published.</span></div>}
    </div>
  );
}
