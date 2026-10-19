import type { Metadata } from "next";
import { PageHeader } from "@/components/PageHeader";
import { Pending, SearchBox, SortTh } from "@/components/table/Table";
import { Icon } from "@/components/Icon";
import { getT } from "@/lib/i18n/server";
import { requirePage } from "@/lib/auth";
import { apiData, qs } from "@/lib/backend";
import { fmtDY } from "@/lib/format";
import { DepartmentActions, NewDepartmentButton } from "./DepartmentsClient";

export const metadata: Metadata = { title: "Departments" };

/** Filtered and sorted by the API from ?q=&sort=&dir=. */
interface Row { id: string; name: string; note: string; head: string; n: number; by: string; on: string }

export default async function DepartmentsPage(props: PageProps<"/departments">) {
  const u = await requirePage("departments");
  const t = await getT();
  const { rows, heads } = await apiData<{ rows: Row[]; heads: { id: string; name: string; title: string }[] }>(`/api/departments${qs(await props.searchParams)}`);

  return (
    <div className="col">
      <PageHeader eyebrow={t("People operations")} title={<>{t("Depart")}<em>{t("ments")}</em></>} sub={t("Create departments first — new employees are added into one of these.")}>
        <NewDepartmentButton by={u.name} heads={heads} />
      </PageHeader>
      <div className="toolbar enter" style={{ ["--i" as string]: 1 }}>
        <SearchBox placeholder={t("Search department, head or creator")} label={t("Search departments")} />
        <span className="tcount">{rows.length === 1 ? t("{n} department", { n: rows.length }) : t("{n} departments", { n: rows.length })}</span>
      </div>
      <section className="card enter" style={{ ["--i" as string]: 2 }}>
        <Pending>
          <div className="table-wrap">
            <table className="t">
              <thead>
                <tr>
                  <SortTh k="name" dflt="name">{t("Department")}</SortTh>
                  <SortTh k="head" dflt="name">{t("Head")}</SortTh>
                  <SortTh k="n" dflt="name" right>{t("People")}</SortTh>
                  <SortTh k="by" dflt="name">{t("Created by")}</SortTh>
                  <SortTh k="on" dflt="name">{t("Created on")}</SortTh>
                  <th><span className="sr-only">{t("Actions")}</span></th>
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
          {!rows.length && <div className="empty"><b>{t("No departments match")}</b><span className="muted">{t("Try a different search.")}</span></div>}
        </Pending>
      </section>
    </div>
  );
}
