import type { Metadata } from "next";
import { PageHeader } from "@/components/PageHeader";
import { ClearFilters, Pager, Pending, SearchBox, SelectFilter, SortTh } from "@/components/table/Table";
import { actionLabel, AUDIT_GROUPS, type AuditGroup, type AuditRow as Row } from "@/lib/audit-labels";
import { requirePage } from "@/lib/auth";
import { apiData, qs } from "@/lib/backend";
import { fmtDateTime } from "@/lib/format";
import { getT } from "@/lib/i18n/server";
import { PAGE_TITLES } from "@/lib/nav";
import { AuditRow } from "./AuditClient";

export const metadata: Metadata = { title: PAGE_TITLES.audit };

const TONE: Record<string, string> = { auth: "neutral", employee: "info", role: "gold", policy: "gold", payroll: "ok", salary: "ok", tax: "ok", proof: "ok", declaration: "ok" };

export default async function AuditPage(props: PageProps<"/audit">) {
  const u = await requirePage("audit");
  const t = await getT();
  const sp = await props.searchParams;
  const { page, take, total, rows, actors } = await apiData<{ page: number; take: number; total: number; rows: Row[]; actors: { v: string; l: string }[] }>(`/api/audit${qs(sp)}`);
  const filtered = Object.keys(sp).some((k) => !["page", "sort", "dir"].includes(k));

  return (
    <div className="col">
      <PageHeader eyebrow={t("Administration")} title={t("Audit")} em={t("log")} sub={t("Who changed what, and when — sign-ins, access, people, roles, approvals, payroll and tax. Click an entry to see before → after.")} />
      <div className="toolbar enter" style={{ ["--i" as string]: 1 }}>
        <SearchBox placeholder={t("Search person, action or record ID")} label={t("Search the audit log")} />
        <SelectFilter name="ev" label={t("Event")} dflt="All" options={[{ v: "All", l: t("All events") }, ...(Object.keys(AUDIT_GROUPS) as AuditGroup[]).map((k) => ({ v: k, l: t(AUDIT_GROUPS[k].label) }))]} />
        <SelectFilter name="actor" label={t("By")} dflt="" options={[{ v: "", l: t("Anyone") }, ...actors]} />
        <SelectFilter name="days" label={t("Period")} dflt="30" options={[{ v: "7", l: t("Last 7 days") }, { v: "30", l: t("Last 30 days") }, { v: "90", l: t("Last 90 days") }, { v: "365", l: t("Last 12 months") }, { v: "All", l: t("All time") }]} />
        <span className="tcount">{t(total === 1 ? "{n} entry" : "{n} entries", { n: total })}</span>
      </div>
      <section className="card enter" style={{ ["--i" as string]: 2 }}>
        <Pending>
          <div className="table-wrap">
            <table className="t">
              <thead>
                <tr>
                  <SortTh k="at" dflt="at" dfltDir="desc">{t("When")}</SortTh>
                  <SortTh k="actor" dflt="at" dfltDir="desc">{t("Who")}</SortTh>
                  <SortTh k="action" dflt="at" dfltDir="desc">{t("What")}</SortTh>
                  <th>{t("Record")}</th>
                  <th className="hide-sm">{t("IP")}</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((r, i) => {
                  const when = fmtDateTime(new Date(r.at), u.timeZone);
                  const label = actionLabel(r.action);
                  return (
                    <AuditRow key={r.id} row={r} i={i} when={when} label={label}>
                      <td className="num" style={{ whiteSpace: "nowrap" }}>{when}</td>
                      <td><b style={{ fontWeight: 500 }}>{r.actor}</b>{r.actorCode && <small className="faint" style={{ display: "block" }}>{r.actorCode}</small>}</td>
                      <td><span className={`chip nodot ${TONE[r.action.split(".")[0]] ?? "neutral"}`}>{label}</span></td>
                      <td className="muted">{r.subject ?? r.entity.replace(/_/g, " ")}</td>
                      <td className="mono faint hide-sm" style={{ fontSize: 12 }}>{r.ip ?? "—"}</td>
                    </AuditRow>
                  );
                })}
              </tbody>
            </table>
          </div>
          {!rows.length && (
            <div className="empty"><b>{t("No entries")}</b><span className="muted">{filtered ? t("Try a different search, event or period.") : t("Sensitive changes will be recorded here.")}</span>{filtered && <ClearFilters />}</div>
          )}
          <Pager page={page} take={take} total={total} />
        </Pending>
      </section>
    </div>
  );
}
