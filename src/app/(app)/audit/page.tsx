import type { Metadata } from "next";
import { PageHeader } from "@/components/PageHeader";
import { ClearFilters, Pager, Pending, SearchBox, SelectFilter, SortTh } from "@/components/table/Table";
import { actionLabel, AUDIT_GROUPS, type AuditGroup, type AuditRow as Row } from "@/lib/audit-labels";
import { requirePage } from "@/lib/auth";
import { apiData, qs } from "@/lib/backend";
import { fmtDateTime } from "@/lib/format";
import { PAGE_TITLES } from "@/lib/nav";
import { AuditRow } from "./AuditClient";

export const metadata: Metadata = { title: PAGE_TITLES.audit };

const TONE: Record<string, string> = { auth: "neutral", employee: "info", role: "gold", policy: "gold", payroll: "ok", salary: "ok", tax: "ok", proof: "ok", declaration: "ok" };

export default async function AuditPage(props: PageProps<"/audit">) {
  const u = await requirePage("audit");
  const sp = await props.searchParams;
  const { page, take, total, rows, actors } = await apiData<{ page: number; take: number; total: number; rows: Row[]; actors: { v: string; l: string }[] }>(`/api/audit${qs(sp)}`);
  const filtered = Object.keys(sp).some((k) => !["page", "sort", "dir"].includes(k));

  return (
    <div className="col">
      <PageHeader eyebrow="Administration" title="Audit" em="log" sub="Who changed what, and when — sign-ins, access, people, roles, approvals, payroll and tax. Click an entry to see before → after." />
      <div className="toolbar enter" style={{ ["--i" as string]: 1 }}>
        <SearchBox placeholder="Search person, action or record ID" label="Search the audit log" />
        <SelectFilter name="ev" label="Event" dflt="All" options={[{ v: "All", l: "All events" }, ...(Object.keys(AUDIT_GROUPS) as AuditGroup[]).map((k) => ({ v: k, l: AUDIT_GROUPS[k].label }))]} />
        <SelectFilter name="actor" label="By" dflt="" options={[{ v: "", l: "Anyone" }, ...actors]} />
        <SelectFilter name="days" label="Period" dflt="30" options={[{ v: "7", l: "Last 7 days" }, { v: "30", l: "Last 30 days" }, { v: "90", l: "Last 90 days" }, { v: "365", l: "Last 12 months" }, { v: "All", l: "All time" }]} />
        <span className="tcount">{total} {total === 1 ? "entry" : "entries"}</span>
      </div>
      <section className="card enter" style={{ ["--i" as string]: 2 }}>
        <Pending>
          <div className="table-wrap">
            <table className="t">
              <thead>
                <tr>
                  <SortTh k="at" dflt="at" dfltDir="desc">When</SortTh>
                  <SortTh k="actor" dflt="at" dfltDir="desc">Who</SortTh>
                  <SortTh k="action" dflt="at" dfltDir="desc">What</SortTh>
                  <th>Record</th>
                  <th className="hide-sm">IP</th>
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
            <div className="empty"><b>No entries</b><span className="muted">{filtered ? "Try a different search, event or period." : "Sensitive changes will be recorded here."}</span>{filtered && <ClearFilters />}</div>
          )}
          <Pager page={page} take={take} total={total} />
        </Pending>
      </section>
    </div>
  );
}
