import type { Metadata } from "next";
import { PageHeader } from "@/components/PageHeader";
import { Icon } from "@/components/Icon";
import { requirePage } from "@/lib/auth";
import { db } from "@/lib/db";
import { dateToIso, fmtDY, todayIn, ymLabel } from "@/lib/format";
import type { PayslipSnapshot } from "@/lib/payroll/service";
import { SlipActions } from "./SlipViewer";

export const metadata: Metadata = { title: "Payslips" };

/** A month appears only after Finance closes it; open months show "Processing" (REQUIREMENTS §5.11). */
export default async function PayslipsPage() {
  const u = await requirePage("payslips");
  const india = u.region === "IN";
  const today = todayIn("Asia/Kolkata");
  const [slips, openRuns, hasStructure] = await Promise.all([
    db.payslip.findMany({ where: { employeeId: u.id, run: { status: "CLOSED" } }, include: { run: true }, orderBy: { run: { month: "desc" } } }),
    db.payrollRun.findMany({ where: { status: "OPEN" }, orderBy: { month: "desc" } }),
    db.salaryStructure.count({ where: { employeeId: u.id } }),
  ]);
  // the month currently being processed: the open run, or last month if no run exists yet
  const prev = new Date(Date.UTC(Number(today.slice(0, 4)), Number(today.slice(5, 7)) - 2, 1)).toISOString().slice(0, 7);
  const processing = india && hasStructure ? [...new Set([...openRuns.map((r) => r.month), slips.some((s) => s.run.month === prev) ? null : prev].filter((x): x is string => !!x))].filter((m) => !slips.some((s) => s.run.month === m)) : [];
  const months = [
    ...processing.map((m) => ({ ym: m, closed: false as const })),
    ...slips.map((s) => ({ ym: s.run.month, closed: true as const, snap: s.snapshot as unknown as PayslipSnapshot, paidOn: s.run.payDate ? dateToIso(s.run.payDate) : null })),
  ].sort((a, b) => b.ym.localeCompare(a.ym));

  return (
    <div className="col">
      <PageHeader eyebrow="Payslips" title="My" em="payslips" sub="A month's payslip appears here once Finance closes that month's payroll." />
      <div className="slip-grid">
        {months.map((m, i) => (
          <div key={m.ym} className={`card slip-card enter${m.closed ? "" : " locked"}`} style={{ ["--i" as string]: i }}>
            <span className="spot"></span>
            <span className="slip-ico"><Icon n={m.closed ? "doc" : "clock"} s={20} /></span>
            <span style={{ display: "flex", flexDirection: "column", gap: 3, textAlign: "left", flex: 1, minWidth: 0 }}>
              <b className="display" style={{ fontSize: 24 }}>{ymLabel(m.ym)}</b>
              <span className="faint" style={{ fontSize: 12.5 }}>{m.closed ? `Paid on ${m.paidOn ? fmtDY(m.paidOn) : "—"} · ${m.snap.attendance.paidDays} paid days` : "Payroll in progress — available once Finance closes it"}</span>
            </span>
            {m.closed ? <SlipActions snap={m.snap} code={u.code} /> : <span className="chip warn">Processing</span>}
          </div>
        ))}
      </div>
      {!months.length && (
        <div className="empty">
          <b className="display">No payslips yet</b>
          <span className="muted">{india ? "Your first payslip will appear after your first payroll is closed." : "Payslips here are for the India payroll. US team pay statements come from the US payroll provider."}</span>
        </div>
      )}
    </div>
  );
}
