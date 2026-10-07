import type { Metadata } from "next";
import { PageHeader } from "@/components/PageHeader";
import { Pager, Pending, SearchBox, SelectFilter } from "@/components/table/Table";
import { requirePage } from "@/lib/auth";
import { apiData, qs } from "@/lib/backend";
import { AddEmployeeButton, PeopleTable, type PersonRow } from "./PeopleClient";

export const metadata: Metadata = { title: "People" };

export default async function PeoplePage(props: PageProps<"/people">) {
  await requirePage("people");
  const sp = await props.searchParams;
  const { rows: view, total, page, take, depts, counts: k, seePay, canAdd } = await apiData<{
    rows: PersonRow[]; total: number; page: number; take: number; depts: { id: string; name: string }[];
    counts: { cur: number; us: number; off: number; past: number }; seePay: boolean; canAdd: boolean;
  }>(`/api/people${qs(sp)}`);

  const exportQs = new URLSearchParams(Object.entries(sp).flatMap(([key, v]) => (typeof v === "string" && key !== "page" ? [[key, v]] : []))).toString();

  return (
    <div className="col">
      <PageHeader eyebrow="People operations" title="The" em="Juris team"
        sub={seePay ? "Employee records, portal access and reporting lines." : "Employee records, portal access and reporting lines. Compensation is visible to Finance only."}>
        <a className="btn" href={`/api/people/export${exportQs ? `?${exportQs}` : ""}`} download>Export CSV</a>
        {canAdd && <AddEmployeeButton />}
      </PageHeader>
      <div className="kpis">
        <div className="card kpi enter" style={{ ["--i" as string]: 1 }}><span className="spot"></span><span className="l">Current employees</span><span className="v num">{k.cur}</span></div>
        <div className="card kpi enter" style={{ ["--i" as string]: 2 }}><span className="spot"></span><span className="l"><i className="dot" style={{ color: "var(--info)" }}></i> US team</span><span className="v num">{k.us}</span></div>
        <div className="card kpi enter" style={{ ["--i" as string]: 3 }}><span className="spot"></span><span className="l"><i className="dot" style={{ color: "var(--bad)" }}></i> Access disabled</span><span className="v num">{k.off}</span></div>
        <div className="card kpi enter" style={{ ["--i" as string]: 4 }}><span className="spot"></span><span className="l"><i className="dot" style={{ color: "var(--faint)" }}></i> Past employees</span><span className="v num">{k.past}</span></div>
      </div>
      <div className="toolbar enter" style={{ ["--i" as string]: 5 }}>
        <SearchBox placeholder="Search name, ID, email, designation, manager" label="Search people" />
        <SelectFilter name="dept" label="Department" dflt="All" options={[{ v: "All", l: "All" }, ...depts.map((d) => ({ v: d.id, l: d.name }))]} />
        <SelectFilter name="status" label="Status" dflt="Current" options={[{ v: "Current", l: "Current" }, { v: "ACTIVE", l: "Active" }, { v: "PROBATION", l: "Probation" }, { v: "ONBOARDING", l: "Onboarding" }, { v: "EXITED", l: "Past employees" }, { v: "All", l: "All" }]} />
        <SelectFilter name="access" label="Access" dflt="All" options={[{ v: "All", l: "All" }, { v: "Enabled", l: "Enabled" }, { v: "Disabled", l: "Disabled" }]} />
        <span className="tcount">{total} shown</span>
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
