import "server-only";
import { db } from "../db";
import { todayIn } from "../format";
import { computeRun, type PayRow } from "../payroll/service";
import { projectTax, type DeclMode, type TaxProjection } from "./service";

/** The payroll month tax is projected for: the first month after the last closed run. */
export async function openPayrollMonth(): Promise<string> {
  const today = todayIn("Asia/Kolkata").slice(0, 7);
  const last = await db.payrollRun.findFirst({ where: { status: "CLOSED" }, orderBy: { month: "desc" } });
  if (!last) return new Date(Date.UTC(Number(today.slice(0, 4)), Number(today.slice(5)) - 2, 1)).toISOString().slice(0, 7);
  const next = new Date(Date.UTC(Number(last.month.slice(0, 4)), Number(last.month.slice(5)), 1)).toISOString().slice(0, 7);
  return next <= today ? next : today;
}

/** Re-project one payroll row with different declarations / regime (same month figures). */
export async function reproject(row: PayRow, month: string, opts: { decl?: DeclMode; regime?: "NEW" | "OLD" }): Promise<TaxProjection> {
  const p = row.pay;
  return projectTax(row.employeeId, month, { gross: p.grossEarned, basic: p.components[0].earned, hra: p.components[1].earned, pf: p.pf }, row.structure.basic + row.structure.hra + row.structure.lta + row.structure.special, row.structure.basic, row.structure.hra, opts);
}

/** Tax positions for the open payroll month; `ids` limits it to some employees. */
export async function taxPositions(ids?: string[]) {
  const month = await openPayrollMonth();
  const rows = await computeRun(month, ids);
  return { month, rows };
}
