import type { Metadata } from "next";
import { PageHeader } from "@/components/PageHeader";
import { Pager, Pending, SearchBox, SelectFilter } from "@/components/table/Table";
import { loadActor } from "@/lib/actor";
import { requirePage } from "@/lib/auth";
import { db } from "@/lib/db";
import { dateToIso } from "@/lib/format";
import { canAddEmployees, canToggleAccess, locationOfTz } from "@/lib/people-rules";
import { peopleQuery } from "@/lib/people-query";
import { AddEmployeeButton, PeopleTable, type PersonRow } from "./PeopleClient";

export const metadata: Metadata = { title: "People" };

export default async function PeoplePage(props: PageProps<"/people">) {
  const u = await requirePage("people");
  const sp = await props.searchParams;
  const { ls, where, orderBy } = peopleQuery(sp);
  const actor = await loadActor(u);

  const [rows, total, depts, counts] = await Promise.all([
    db.employee.findMany({
      where, orderBy, skip: ls.skip, take: ls.take,
      select: {
        id: true, code: true, fullName: true, designation: true, status: true, portalAccess: true, timeZone: true, region: true, roleId: true, dateOfJoining: true,
        department: { select: { name: true } }, reportingManager: { select: { fullName: true } }, role: { select: { name: true, key: true } }, user: { select: { isSuperAdmin: true } },
      },
    }),
    db.employee.count({ where }),
    db.department.findMany({ where: { archivedAt: null }, orderBy: { name: "asc" }, select: { id: true, name: true } }),
    db.employee.groupBy({ by: ["status", "portalAccess", "region"], _count: true }),
  ]);

  const sum = (f: (c: (typeof counts)[number]) => boolean) => counts.filter(f).reduce((a, c) => a + c._count, 0);
  const k = {
    cur: sum((c) => c.status !== "EXITED"),
    us: sum((c) => c.status !== "EXITED" && c.region === "US"),
    off: sum((c) => c.status !== "EXITED" && !c.portalAccess),
    past: sum((c) => c.status === "EXITED"),
  };
  const seePay = u.perms.has("struct.manage");

  const view: PersonRow[] = rows.map((e) => ({
    id: e.id, code: e.code, name: e.fullName, title: e.designation, dept: e.department?.name ?? "—", manager: e.reportingManager?.fullName ?? "—",
    loc: locationOfTz(e.timeZone).l, india: e.region === "IN", role: e.role.name, status: e.status, access: e.portalAccess, doj: dateToIso(e.dateOfJoining),
    canToggle: canToggleAccess(actor, { id: e.id, roleId: e.roleId, roleKey: e.role.key, status: e.status, isSuperAdmin: !!e.user?.isSuperAdmin }),
  }));

  const exportQs = new URLSearchParams(Object.entries(sp).flatMap(([key, v]) => (typeof v === "string" && key !== "page" ? [[key, v]] : []))).toString();

  return (
    <div className="col">
      <PageHeader eyebrow="People operations" title="The" em="Juris team"
        sub={seePay ? "Employee records, portal access and reporting lines." : "Employee records, portal access and reporting lines. Compensation is visible to Finance only."}>
        <a className="btn" href={`/api/people/export${exportQs ? `?${exportQs}` : ""}`} download>Export CSV</a>
        {canAddEmployees(actor) && <AddEmployeeButton />}
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
          <Pager page={ls.page} take={ls.take} total={total} />
        </Pending>
      </section>
    </div>
  );
}
