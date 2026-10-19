import type { Metadata } from "next";
import Link from "next/link";
import { PageHeader } from "@/components/PageHeader";
import type { PolicyDetail } from "@/lib/api-types";
import { requirePage } from "@/lib/auth";
import { apiData } from "@/lib/backend";
import { fmtDateTime, fmtDY } from "@/lib/format";
import { getT } from "@/lib/i18n/server";
import { PolicyActions, PolicyEditor } from "../PoliciesClient";

export const metadata: Metadata = { title: "Policy" };

/** One policy: edit the draft, publish / archive, versions, and who has accepted the current version. */
export default async function PolicyPage(props: PageProps<"/policies/[id]">) {
  const u = await requirePage("policies");
  const t = await getT();
  const { id } = await props.params;
  const p = await apiData<PolicyDetail>(`/api/policies/${encodeURIComponent(id)}`);
  const accepted = p.people.filter((x) => x.acknowledgedAt).length;

  return (
    <div className="col">
      <PageHeader eyebrow={<><Link href="/policies" className="linkish">{t("Company policies")}</Link> / {p.version ? t("version {n}", { n: p.version }) : t("draft")}</>} title={p.title}
        sub={p.status === "PUBLISHED" ? (p.hasChanges ? t("Published — the draft below has changes that aren't published yet.") : t("Published. Edit the draft and publish again to make a new version."))
          : p.status === "ARCHIVED" ? t("Archived — employees no longer see it. Publish it again to bring it back.") : t("Draft — nobody sees it until you publish it.")}>
        <PolicyActions p={p} />
      </PageHeader>

      <PolicyEditor p={p} />

      {p.version > 0 && p.status === "PUBLISHED" && p.requiresAck && (
        <section className="card enter" style={{ ["--i" as string]: 3 }}>
          <div className="card-h" style={{ padding: "16px 18px 0" }}>
            <h3>{t("Version {v}: {a} of {n} accepted", { v: p.version, a: accepted, n: p.people.length })}</h3>
          </div>
          <div className="table-wrap">
            <table className="t">
              <thead><tr><th>{t("Employee")}</th><th className="hide-sm">{t("Department")}</th><th>{t("Accepted")}</th></tr></thead>
              <tbody>
                {p.people.map((x) => (
                  <tr key={x.code}>
                    <td><b>{x.name}</b> <span className="muted mono" style={{ fontSize: 12 }}>{x.code}</span></td>
                    <td className="muted hide-sm">{x.department ?? "—"}</td>
                    <td>{x.acknowledgedAt ? <span className="chip ok">{fmtDateTime(new Date(x.acknowledgedAt), u.timeZone)}</span>
                      : <span className="chip warn">{t("Not yet")}{x.lastVersion ? ` ${t("(accepted v{v})", { v: x.lastVersion })}` : ""}</span>}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      )}

      {p.versions.length > 0 && (
        <section className="card enter" style={{ ["--i" as string]: 4 }}>
          <div className="card-h" style={{ padding: "16px 18px 0" }}><h3>{t("Published versions")}</h3><small className="muted">{t("A published version can never be changed.")}</small></div>
          <div className="table-wrap">
            <table className="t">
              <thead><tr><th>{t("Version")}</th><th>{t("Published")}</th><th className="hide-sm">{t("By")}</th><th className="r">{t("Accepted by")}</th><th className="hide-sm">{t("Text fingerprint (SHA-256)")}</th><th></th></tr></thead>
              <tbody>
                {p.versions.map((v) => (
                  <tr key={v.versionId}>
                    <td><b>v{v.version}</b> <span className="muted">{v.title !== p.title ? v.title : ""}</span></td>
                    <td>{fmtDY(v.publishedAt.slice(0, 10))}</td>
                    <td className="muted hide-sm">{v.publishedBy ?? "—"}</td>
                    <td className="r num">{v.requiresAck ? v.acknowledgements : "—"}</td>
                    <td className="mono muted hide-sm" style={{ fontSize: 11.5 }} title={v.bodySha256}>{v.bodySha256.slice(0, 16)}…</td>
                    <td className="r">{v.fileName && <a className="linkish" href={`/api/policies/${p.id}/versions/${v.version}/file`} target="_blank" rel="noopener">{t("PDF")}</a>}</td>
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
