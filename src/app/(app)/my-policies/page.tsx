import type { Metadata } from "next";
import { Icon } from "@/components/Icon";
import { PageHeader } from "@/components/PageHeader";
import type { PolicyToRead } from "@/lib/api-types";
import { requirePage } from "@/lib/auth";
import { apiData } from "@/lib/backend";
import { fmtDY } from "@/lib/format";
import { getT } from "@/lib/i18n/server";
import { PolicyText } from "./PolicyText";

export const metadata: Metadata = { title: "My policies" };

/** Every current company policy, and when the signed-in user accepted it. */
export default async function MyPoliciesPage() {
  await requirePage("my-policies");
  const t = await getT();
  const rows = await apiData<PolicyToRead[]>("/api/policies/mine");

  return (
    <div className="col">
      <PageHeader eyebrow={t("Company")} title={<>{t("My")} <em>{t("policies")}</em></>} sub={t("The policies that apply to you. When a policy changes you'll be asked to read and accept the new version.")} />
      {rows.map((p, i) => (
        <details key={p.id} className="card enter" style={{ ["--i" as string]: i + 1, padding: "14px 18px" }}>
          <summary style={{ display: "flex", alignItems: "center", gap: 12, cursor: "pointer", listStyle: "none" }}>
            <span style={{ width: 34, height: 34, borderRadius: 9, background: "var(--accentsoft)", color: "var(--accent)", display: "grid", placeItems: "center", flex: "none" }}><Icon n="book" s={16} /></span>
            <span style={{ flex: 1, minWidth: 0 }}>
              <b>{p.title}</b><br />
              <small className="muted">{t("Version {v} · published {date}", { v: p.version, date: fmtDY(p.publishedAt.slice(0, 10)) })}</small>
            </span>
            {p.acknowledgedAt ? <span className="chip ok">{t("Accepted {date}", { date: fmtDY(p.acknowledgedAt.slice(0, 10)) })}</span>
              : p.requiresAck ? <span className="chip warn">{t("To accept")}</span> : <span className="chip neutral">{t("For information")}</span>}
            <Icon n="chevD" s={14} />
          </summary>
          <div style={{ marginTop: 14, borderTop: "1px solid var(--line)", paddingTop: 14 }}>
            {p.body ? <PolicyText body={p.body} /> : null}
            {p.fileName && (
              <a className="btn btn-sm" href={`/api/policies/${p.id}/versions/${p.version}/file`} target="_blank" rel="noopener"><Icon n="doc" s={14} />{t("Open {file}", { file: p.fileName })}</a>
            )}
          </div>
        </details>
      ))}
      {!rows.length && <div className="empty card"><b className="display">{t("No policies yet")}</b><span className="muted">{t("Company policies will appear here once they are published.")}</span></div>}
    </div>
  );
}
