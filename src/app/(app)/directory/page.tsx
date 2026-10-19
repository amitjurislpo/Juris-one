import type { Metadata } from "next";
import { PageHeader } from "@/components/PageHeader";
import { ClearFilters, Pager, Pending, SearchBox, SegFilter, SelectFilter, SortTh } from "@/components/table/Table";
import { getT } from "@/lib/i18n/server";
import { requirePage } from "@/lib/auth";
import { apiData, qs } from "@/lib/backend";
import { avc, initials } from "@/lib/format";
import { CopyEmail } from "./CopyEmail";

export const metadata: Metadata = { title: "Directory" };

/** Everyone (`dir`): name, official email, designation and Active / Past only (REQUIREMENTS §5.3). */
export default async function DirectoryPage(props: PageProps<"/directory">) {
  await requirePage("directory");
  const t = await getT();
  const { rows, total, page, take, nActive, depts } = await apiData<{
    rows: { id: string; name: string; email: string; title: string; status: string }[]; total: number; page: number; take: number; nActive: number; depts: { id: string; name: string }[];
  }>(`/api/directory${qs(await props.searchParams)}`);

  return (
    <div className="col">
      <PageHeader eyebrow={t("Directory")} title={t("Find")} em={t("anyone")} sub={t("{n} people at Juris Consultants. Search by name, email or designation.", { n: nActive })} />
      <div className="toolbar enter" style={{ ["--i" as string]: 1 }}>
        <SearchBox big placeholder={t("Search name, email or designation")} label={t("Search directory")} />
        <SelectFilter name="dept" label={t("Department")} dflt="All" options={[{ v: "All", l: t("All") }, ...depts.map((x) => ({ v: x.id, l: x.name }))]} />
        <SegFilter name="st" dflt="All" label={t("Status")} options={[{ v: "All", l: t("All") }, { v: "Active", l: t("Active") }, { v: "Past", l: t("Past employees") }]} />
      </div>
      <section className="card enter" style={{ ["--i" as string]: 2 }}>
        <Pending>
          <div className="table-wrap">
            <table className="t">
              <thead>
                <tr>
                  <SortTh k="name" dflt="name">{t("Name")}</SortTh>
                  <SortTh k="email" dflt="name">{t("Email")}</SortTh>
                  <SortTh k="title" dflt="name">{t("Designation")}</SortTh>
                  <SortTh k="st" dflt="name">{t("Status")}</SortTh>
                </tr>
              </thead>
              <tbody>
                {rows.map((e, i) => (
                  <tr key={e.id} className={e.status === "EXITED" ? "dim" : undefined} style={{ animationDelay: `${i * 30}ms` }}>
                    <td><div className="person"><span className={`avatar ${avc(initials(e.name))}`}>{initials(e.name)}</span><div><b>{e.name}</b></div></div></td>
                    <td><CopyEmail email={e.email} /></td>
                    <td className="muted">{e.title}</td>
                    <td><span className={`chip ${e.status === "EXITED" ? "neutral" : "ok"}`}>{e.status === "EXITED" ? t("Past employee") : t("Active")}</span></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {!rows.length && <div className="empty"><b>{t("No one found")}</b><span className="muted">{t("Try another name or email.")}</span><ClearFilters /></div>}
          <Pager page={page} take={take} total={total} />
        </Pending>
      </section>
    </div>
  );
}
