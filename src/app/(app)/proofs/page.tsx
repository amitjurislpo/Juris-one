import type { Metadata } from "next";
import { PageHeader } from "@/components/PageHeader";
import { ClearFilters, Pending, SearchBox, SegFilter, SortTh } from "@/components/table/Table";
import { requirePage } from "@/lib/auth";
import { apiData, qs } from "@/lib/backend";
import { getT } from "@/lib/i18n/server";
import { avc, inr, initials } from "@/lib/format";
import { ReviewButton } from "./ProofsClient";

export const metadata: Metadata = { title: "Investment proofs" };

export default async function ProofsPage(props: PageProps<"/proofs">) {
  await requirePage("proofs");
  const t = await getT();
  const sp = await props.searchParams;
  const { rows, total } = await apiData<{ rows: { id: string; name: string; code: string; dept: string; reg: "NEW" | "OLD"; dec: number; pend: number; appr: number }[]; total: number }>(`/api/proofs${qs(sp)}`);

  return (
    <div className="col">
      <PageHeader eyebrow={t("Finance · Old-regime declarations")} title={t("Investment")} em={t("proofs")} sub={t("Verify what employees declared. Approved amounts reduce their TDS from the next payroll.")} />
      <div className="toolbar enter" style={{ ["--i" as string]: 1 }}>
        <SearchBox placeholder={t("Search name, ID or department")} label={t("Search proofs")} />
        <SegFilter name="st" dflt="All" label={t("Status")} options={[{ v: "All", l: t("All") }, { v: "Pending", l: t("To verify") }, { v: "Verified", l: t("Verified") }]} />
        <span className="tcount">{t("{a} of {b}", { a: rows.length, b: total })}</span>
      </div>
      <section className="card enter" style={{ ["--i" as string]: 1 }}>
        <Pending>
          <div className="table-wrap">
            <table className="t">
              <thead>
                <tr>
                  <SortTh k="name" dflt="pend" dfltDir="desc">{t("Employee")}</SortTh>
                  <SortTh k="reg" dflt="pend" dfltDir="desc">{t("Regime")}</SortTh>
                  <SortTh k="dec" dflt="pend" dfltDir="desc" right>{t("Declared")}</SortTh>
                  <SortTh k="pend" dflt="pend" dfltDir="desc">{t("Status")}</SortTh>
                  <th><span className="sr-only">{t("Actions")}</span></th>
                </tr>
              </thead>
              <tbody>
                {rows.map((r, i) => (
                  <tr key={r.id} style={{ animationDelay: `${i * 40}ms` }}>
                    <td><div className="person"><span className={`avatar ${avc(initials(r.name))}`}>{initials(r.name)}</span><div><b>{r.name}</b><small>{r.code} · {r.dept}</small></div></div></td>
                    <td><span className={`chip nodot ${r.reg === "OLD" ? "info" : "gold"}`}>{r.reg === "OLD" ? t("Old") : t("New")}</span></td>
                    <td className="r num">{inr(r.dec)}</td>
                    <td>{r.pend ? <span className="chip warn">{t("{n} to verify", { n: r.pend })}</span> : r.appr ? <span className="chip ok">{t("Verified")}</span> : <span className="chip neutral">{t("Nothing submitted")}</span>}</td>
                    <td className="r"><ReviewButton employeeId={r.id} name={r.name} gold={r.pend > 0} /></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {!total && <div className="empty"><b className="display">{t("No declarations yet")}</b><span className="muted">{t("Employees on the old regime will appear here.")}</span></div>}
          {total > 0 && !rows.length && <div className="empty"><b>{t("No one matches")}</b><ClearFilters /></div>}
        </Pending>
      </section>
    </div>
  );
}
