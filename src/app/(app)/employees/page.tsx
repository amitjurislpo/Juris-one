import type { Metadata } from "next";
import { PageHeader } from "@/components/PageHeader";
import { Pager, Pending, SearchBox, SelectFilter } from "@/components/table/Table";
import { getT } from "@/lib/i18n/server";
import { requirePage } from "@/lib/auth";
import { apiData, qs } from "@/lib/backend";
import { AddEmployeeButton, PeopleTable, type PersonRow } from "./PeopleClient";

export const metadata: Metadata = { title: "Employees" };

export default async function PeoplePage(props: PageProps<"/employees">) {
  await requirePage("employees");
  const t = await getT();
  const sp = await props.searchParams;
  const { rows: view, total, page, take, depts, counts: k, seePay, canAdd } = await apiData<{
    rows: PersonRow[]; total: number; page: number; take: number; depts: { id: string; name: string }[];
    counts: { cur: number; us: number; off: number; past: number }; seePay: boolean; canAdd: boolean;
  }>(`/api/people${qs(sp)}`);

  const exportQs = new URLSearchParams(Object.entries(sp).flatMap(([key, v]) => (typeof v === "string" && key !== "page" ? [[key, v]] : []))).toString();

  return (
    <div className="col">
      <PageHeader eyebrow={t("People operations")} title={t("All")} em={t("employees")}
        sub={seePay ? t("Employee records, portal access and reporting lines.") : t("Employee records, portal access and reporting lines. Compensation is visible to Finance only.")}>
        <a className="btn" href={`/api/people/export${exportQs ? `?${exportQs}` : ""}`} download>{t("Export CSV")}</a>
        {canAdd && <AddEmployeeButton />}
      </PageHeader>
      <div className="kpis">
        <div className="card kpi enter" style={{ ["--i" as string]: 1 }}><span className="spot"></span><span className="l">{t("Current employees")}</span><span className="v num">{k.cur}</span></div>
        <div className="card kpi enter" style={{ ["--i" as string]: 2 }}><span className="spot"></span><span className="l"><i className="dot" style={{ color: "var(--info)" }}></i> {t("US team")}</span><span className="v num">{k.us}</span></div>
        <div className="card kpi enter" style={{ ["--i" as string]: 3 }}><span className="spot"></span><span className="l"><i className="dot" style={{ color: "var(--bad)" }}></i> {t("Access disabled")}</span><span className="v num">{k.off}</span></div>
        <div className="card kpi enter" style={{ ["--i" as string]: 4 }}><span className="spot"></span><span className="l"><i className="dot" style={{ color: "var(--faint)" }}></i> {t("Past employees")}</span><span className="v num">{k.past}</span></div>
      </div>
      <div className="toolbar enter" style={{ ["--i" as string]: 5 }}>
        <SearchBox placeholder={t("Search name, ID, email, designation, manager")} label={t("Search people")} />
        <SelectFilter name="dept" label={t("Department")} dflt="All" options={[{ v: "All", l: t("All") }, ...depts.map((d) => ({ v: d.id, l: d.name }))]} />
        <SelectFilter name="status" label={t("Status")} dflt="Current" options={[{ v: "Current", l: t("Current") }, { v: "ACTIVE", l: t("Active") }, { v: "PROBATION", l: t("Probation") }, { v: "ONBOARDING", l: t("Onboarding") }, { v: "EXITED", l: t("Past employees") }, { v: "All", l: t("All") }]} />
        <SelectFilter name="access" label={t("Access")} dflt="All" options={[{ v: "All", l: t("All") }, { v: "Enabled", l: t("Enabled") }, { v: "Disabled", l: t("Disabled") }]} />
        <span className="tcount">{t("{total} shown", { total })}</span>
      </div>
      <section className="card enter" style={{ ["--i" as string]: 6 }}>
        <Pending>
          <PeopleTable rows={view} />
          <Pager page={page} take={take} total={total} />
        </Pending>
      </section>
    </div>
  );
}
