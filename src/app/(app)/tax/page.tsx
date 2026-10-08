import type { Metadata } from "next";
import Link from "next/link";
import { PageHeader } from "@/components/PageHeader";
import { ClearFilters, Pending, SearchBox, SegFilter, SortTh } from "@/components/table/Table";
import { can, requirePage } from "@/lib/auth";
import { apiData, qs as toQs } from "@/lib/backend";
import { avc, inr, initials, ymLabel } from "@/lib/format";
import { TaxRow } from "./TaxClient";

export const metadata: Metadata = { title: "Tax & TDS" };

type TaxRowView = { id: string; code: string; name: string; title: string; reg: "NEW" | "OLD"; inc: number; ded: number; taxable: number; tax: number; tds: number; pend: number; overridden: boolean };

export default async function TaxPage(props: PageProps<"/tax">) {
  const u = await requirePage("tax");
  const sp = await props.searchParams;
  const { month, fy, all, rows } = await apiData<{ month: string; fy: string; all: TaxRowView[]; rows: TaxRowView[] }>(`/api/tax${toQs(sp)}`);
  const qs = new URLSearchParams(Object.entries(sp).flatMap(([k, v]) => (typeof v === "string" ? [[k, v]] : []))).toString();
  const rp = (rupees: number) => inr(rupees * 100);

  return (
    <div className="col">
      <PageHeader eyebrow={`Finance · Tax year ${fy}`} title="Tax" em="& TDS" sub={`Monthly TDS for every employee as of ${ymLabel(month)} payroll, based on their regime and verified investment proofs.`}>
        <a className="btn" href={`/api/tax/export${qs ? `?${qs}` : ""}`} download>Export CSV</a>
      </PageHeader>
      <div className="kpis">
        <div className="card kpi enter" style={{ ["--i" as string]: 1 }}><span className="spot"></span><span className="l">Monthly TDS (all)</span><span className="v num">{rp(all.reduce((a, r) => a + r.tds, 0))}</span></div>
        <div className="card kpi enter" style={{ ["--i" as string]: 2 }}><span className="spot"></span><span className="l">On old regime</span><span className="v"><span className="num">{all.filter((r) => r.reg === "OLD").length}</span><span className="faint" style={{ fontSize: 16 }}> / {all.length}</span></span></div>
        <div className="card kpi enter" style={{ ["--i" as string]: 3 }}><span className="spot"></span><span className="l">Proofs pending</span><span className="v num">{all.reduce((a, r) => a + r.pend, 0)}</span>{can(u, "proofs.verify") && <Link className="btn btn-sm btn-ghost" style={{ alignSelf: "flex-start", marginTop: 2 }} href="/proofs?st=Pending">Review →</Link>}</div>
      </div>
      <div className="toolbar enter" style={{ ["--i" as string]: 4 }}>
        <SearchBox placeholder="Search name or ID" label="Search tax" />
        <SegFilter name="reg" dflt="All" label="Regime" options={[{ v: "All", l: "All regimes" }, { v: "NEW", l: "New" }, { v: "OLD", l: "Old" }]} />
        <span className="tcount">{rows.length} employees</span>
      </div>
      <section className="card enter" style={{ ["--i" as string]: 4 }}>
        <Pending>
          <div className="table-wrap">
            <table className="t">
              <thead>
                <tr>
                  <SortTh k="name" dflt="name">Employee</SortTh>
                  <SortTh k="reg" dflt="name">Regime</SortTh>
                  <SortTh k="inc" dflt="name" right>Projected income</SortTh>
                  <th className="r">Deductions</th>
                  <SortTh k="taxable" dflt="name" right>Taxable</SortTh>
                  <SortTh k="tax" dflt="name" right>Annual tax</SortTh>
                  <SortTh k="tds" dflt="name" right>Monthly TDS</SortTh>
                  <SortTh k="pend" dflt="name">Proofs</SortTh>
                </tr>
              </thead>
              <tbody>
                {rows.map((r, i) => (
                  <TaxRow key={r.id} id={r.id} name={r.name} i={i}>
                    <td><div className="person"><span className={`avatar ${avc(initials(r.name))}`}>{initials(r.name)}</span><div><b>{r.name}</b><small>{r.code}</small></div></div></td>
                    <td><span className={`chip nodot ${r.reg === "OLD" ? "info" : "gold"}`}>{r.reg === "OLD" ? "Old" : "New"}{r.overridden ? " · set by Finance" : ""}</span></td>
                    <td className="r num muted">{rp(r.inc)}</td>
                    <td className="r num muted">{rp(r.ded)}</td>
                    <td className="r num">{rp(r.taxable)}</td>
                    <td className="r num">{rp(r.tax)}</td>
                    <td className="r num" style={{ color: "var(--accenttext)", fontWeight: 600 }}>{rp(r.tds)}</td>
                    <td>{r.pend ? <span className="chip warn">{r.pend} pending</span> : <span className="faint" style={{ fontSize: 12.5 }}>—</span>}</td>
                  </TaxRow>
                ))}
              </tbody>
            </table>
          </div>
          {!rows.length && <div className="empty"><b>No one matches</b><ClearFilters /></div>}
        </Pending>
      </section>
    </div>
  );
}
