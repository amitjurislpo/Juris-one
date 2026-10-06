import type { Metadata } from "next";
import type { Prisma } from "@/generated/prisma/client";
import { PageHeader } from "@/components/PageHeader";
import { ClearFilters, Pager, Pending, SearchBox, SegFilter, SelectFilter, SortTh } from "@/components/table/Table";
import { requirePage } from "@/lib/auth";
import { db } from "@/lib/db";
import { avc, initials } from "@/lib/format";
import { listState, pick, str } from "@/lib/list";
import { CopyEmail } from "./CopyEmail";

export const metadata: Metadata = { title: "Directory" };

const SORTS = ["name", "email", "title", "st"] as const;

/** Everyone (`dir`): name, official email, designation and Active / Past only (REQUIREMENTS §5.3). */
export default async function DirectoryPage(props: PageProps<"/directory">) {
  await requirePage("directory");
  const sp = await props.searchParams;
  const ls = listState(sp, SORTS, "name");
  const st = pick(sp, "st", ["All", "Active", "Past"] as const, "All");
  const dept = str(sp, "dept", "All");

  const and: Prisma.EmployeeWhereInput[] = [];
  if (st !== "All") and.push({ status: st === "Past" ? "EXITED" : { not: "EXITED" } });
  if (dept !== "All" && /^[0-9a-f-]{36}$/.test(dept)) and.push({ departmentId: dept });
  if (ls.q) {
    const c = { contains: ls.q, mode: "insensitive" as const };
    and.push({ OR: [{ fullName: c }, { email: c }, { designation: c }] });
  }
  const where = { AND: and };
  const d = ls.dir;
  const orderBy = { name: [{ fullName: d }], email: [{ email: d }], title: [{ designation: d }, { fullName: "asc" as const }], st: [{ status: d }, { fullName: "asc" as const }] }[ls.sort];

  const [rows, total, nActive, depts] = await Promise.all([
    db.employee.findMany({ where, orderBy, skip: ls.skip, take: ls.take, select: { id: true, fullName: true, email: true, designation: true, status: true } }),
    db.employee.count({ where }),
    db.employee.count({ where: { status: { not: "EXITED" } } }),
    db.department.findMany({ where: { archivedAt: null }, orderBy: { name: "asc" }, select: { id: true, name: true } }),
  ]);

  return (
    <div className="col">
      <PageHeader eyebrow="Directory" title="Find" em="anyone" sub={`${nActive} people at Juris Consultants. Search by name, email or designation.`} />
      <div className="toolbar enter" style={{ ["--i" as string]: 1 }}>
        <SearchBox big placeholder="Search name, email or designation" label="Search directory" />
        <SelectFilter name="dept" label="Department" dflt="All" options={[{ v: "All", l: "All" }, ...depts.map((x) => ({ v: x.id, l: x.name }))]} />
        <SegFilter name="st" dflt="All" label="Status" options={[{ v: "All", l: "All" }, { v: "Active", l: "Active" }, { v: "Past", l: "Past employees" }]} />
      </div>
      <section className="card enter" style={{ ["--i" as string]: 2 }}>
        <Pending>
          <div className="table-wrap">
            <table className="t">
              <thead>
                <tr>
                  <SortTh k="name" dflt="name">Name</SortTh>
                  <SortTh k="email" dflt="name">Email</SortTh>
                  <SortTh k="title" dflt="name">Designation</SortTh>
                  <SortTh k="st" dflt="name">Status</SortTh>
                </tr>
              </thead>
              <tbody>
                {rows.map((e, i) => (
                  <tr key={e.id} className={e.status === "EXITED" ? "dim" : undefined} style={{ animationDelay: `${i * 30}ms` }}>
                    <td><div className="person"><span className={`avatar ${avc(initials(e.fullName))}`}>{initials(e.fullName)}</span><div><b>{e.fullName}</b></div></div></td>
                    <td><CopyEmail email={e.email} /></td>
                    <td className="muted">{e.designation}</td>
                    <td><span className={`chip ${e.status === "EXITED" ? "neutral" : "ok"}`}>{e.status === "EXITED" ? "Past employee" : "Active"}</span></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {!rows.length && <div className="empty"><b>No one found</b><span className="muted">Try another name or email.</span><ClearFilters /></div>}
          <Pager page={ls.page} take={ls.take} total={total} />
        </Pending>
      </section>
    </div>
  );
}
