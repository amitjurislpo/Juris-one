import type { Metadata } from "next";
import Link from "next/link";
import { PageHeader } from "@/components/PageHeader";
import { ClearFilters, Pending, SearchBox, SegFilter, SortTh } from "@/components/table/Table";
import { can, requirePage } from "@/lib/auth";
import { apiData, qs as toQs } from "@/lib/backend";
import { getT } from "@/lib/i18n/server";
import { avc, inr, initials, ymLabel } from "@/lib/format";
import { TaxRow } from "./TaxClient";

export const metadata: Metadata = { title: "Tax & TDS" };

type TaxRowView = { id: string; code: string; name: string; title: string; reg: "NEW" | "OLD"; inc: number; ded: number; taxable: number; tax: number; tds: number; pend: number; overridden: boolean };

export default async function TaxPage(props: PageProps<"/tax">) {
  const u = await requirePage("tax");
  const t = await getT();
  const sp = await props.searchParams;
  const { month, fy, all, rows } = await apiData<{ month: string; fy: string; all: TaxRowView[]; rows: TaxRowView[] }>(`/api/tax${toQs(sp)}`);
  const qs = new URLSearchParams(Object.entries(sp).flatMap(([k, v]) => (typeof v === "string" ? [[k, v]] : []))).toString();
  const rp = (rupees: number) => inr(rupees * 100);

  return (
    <div className="col">
      <PageHeader eyebrow={t("Finance · Tax year {fy}", { fy })} title={t("Tax")} em={t("& TDS")} sub={t("Monthly TDS for every employee as of {month} payroll, based on their regime and verified investment proofs.", { month: ymLabel(month) })}>
        <a className="btn" href={`/api/tax/export${qs ? `?${qs}` : ""}`} download>{t("Export CSV")}</a>
      </PageHeader>
      <div className="kpis">
        <div className="card kpi enter" style={{ ["--i" as string]: 1 }}><span className="spot"></span><span className="l">{t("Monthly TDS (all)")}</span><span className="v num">{rp(all.reduce((a, r) => a + r.tds, 0))}</span></div>
        <div className="card kpi enter" style={{ ["--i" as string]: 2 }}><span className="spot"></span><span className="l">{t("On old regime")}</span><span className="v"><span className="num">{all.filter((r) => r.reg === "OLD").length}</span><span className="faint" style={{ fontSize: 16 }}> / {all.length}</span></span></div>
        <div className="card kpi enter" style={{ ["--i" as string]: 3 }}><span className="spot"></span><span className="l">{t("Proofs pending")}</span><span className="v num">{all.reduce((a, r) => a + r.pend, 0)}</span>{can(u, "proofs.verify") && <Link className="btn btn-sm btn-ghost" style={{ alignSelf: "flex-start", marginTop: 2 }} href="/proofs?st=Pending">{t("Review →")}</Link>}</div>
      </div>
      <div className="toolbar enter" style={{ ["--i" as string]: 4 }}>
        <SearchBox placeholder={t("Search name or ID")} label={t("Search tax")} />
        <SegFilter name="reg" dflt="All" label={t("Regime")} options={[{ v: "All", l: t("All regimes") }, { v: "NEW", l: t("New") }, { v: "OLD", l: t("Old") }]} />
        <span className="tcount">{t("{n} employees", { n: rows.length })}</span>
      </div>
      <section className="card enter" style={{ ["--i" as string]: 4 }}>
        <Pending>
          <div className="table-wrap">
            <table className="t">
              <thead>
                <tr>
                  <SortTh k="name" dflt="name">{t("Employee")}</SortTh>
                  <SortTh k="reg" dflt="name">{t("Regime")}</SortTh>
                  <SortTh k="inc" dflt="name" right>{t("Projected income")}</SortTh>
                  <th className="r">{t("Deductions")}</th>
                  <SortTh k="taxable" dflt="name" right>{t("Taxable")}</SortTh>
                  <SortTh k="tax" dflt="name" right>{t("Annual tax")}</SortTh>
                  <SortTh k="tds" dflt="name" right>{t("Monthly TDS")}</SortTh>
                  <SortTh k="pend" dflt="name">{t("Proofs")}</SortTh>
                </tr>
              </thead>
              <tbody>
                {rows.map((r, i) => (
                  <TaxRow key={r.id} id={r.id} name={r.name} i={i}>
                    <td><div className="person"><span className={`avatar ${avc(initials(r.name))}`}>{initials(r.name)}</span><div><b>{r.name}</b><small>{r.code}</small></div></div></td>
                    <td><span className={`chip nodot ${r.reg === "OLD" ? "info" : "gold"}`}>{r.reg === "OLD" ? t("Old") : t("New")}{r.overridden ? ` · ${t("set by Finance")}` : ""}</span></td>
                    <td className="r num muted">{rp(r.inc)}</td>
                    <td className="r num muted">{rp(r.ded)}</td>
                    <td className="r num">{rp(r.taxable)}</td>
                    <td className="r num">{rp(r.tax)}</td>
                    <td className="r num" style={{ color: "var(--accenttext)", fontWeight: 600 }}>{rp(r.tds)}</td>
                    <td>{r.pend ? <span className="chip warn">{t("{n} pending", { n: r.pend })}</span> : <span className="faint" style={{ fontSize: 12.5 }}>—</span>}</td>
                  </TaxRow>
                ))}
              </tbody>
            </table>
          </div>
          {!rows.length && <div className="empty"><b>{t("No one matches")}</b><ClearFilters /></div>}
        </Pending>
      </section>
    </div>
  );
}
