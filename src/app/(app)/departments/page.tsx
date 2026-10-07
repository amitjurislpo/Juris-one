import type { Metadata } from "next";
import { PageHeader } from "@/components/PageHeader";
import { Pending, SearchBox, SortTh } from "@/components/table/Table";
import { Icon } from "@/components/Icon";
import { requirePage } from "@/lib/auth";
import { apiData, qs } from "@/lib/backend";
import { fmtDY } from "@/lib/format";
import { DepartmentActions, NewDepartmentButton } from "./DepartmentsClient";

export const metadata: Metadata = { title: "Departments" };

/** Filtered and sorted by the API from ?q=&sort=&dir=. */
interface Row { id: string; name: string; note: string; head: string; n: number; by: string; on: string }

export default async function DepartmentsPage(props: PageProps<"/departments">) {
  const u = await requirePage("departments");
  const { rows, heads } = await apiData<{ rows: Row[]; heads: { id: string; name: string; title: string }[] }>(`/api/departments${qs(await props.searchParams)}`);

  return (
    <div className="col">
      <PageHeader eyebrow="People operations" title={<>Depart<em>ments</em></>} sub="Create departments first — new employees are added into one of these.">
        <NewDepartmentButton by={u.name} heads={heads} />
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
