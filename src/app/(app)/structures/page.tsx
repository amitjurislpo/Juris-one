import type { Metadata } from "next";
import { PageHeader } from "@/components/PageHeader";
import { ClearFilters, Pending, SearchBox, SegFilter, SelectFilter, SortTh } from "@/components/table/Table";
import { requirePage } from "@/lib/auth";
import { getT } from "@/lib/i18n/server";
import { apiData, qs } from "@/lib/backend";
import { avc, fmtDY, inr, initials } from "@/lib/format";
import { HistoryButton, StructureButton, type Rev } from "./StructuresClient";

export const metadata: Metadata = { title: "Salary structures" };


export default async function StructuresPage(props: PageProps<"/structures">) {
  await requirePage("structures");
  const t = await getT();
  const d = await apiData<{
    rows: { id: string; code: string; name: string; title: string; doj: string; revs: Rev[]; cur: Rev | null; g: number }[];
    total: number; depts: { id: string; name: string }[]; today: string; minFrom: string;
  }>(`/api/structures${qs(await props.searchParams)}`);
  const { rows, depts, today, minFrom } = d;
  const all = { length: d.total };

  return (
    <div className="col">
      <PageHeader eyebrow={t("Finance · Compensation")} title={t("Salary")} em={t("structures")} sub={t("Set a breakup once. Payroll and TDS use it every month until you add a revision (appraisal or change).")} />
      <div className="toolbar enter" style={{ ["--i" as string]: 1 }}>
        <SearchBox placeholder={t("Search name, ID or designation")} label={t("Search structures")} />
        <SelectFilter name="dept" label={t("Department")} dflt="All" options={[{ v: "All", l: t("All") }, ...depts.map((d) => ({ v: d.id, l: d.name }))]} />
        <SegFilter name="set" dflt="All" label={t("Structure")} options={[{ v: "All", l: t("All") }, { v: "Set", l: t("Set up") }, { v: "Missing", l: t("Not set") }]} />
        <span className="tcount">{t("{a} of {b}", { a: rows.length, b: all.length })}</span>
      </div>
      <section className="card enter" style={{ ["--i" as string]: 2 }}>
        <Pending>
          <div className="table-wrap">
            <table className="t">
              <thead>
                <tr>
                  <SortTh k="name" dflt="name">{t("Employee")}</SortTh>
                  <SortTh k="basic" dflt="name" right>{t("Basic")}</SortTh>
                  <th className="r">{t("HRA")}</th><th className="r">{t("LTA")}</th><th className="r">{t("Special")}</th>
                  <SortTh k="g" dflt="name" right>{t("Monthly gross")}</SortTh>
                  <SortTh k="since" dflt="name">{t("Effective from")}</SortTh>
                  <th><span className="sr-only">{t("Actions")}</span></th>
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
                      <><td colSpan={5}><span className="chip warn">{t("Not set — needed before payroll")}</span></td><td></td></>
                    )}
                    <td className="r"><StructureButton emp={{ id: r.id, code: r.code, name: r.name, title: r.title, doj: r.doj }} current={r.cur} minFrom={minFrom > r.doj ? minFrom : r.doj} /></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {!rows.length && <div className="empty"><b>{t("No one matches")}</b><span className="muted">{t("Try another search or filter.")}</span><ClearFilters /></div>}
        </Pending>
      </section>
    </div>
  );
}
