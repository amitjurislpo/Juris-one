import type { Metadata } from "next";
import { PageHeader } from "@/components/PageHeader";
import { ClearFilters, Pending, SearchBox, SegFilter, SelectFilter, SortTh } from "@/components/table/Table";
import { requirePage } from "@/lib/auth";
import { apiData, qs } from "@/lib/backend";
import { avc, fmtDY, inr, initials } from "@/lib/format";
import { HistoryButton, StructureButton, type Rev } from "./StructuresClient";

export const metadata: Metadata = { title: "Salary structures" };


export default async function StructuresPage(props: PageProps<"/structures">) {
  await requirePage("structures");
  const d = await apiData<{
    rows: { id: string; code: string; name: string; title: string; doj: string; revs: Rev[]; cur: Rev | null; g: number }[];
    total: number; depts: { id: string; name: string }[]; today: string; minFrom: string;
  }>(`/api/structures${qs(await props.searchParams)}`);
  const { rows, depts, today, minFrom } = d;
  const all = { length: d.total };

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
