import type { Metadata } from "next";
import { PageHeader } from "@/components/PageHeader";
import { Pending, SearchBox, SortTh } from "@/components/table/Table";
import { Icon } from "@/components/Icon";
import { requirePage } from "@/lib/auth";
import { db } from "@/lib/db";
import { fmtDY, isoInZone } from "@/lib/format";
import { listState } from "@/lib/list";
import { DepartmentActions, NewDepartmentButton } from "./DepartmentsClient";

export const metadata: Metadata = { title: "Departments" };

const SORTS = ["name", "head", "n", "by", "on"] as const;

export default async function DepartmentsPage(props: PageProps<"/departments">) {
  const u = await requirePage("departments");
  const ls = listState(await props.searchParams, SORTS, "name");

  const [depts, people] = await Promise.all([
    db.department.findMany({
      where: { archivedAt: null },
      include: { head: { select: { fullName: true } }, createdBy: { select: { fullName: true } }, _count: { select: { employees: { where: { status: { not: "EXITED" } } } } } },
    }),
    db.employee.findMany({ where: { status: { not: "EXITED" } }, select: { id: true, fullName: true, designation: true }, orderBy: { fullName: "asc" } }),
  ]);

  const q = ls.q.toLowerCase();
  const rows = depts
    .map((d) => ({ id: d.id, name: d.name, note: d.description ?? "", head: d.head?.fullName ?? "", n: d._count.employees, by: d.createdBy?.fullName ?? "—", on: isoInZone(d.createdAt, u.timeZone) }))
    .filter((r) => !q || `${r.name} ${r.head} ${r.by} ${r.note}`.toLowerCase().includes(q));
  const key = ls.sort;
  rows.sort((a, b) => {
    const x = a[key], y = b[key];
    return (x > y ? 1 : x < y ? -1 : 0) * (ls.dir === "asc" ? 1 : -1);
  });

  return (
    <div className="col">
      <PageHeader eyebrow="People operations" title={<>Depart<em>ments</em></>} sub="Create departments first — new employees are added into one of these.">
        <NewDepartmentButton by={u.name} heads={people.map((p) => ({ id: p.id, name: p.fullName, title: p.designation }))} />
      </PageHeader>
      <div className="toolbar enter" style={{ ["--i" as string]: 1 }}>
        <SearchBox placeholder="Search department, head or creator" label="Search departments" />
        <span className="tcount">{rows.length} department{rows.length === 1 ? "" : "s"}</span>
      </div>
      <section className="card enter" style={{ ["--i" as string]: 2 }}>
        <Pending>
          <div className="table-wrap">
            <table className="t">
              <thead>
                <tr>
                  <SortTh k="name" dflt="name">Department</SortTh>
                  <SortTh k="head" dflt="name">Head</SortTh>
                  <SortTh k="n" dflt="name" right>People</SortTh>
                  <SortTh k="by" dflt="name">Created by</SortTh>
                  <SortTh k="on" dflt="name">Created on</SortTh>
                  <th><span className="sr-only">Actions</span></th>
                </tr>
              </thead>
              <tbody>
                {rows.map((r, i) => (
                  <tr key={r.id} style={{ animationDelay: `${i * 30}ms` }}>
                    <td>
                      <div className="person">
                        <span className="kpi-ico" style={{ width: 34, height: 34, background: "var(--accentsoft)", color: "var(--accent)" }}><Icon n="building" s={16} /></span>
                        <div><b>{r.name}</b>{r.note && <small>{r.note}</small>}</div>
                      </div>
                    </td>
                    <td>{r.head || "—"}</td>
                    <td className="r num">{r.n}</td>
                    <td className="muted">{r.by}</td>
                    <td className="muted">{fmtDY(r.on)}</td>
                    <td className="r">{!r.n && <DepartmentActions id={r.id} name={r.name} />}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {!rows.length && <div className="empty"><b>No departments match</b><span className="muted">Try a different search.</span></div>}
        </Pending>
      </section>
    </div>
  );
}
