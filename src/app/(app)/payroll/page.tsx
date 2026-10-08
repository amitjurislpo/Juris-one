import type { Metadata } from "next";
import Link from "next/link";
import { Icon } from "@/components/Icon";
import { PageHeader } from "@/components/PageHeader";
import { Pending, SearchBox, SelectFilter } from "@/components/table/Table";
import { requirePage } from "@/lib/auth";
import { apiData, qs } from "@/lib/backend";
import { fmtDY, ymLabel } from "@/lib/format";
import type { PayView } from "@/lib/api-types";
import { CloseButton, MonthSeg, PayrollKpis, PayrollTable } from "./PayrollClient";

export const metadata: Metadata = { title: "Payroll" };

export default async function PayrollPage(props: PageProps<"/payroll">) {
  await requirePage("payroll");
  const sp = await props.searchParams;
  const d = await apiData<{
    month: string; months: string[]; closed: boolean; attendanceLocked: boolean; approved: boolean; closedOn: string | null; missing: string[];
    rows: PayView[]; view: PayView[]; canClose: boolean; canStructures: boolean;
  }>(`/api/payroll${qs(sp)}`);
  const { month, months, closed, attendanceLocked, approved, rows, view } = d;
  const missing = d.missing.map((fullName) => ({ fullName }));
  const stages = [
    { l: "Lock attendance", done: attendanceLocked },
    { l: "Compute", done: attendanceLocked },
    { l: "Review & edit", done: approved },
    { l: "Approve", done: approved },
    { l: "Close & publish", done: closed },
  ];
  const nowIdx = stages.findIndex((s) => !s.done);
  const tot = { n: rows.length, earned: rows.reduce((a, r) => a + r.earned, 0), lop: rows.reduce((a, r) => a + r.lop, 0), tds: rows.reduce((a, r) => a + r.tds, 0), net: rows.reduce((a, r) => a + r.net, 0) };

  return (
    <div className="col">
      <PageHeader eyebrow="Finance · Payroll" title={ymLabel(month).split(" ")[0]} em="payroll" sub="Salary comes from each employee's current salary structure. Edit LOP, bonus or deductions before closing.">
        <MonthSeg months={months} value={month} />
        {d.canClose || closed
          ? <CloseButton month={month} closed={closed} locked={attendanceLocked} count={rows.length} />
          : <span className="chip warn nodot" title="Only the Finance Manager can close payroll">Closing needs Finance Manager</span>}
      </PageHeader>

      <section className="card pad enter" style={{ ["--i" as string]: 1 }}>
        <span className="spot"></span>
        <div className="stage">
          {stages.map((s, i) => (
            <div key={s.l} className={`s ${s.done ? "done" : i === nowIdx ? "now" : ""}`}>
              <span><i>{s.done ? "✓" : i + 1}</i>{s.l}</span>
              <div className="bar"><i style={{ transform: `scaleX(${s.done ? 1 : i === nowIdx ? 0.5 : 0})` }}></i></div>
            </div>
          ))}
        </div>
        <p className="faint" style={{ margin: "14px 0 0", fontSize: 12.5 }}>
          {closed ? `Closed on ${fmtDY(d.closedOn!)} · payslips are visible to employees.` : attendanceLocked ? "Closing publishes payslips to every employee for this month." : `Attendance for ${ymLabel(month)} locks on the 1st of next month at 10:00 AM — figures can still change until then.`}
        </p>
      </section>

      {missing.length > 0 && (
        <section className="card pad enter" style={{ ["--i" as string]: 2, display: "flex", gap: 14, alignItems: "center", flexWrap: "wrap" }}>
          <span className="spot"></span>
          <span className="kpi-ico" style={{ background: "var(--warnbg)", color: "var(--warn)" }}><Icon n="info" s={18} /></span>
          <div style={{ flex: 1 }}><b>{missing.length} employee(s) without a salary structure</b><div className="muted" style={{ fontSize: 13 }}>{missing.map((e) => e.fullName).join(", ")} — set it up once and it applies every month.</div></div>
          {d.canStructures && <Link className="btn" href="/structures?set=Missing">Set up structure</Link>}
        </section>
      )}

      <PayrollKpis tot={tot} />

      <div className="toolbar enter" style={{ ["--i" as string]: 8 }}>
        <SearchBox placeholder="Search name, ID or department" label="Search payroll" />
        <SelectFilter name="show" label="Show" dflt="All" options={[{ v: "All", l: "Everyone" }, { v: "LOP", l: "With loss of pay" }, { v: "Edited", l: "Edited this month" }, { v: "NEW", l: "New regime" }, { v: "OLD", l: "Old regime" }]} />
        <span className="tcount">{view.length} of {rows.length}</span>
        <details className="menu-anchor" style={{ position: "relative" }}>
          <summary className="btn" style={{ listStyle: "none" }}><Icon n="download" s={15} />Export</summary>
          <div className="menu" style={{ minWidth: 220 }}>
            {[["bank", "Bank transfer file"], ["ecr", "PF ECR"], ["pt", "Professional tax summary"], ["tds", "TDS summary"], ["register", "Payroll register"]].map(([t, l]) => (
              <a key={t} href={`/api/payroll/export?m=${month}&type=${t}`} download>{l}</a>
            ))}
          </div>
        </details>
      </div>
      <section className="card enter" style={{ ["--i" as string]: 8 }}>
        <Pending><PayrollTable rows={view} month={month} closed={closed} /></Pending>
      </section>
    </div>
  );
}
