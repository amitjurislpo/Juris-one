import "server-only";
import { db } from "../db";
import { listState, pick, type SearchParams } from "../list";
import { getSetting } from "../settings";
import { taxPositions } from "./position";

export const TAX_SORTS = ["name", "reg", "inc", "taxable", "tax", "tds", "pend"] as const;

/** One row per India payroll employee for the Tax page and its export. */
export async function taxRows(sp: SearchParams) {
  const ls = listState(sp, TAX_SORTS, "name", 200);
  const reg = pick(sp, "reg", ["All", "NEW", "OLD"] as const, "All");
  const { fy } = await getSetting("tax.policy");
  const { month, rows } = await taxPositions();
  const pending = await db.declarationItem.groupBy({ by: ["employeeId"], where: { fy, status: "PENDING" }, _count: true });
  const all = rows.map((r) => ({
    id: r.employeeId, code: r.code, name: r.name, title: r.designation, reg: r.tax.regime,
    inc: r.tax.income, ded: r.tax.current.std + r.tax.current.deductions, taxable: r.tax.current.taxable, tax: r.tax.current.total, tds: r.tax.monthly,
    pend: pending.find((p) => p.employeeId === r.employeeId)?._count ?? 0, overridden: r.tax.regimeOverridden,
  }));
  const q = ls.q.toLowerCase();
  const out = all.filter((r) => (!q || `${r.name} ${r.code}`.toLowerCase().includes(q)) && (reg === "All" || r.reg === reg));
  const key = ls.sort;
  out.sort((a, b) => { const x = a[key], y = b[key]; return (x > y ? 1 : x < y ? -1 : 0) * (ls.dir === "asc" ? 1 : -1); });
  return { month, fy, all, rows: out };
}
