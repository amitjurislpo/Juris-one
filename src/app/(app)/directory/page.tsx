import type { Metadata } from "next";
import { PageHeader } from "@/components/PageHeader";
import { ClearFilters, Pager, Pending, SearchBox, SegFilter, SelectFilter, SortTh } from "@/components/table/Table";
import { requirePage } from "@/lib/auth";
import { apiData, qs } from "@/lib/backend";
import { avc, initials } from "@/lib/format";
import { CopyEmail } from "./CopyEmail";

export const metadata: Metadata = { title: "Directory" };

/** Everyone (`dir`): name, official email, designation and Active / Past only (REQUIREMENTS §5.3). */
export default async function DirectoryPage(props: PageProps<"/directory">) {
  await requirePage("directory");
  const { rows, total, page, take, nActive, depts } = await apiData<{
    rows: { id: string; name: string; email: string; title: string; status: string }[]; total: number; page: number; take: number; nActive: number; depts: { id: string; name: string }[];
  }>(`/api/directory${qs(await props.searchParams)}`);

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
                    <td><div className="person"><span className={`avatar ${avc(initials(e.name))}`}>{initials(e.name)}</span><div><b>{e.name}</b></div></div></td>
                    <td><CopyEmail email={e.email} /></td>
                    <td className="muted">{e.title}</td>
                    <td><span className={`chip ${e.status === "EXITED" ? "neutral" : "ok"}`}>{e.status === "EXITED" ? "Past employee" : "Active"}</span></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {!rows.length && <div className="empty"><b>No one found</b><span className="muted">Try another name or email.</span><ClearFilters /></div>}
          <Pager page={page} take={take} total={total} />
        </Pending>
      </section>
    </div>
  );
}
