import type { Metadata } from "next";
import Link from "next/link";
import { Icon } from "@/components/Icon";
import { PageHeader } from "@/components/PageHeader";
import { requirePage } from "@/lib/auth";
import { apiData } from "@/lib/backend";
import { fmtDY } from "@/lib/format";
import { NewPolicyButton } from "./PoliciesClient";

export const metadata: Metadata = { title: "Company policies" };

interface Row {
  id: string; title: string; status: "DRAFT" | "PUBLISHED" | "ARCHIVED"; version: number; requiresAck: boolean; updatedAt: string; publishedAt: string | null;
  hasChanges: boolean; acknowledged: number; required: number;
}

const STATUS: Record<Row["status"], { cls: string; text: string }> = {
  DRAFT: { cls: "neutral", text: "Draft" }, PUBLISHED: { cls: "ok", text: "Published" }, ARCHIVED: { cls: "neutral", text: "Archived" },
};

/** HR / the owner: write and publish policies; see how many people have accepted each one. */
export default async function PoliciesPage() {
  await requirePage("policies");
  const rows = await apiData<Row[]>("/api/policies");

  return (
    <div className="col">
      <PageHeader eyebrow="Administration" title={<>Company <em>policies</em></>}
        sub="Publish a policy and everyone (except owners) must read and accept it before using JurisOne. Each change is a new version; every acceptance is kept as a permanent record.">
        <NewPolicyButton />
      </PageHeader>
      <section className="card enter" style={{ ["--i" as string]: 1 }}>
        <div className="table-wrap">
          <table className="t">
            <thead><tr><th>Policy</th><th>Status</th><th>Version</th><th>Accepted</th><th className="hide-sm">Last change</th></tr></thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.id}>
                  <td>
                    <Link href={`/policies/${r.id}`} className="person" style={{ textDecoration: "none", color: "inherit" }}>
                      <span className="kpi-ico" style={{ width: 34, height: 34, background: "var(--accentsoft)", color: "var(--accent)" }}><Icon n="book" s={16} /></span>
                      <div><b>{r.title}</b><small>{r.requiresAck ? "Must be accepted" : "For information"}</small></div>
                    </Link>
                  </td>
                  <td>
                    <span className={`chip ${STATUS[r.status].cls}`}>{STATUS[r.status].text}</span>
                    {r.status !== "DRAFT" && r.hasChanges && <span className="chip warn nodot" style={{ marginLeft: 6 }}>Unpublished changes</span>}
                  </td>
                  <td className="num">{r.version ? `v${r.version}` : "—"}</td>
                  <td>{r.status === "PUBLISHED" && r.requiresAck ? <span className="num">{r.acknowledged} / {r.required}</span> : <span className="muted">—</span>}</td>
                  <td className="muted hide-sm">{fmtDY(r.updatedAt.slice(0, 10))}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {!rows.length && <div className="empty"><b className="display">No policies yet</b><span className="muted">Write your first policy — a code of conduct, leave or IT policy.</span></div>}
      </section>
    </div>
  );
}
