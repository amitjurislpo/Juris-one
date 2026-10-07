import "server-only";
import { addDays, dowOf, summarize } from "../attendance/engine";
import { attendanceFor, attendancePolicy, isMonthLocked, loadAttEmployees } from "../attendance/service";
import { db } from "../db";
import { dateToIso, isoToDate } from "../format";
import { getSetting } from "../settings";
import { projectTax, type TaxProjection } from "../tax/service";
import { computePay, daysInMonth, eligibleDays, grossOf, rupeesInWords, type PayResult, type Structure } from "./engine";


const lastDay = (ym: string) => `${ym}-${String(daysInMonth(ym)).padStart(2, "0")}`;

/** Structure effective on a date: the latest revision with effective_from ≤ date. */
export async function structureOn(employeeId: string, iso: string) {
  return db.salaryStructure.findFirst({ where: { employeeId, effectiveFrom: { lte: isoToDate(iso) } }, orderBy: { effectiveFrom: "desc" } });
}

export interface PayRow {
  employeeId: string;
  code: string;
  name: string;
  designation: string;
  department: string;
  doj: string;
  regime: "NEW" | "OLD";
  structure: Structure;
  pay: PayResult;
  attendanceLop: number; // from attendance (absences, unpaid leave, late marks)
  adjusted: boolean;
  note: string | null;
  weeklyOffs: number;
  holidays: number;
  present: number;
  annualTax: number; // ₹
  tax: TaxProjection;
}

export interface PayslipSnapshot {
  version: 1;
  ym: string;
  company: { name: string; address: string };
  employee: { code: string; name: string; designation: string; department: string; doj: string; panLast4: string | null; uanLast4: string | null; bankName: string | null; bankLast4: string | null };
  regime: "NEW" | "OLD";
  payDate: string;
  attendance: { daysInMonth: number; eligibleDays: number; weeklyOffs: number; holidays: number; present: number; lopDays: number; paidDays: number };
  components: { label: string; rate: number; earned: number }[];
  bonus: number;
  grossEarned: number;
  pf: number;
  pt: number;
  tds: number;
  otherDeduction: number;
  deductions: { label: string; amount: number }[];
  totalDeductions: number;
  net: number;
  netWords: string;
  note: string | null;
}

/** India employees on payroll that month (structure on the last day, employed some day of it). */
async function payrollEmployees(ym: string, ids?: string[]) {
  const last = lastDay(ym);
  return db.employee.findMany({
    where: { ...(ids ? { id: { in: ids } } : {}), region: "IN", dateOfJoining: { lte: isoToDate(last) }, OR: [{ exitDate: null }, { exitDate: { gte: isoToDate(`${ym}-01`) } }], salaryStructures: { some: { effectiveFrom: { lte: isoToDate(last) } } } },
    include: { department: { select: { name: true } } },
    orderBy: { fullName: "asc" },
  });
}

