import type { NextRequest } from "next/server";
import { handler } from "@/lib/api";
import { audit } from "@/lib/audit";
import { assertPerm } from "@/lib/auth";
import { csvResponse, toCsv } from "@/lib/csv";
import { taxRows } from "@/lib/tax/rows";

/** Tax & TDS export (tax.view) — same filters as the page. */
export const GET = handler(async (req: NextRequest) => {
  const u = await assertPerm("tax.view");
  const sp = Object.fromEntries(req.nextUrl.searchParams.entries());
  const { month, fy, rows } = await taxRows(sp);
  await audit({ actorId: u.id, action: "tax.export", entity: "tax", entityId: fy, after: { rows: rows.length, month } });
  return csvResponse(`tax-tds-${fy}-${month}.csv`, toCsv(
    ["Employee ID", "Name", "Regime", "Projected income", "Deductions (incl. standard)", "Taxable income", "Annual tax", "Monthly TDS", "Pending proofs"],
    rows.map((r) => [r.code, r.name, r.reg === "OLD" ? "Old" : "New", r.inc, r.ded, r.taxable, r.tax, r.tds, r.pend]),
  ));
});
