import type { Metadata } from "next";
import { PageHeader } from "@/components/PageHeader";
import { ClearFilters, Pending, SearchBox, SegFilter, SelectFilter, SortTh } from "@/components/table/Table";
import { requirePage } from "@/lib/auth";
import { db } from "@/lib/db";
import { avc, dateToIso, fmtDY, inr, initials, todayIn } from "@/lib/format";
import { listState, pick, str } from "@/lib/list";
import { HistoryButton, StructureButton, type Rev } from "./StructuresClient";

export const metadata: Metadata = { title: "Salary structures" };

const SORTS = ["name", "basic", "g", "since"] as const;

export default async function StructuresPage(props: PageProps<"/structures">) {
  await requirePage("structures");
  const sp = await props.searchParams;
  const ls = listState(sp, SORTS, "name", 100);
  const set = pick(sp, "set", ["All", "Set", "Missing"] as const, "All");
  const dept = str(sp, "dept", "All");
  const today = todayIn("Asia/Kolkata");

  const [emps, depts, lastClosed] = await Promise.all([
    db.employee.findMany({
      where: { region: "IN", status: { not: "EXITED" }, ...(dept !== "All" && /^[0-9a-f-]{36}$/.test(dept) ? { departmentId: dept } : {}) },
      include: { salaryStructures: { orderBy: { effectiveFrom: "desc" } }, department: { select: { name: true } } },
    }),
    db.department.findMany({ where: { archivedAt: null }, orderBy: { name: "asc" }, select: { id: true, name: true } }),
    db.payrollRun.findFirst({ where: { status: "CLOSED" }, orderBy: { month: "desc" } }),
  ]);
  const q = ls.q.toLowerCase();
  const all = emps.map((e) => {
    const revs: Rev[] = e.salaryStructures.map((s) => ({ from: dateToIso(s.effectiveFrom), basic: s.basic, hra: s.hra, lta: s.lta, special: s.special, reason: s.reason }));
    const cur = revs.find((r) => r.from <= today) ?? revs.at(-1) ?? null; // current, else the upcoming one
    return { id: e.id, code: e.code, name: e.fullName, title: e.designation, doj: dateToIso(e.dateOfJoining), revs, cur, g: cur ? cur.basic + cur.hra + cur.lta + cur.special : 0 };
  });
  const rows = all.filter((r) => (!q || `${r.name} ${r.code} ${r.title}`.toLowerCase().includes(q)) && (set === "All" || (set === "Set") === !!r.cur));
  const get = { name: (r: (typeof rows)[number]) => r.name, basic: (r: (typeof rows)[number]) => r.cur?.basic ?? -1, g: (r: (typeof rows)[number]) => r.g, since: (r: (typeof rows)[number]) => r.cur?.from ?? "" }[ls.sort];
  rows.sort((a, b) => { const x = get(a), y = get(b); return (x > y ? 1 : x < y ? -1 : 0) * (ls.dir === "asc" ? 1 : -1); });
  const minFrom = lastClosed ? new Date(Date.UTC(Number(lastClosed.month.slice(0, 4)), Number(lastClosed.month.slice(5)), 1)).toISOString().slice(0, 10) : "";

  return (
    <div className="col">
      <PageHeader eyebrow="Finance · Compensation" title="Salary" em="structures" sub="Set a breakup once. Payroll and TDS use it every month until you add a revision (appraisal or change)." />
      <div className="toolbar enter" style={{ ["--i" as string]: 1 }}>
        <SearchBox placeholder="Search name, ID or designation" label="Search structures" />
        <SelectFilter name="dept" label="Department" dflt="All" options={[{ v: "All", l: "All" }, ...depts.map((d) => ({ v: d.id, l: d.name }))]} />
        <SegFilter name="set" dflt="All" label="Structure" options={[{ v: "All", l: "All" }, { v: "Set", l: "Set up" }, { v: "Missing", l: "Not set" }]} />
        <span className="tcount">{rows.length} of {all.length}</span>
      </div>
      <section className="card enter" style={{ ["--i" as string]: 2 }}>
        <Pending>
          <div className="table-wrap">
            <table className="t">
              <thead>
                <tr>
                  <SortTh k="name" dflt="name">Employee</SortTh>
                  <SortTh k="basic" dflt="name" right>Basic</SortTh>
                  <th className="r">HRA</th><th className="r">LTA</th><th className="r">Special</th>
                  <SortTh k="g" dflt="name" right>Monthly gross</SortTh>
                  <SortTh k="since" dflt="name">Effective from</SortTh>
                  <th><span className="sr-only">Actions</span></th>
                </tr>
              </thead>
              <tbody>
                {rows.map((r, i) => (
                  <tr key={r.id} style={{ animationDelay: `${i * 35}ms` }}>
                    <td><div className="person"><span className={`avatar ${avc(initials(r.name))}`}>{initials(r.name)}</span><div><b>{r.name}</b><small>{r.code} · {r.title}</small></div></div></td>
                    {r.cur ? (
                      <>
                        <td className="r num muted">{inr(r.cur.basic)}</td><td className="r num muted">{inr(r.cur.hra)}</td><td className="r num muted">{inr(r.cur.lta)}</td><td className="r num muted">{inr(r.cur.special)}</td>
                        <td className="r num" style={{ fontWeight: 600 }}>{inr(r.g)}</td>
                        <td><span className="muted">{fmtDY(r.cur.from)}</span>{r.revs.length > 1 && <HistoryButton name={r.name} revs={r.revs} today={today} />}</td>
                      </>
                    ) : (
                      <><td colSpan={5}><span className="chip warn">Not set — needed before payroll</span></td><td></td></>
                    )}
                    <td className="r"><StructureButton emp={{ id: r.id, code: r.code, name: r.name, title: r.title, doj: r.doj }} current={r.cur} minFrom={minFrom > r.doj ? minFrom : r.doj} /></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {!rows.length && <div className="empty"><b>No one matches</b><span className="muted">Try another search or filter.</span><ClearFilters /></div>}
        </Pending>
      </section>
    </div>
  );
}