/** Live computation for an open run (optionally for some employees only). */
export async function computeRun(ym: string, ids?: string[]): Promise<PayRow[]> {
  const run = await db.payrollRun.findUnique({ where: { month: ym }, include: { adjustments: true } });
  const pay = await getSetting("payroll.policy");
  const policy = await attendancePolicy();
  const emps = await payrollEmployees(ym, ids);
  const att = await loadAttEmployees({ id: { in: emps.map((e) => e.id) } });
  const first = `${ym}-01`, last = lastDay(ym);
  const days = await attendanceFor(att, first, last, new Date(Math.max(Date.now(), Date.parse(`${last}T23:59:59Z`) + 2 * 86400_000)));
  const holidays = await db.holiday.findMany({ where: { date: { gte: isoToDate(first), lte: isoToDate(last) }, region: { in: ["IN", "ALL"] } } });
  const D = daysInMonth(ym);
  const rows: PayRow[] = [];
  for (const e of emps) {
    const doj = dateToIso(e.dateOfJoining), exit = e.exitDate ? dateToIso(e.exitDate) : null;
    const elig = eligibleDays(ym, doj, exit);
    if (!elig) continue;
    const st = (await structureOn(e.id, last))!;
    const structure: Structure = { basic: st.basic, hra: st.hra, lta: st.lta, special: st.special };
    const from = doj > first ? doj : first, to = exit && exit < last ? exit : last;
    let wo = 0, hol = 0;
    for (let d = from; d <= to; d = addDays(d, 1)) {
      if (policy.weeklyOffs.includes(dowOf(d))) wo++;
      else if (holidays.some((h) => dateToIso(h.date) === d)) hol++;
    }
    const ds = days.get(e.id);
    const s = ds ? summarize(ds.filter((x) => x.date >= from && x.date <= to), policy) : null;
    const attendanceLop = s ? s.lopDays : 0;
    const adj = run?.adjustments.find((a) => a.employeeId === e.id);
    const lop = adj?.lopDays != null ? Number(adj.lopDays) : attendanceLop;
    const base = { structure, daysInMonth: D, eligibleDays: elig, lopDays: lop, bonus: adj?.bonus ?? 0, otherDeduction: adj?.otherDeduction ?? 0, pfRate: pay.pfRate, pfWageCeiling: pay.pfWageCeiling * 100, professionalTax: pay.professionalTax.monthly * 100 };
    const pre = computePay({ ...base, tds: 0 });
    const tax = await projectTax(e.id, ym, { gross: pre.grossEarned, basic: pre.components[0].earned, hra: pre.components[1].earned, pf: pre.pf }, grossOf(structure), structure.basic, structure.hra);
    const result = computePay({ ...base, tds: tax.monthly * 100 });
    rows.push({
      employeeId: e.id, code: e.code, name: e.fullName, designation: e.designation, department: e.department?.name ?? "—", doj, regime: tax.regime,
      structure, pay: result, attendanceLop, adjusted: !!adj && (adj.lopDays != null || adj.bonus > 0 || adj.otherDeduction > 0), note: adj?.note ?? null,
      weeklyOffs: wo, holidays: hol, present: Math.max(0, elig - wo - hol - result.lopDays), annualTax: tax.current.total, tax,
    });
  }
  return rows;
}

export function toSnapshot(row: PayRow, ym: string, emp: { panLast4: string | null; uanLast4: string | null; bankName: string | null; bankAccountLast4: string | null }, company: { name: string; address: string }, payDate: string): PayslipSnapshot {
  const p = row.pay;
  const comps = p.components.map((c) => ({ ...c }));
  if (p.bonus) comps.push({ label: "Bonus / arrears", rate: 0, earned: p.bonus });
  const deductions = [
    { label: "Provident fund (12% of Basic)", amount: p.pf },
    { label: "Professional tax", amount: p.pt },
    { label: "Income tax (TDS)", amount: p.tds },
    ...(p.otherDeduction ? [{ label: "Other deduction", amount: p.otherDeduction }] : []),
  ];
  return {
    version: 1, ym, company, regime: row.regime, payDate,
    employee: { code: row.code, name: row.name, designation: row.designation, department: row.department, doj: row.doj, panLast4: emp.panLast4, uanLast4: emp.uanLast4, bankName: emp.bankName, bankLast4: emp.bankAccountLast4 },
    attendance: { daysInMonth: p.daysInMonth, eligibleDays: p.eligibleDays, weeklyOffs: row.weeklyOffs, holidays: row.holidays, present: row.present, lopDays: p.lopDays, paidDays: p.paidDays },
    components: comps, bonus: p.bonus, grossEarned: p.grossEarned, pf: p.pf, pt: p.pt, tds: p.tds, otherDeduction: p.otherDeduction,
    deductions, totalDeductions: p.totalDeductions, net: p.net, netWords: rupeesInWords(p.net / 100), note: row.note,
  };
}

/** Snapshot rows of a closed run, shaped like live rows (closed payslips never change). */
export async function closedRows(ym: string) {
  const run = await db.payrollRun.findUnique({ where: { month: ym }, include: { payslips: { include: { employee: { select: { id: true } } } } } });
  return (run?.payslips ?? []).map((p) => ({ employeeId: p.employeeId, snap: p.snapshot as unknown as PayslipSnapshot }));
}

export async function runStatus(ym: string) {
  const policy = await attendancePolicy();
  const run = await db.payrollRun.findUnique({ where: { month: ym } });
  return { run, attendanceLocked: isMonthLocked(ym, policy) };
}
