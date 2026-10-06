import "server-only";
import { db } from "../db";
import { listState, pick, type SearchParams } from "../list";
import { closedRows, computeRun, runStatus, type PayslipSnapshot } from "./service";

export const PAY_SORTS = ["name", "gross", "lop", "paid", "earned", "tds", "net"] as const;
export const PAY_FILTERS = ["All", "LOP", "Edited", "NEW", "OLD"] as const;

export interface PayView {
  employeeId: string; code: string; name: string; department: string; regime: "NEW" | "OLD";
  monthlyGross: number; lop: number; attendanceLop: number; paid: number; days: number; bonus: number; earned: number; pfPt: number; tds: number; other: number; net: number;
  edited: boolean; note: string | null;
}

/** Rows for a payroll month: live for an open run, snapshots for a closed one. */
export async function payrollView(month: string) {
  const { run, attendanceLocked } = await runStatus(month);
  const closed = run?.status === "CLOSED";
  let rows: PayView[];
  if (closed) {
    const snaps = await closedRows(month);
    rows = snaps.map(({ employeeId, snap: s }: { employeeId: string; snap: PayslipSnapshot }) => ({
      employeeId, code: s.employee.code, name: s.employee.name, department: s.employee.department, regime: s.regime,
      monthlyGross: s.components.filter((c) => c.rate).reduce((a, c) => a + c.rate, 0), lop: s.attendance.lopDays, attendanceLop: s.attendance.lopDays,
      paid: s.attendance.paidDays, days: s.attendance.daysInMonth, bonus: s.bonus, earned: s.grossEarned, pfPt: s.pf + s.pt, tds: s.tds, other: s.otherDeduction, net: s.net,
      edited: s.bonus > 0 || s.otherDeduction > 0 || !!s.note, note: s.note,
    }));
  } else {
    const live = await computeRun(month);
    rows = live.map((r) => ({
      employeeId: r.employeeId, code: r.code, name: r.name, department: r.department, regime: r.regime,
      monthlyGross: r.structure.basic + r.structure.hra + r.structure.lta + r.structure.special, lop: r.pay.lopDays, attendanceLop: r.attendanceLop,
      paid: r.pay.paidDays, days: r.pay.daysInMonth, bonus: r.pay.bonus, earned: r.pay.grossEarned, pfPt: r.pay.pf + r.pay.pt, tds: r.pay.tds, other: r.pay.otherDeduction, net: r.pay.net,
      edited: r.adjusted || !!r.note, note: r.note,
    }));
  }
  return { run, closed, attendanceLocked, rows };
}

export function filterPayroll(rows: PayView[], sp: SearchParams) {
  const ls = listState(sp, PAY_SORTS, "name", 100);
  const flt = pick(sp, "show", PAY_FILTERS, "All");
  const q = ls.q.toLowerCase();
  const out = rows.filter((r) =>
    (!q || `${r.name} ${r.code} ${r.department}`.toLowerCase().includes(q)) &&
    (flt === "All" || (flt === "LOP" ? r.lop > 0 : flt === "Edited" ? r.edited : r.regime === flt)));
  const get = { name: (r: PayView) => r.name, gross: (r: PayView) => r.monthlyGross, lop: (r: PayView) => r.lop, paid: (r: PayView) => r.paid, earned: (r: PayView) => r.earned, tds: (r: PayView) => r.tds, net: (r: PayView) => r.net }[ls.sort];
  out.sort((a, b) => { const x = get(a), y = get(b); return (x > y ? 1 : x < y ? -1 : 0) * (ls.dir === "asc" ? 1 : -1); });
  return out;
}

/** Months shown in the switcher: closed runs + the first open month after them (or last month). */
export async function payrollMonths(todayIso: string) {
  const runs = await db.payrollRun.findMany({ orderBy: { month: "asc" }, select: { month: true, status: true } });
  const cur = todayIso.slice(0, 7);
  const prev = new Date(Date.UTC(Number(cur.slice(0, 4)), Number(cur.slice(5)) - 2, 1)).toISOString().slice(0, 7);
  const lastClosed = runs.filter((r) => r.status === "CLOSED").at(-1)?.month;
  const open = lastClosed ? new Date(Date.UTC(Number(lastClosed.slice(0, 4)), Number(lastClosed.slice(5)), 1)).toISOString().slice(0, 7) : prev;
  const months = [...new Set([...runs.map((r) => r.month), open <= cur ? open : prev])].sort();
  return { months: months.slice(-6), dflt: open <= cur ? open : prev };
}
