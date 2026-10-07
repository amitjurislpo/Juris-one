"use server";

import { revalidatePath } from "next/cache";
import { z } from "zod";
import { run, UserError, type ActionResult } from "@/lib/action";
import { audit } from "@/lib/audit";
import { assertPerm } from "@/lib/auth";
import { db } from "@/lib/db";
import { notify } from "@/lib/notify";
import { requestMeta } from "@/lib/request";
import { getSetting } from "@/lib/settings";
import type { TaxProjection } from "@/lib/tax/service";
import { taxPositions } from "@/lib/tax/position";

const refresh = () => ["/tax", "/payroll", "/declare", "/proofs"].forEach((p) => revalidatePath(p));
const id = z.string().uuid();

export async function taxDetail(employeeId: string): Promise<ActionResult<{ month: string; tax: TaxProjection; name: string; code: string; title: string }>> {
  return run(async () => {
    await assertPerm("tax.view");
    id.parse(employeeId);
    const pos = await taxPositions([employeeId]);
    const row = pos.rows[0];
    if (!row) throw new UserError("No salary structure — nothing to compute yet.");
    return { ok: true, data: { month: pos.month, tax: row.tax, name: row.name, code: row.code, title: row.designation } };
  });
}

/** Finance overrides an employee's regime (REQUIREMENTS §5.9). */
export async function overrideRegime(employeeId: string, regime: "NEW" | "OLD"): Promise<ActionResult> {
  return run(async () => {
    const u = await assertPerm("tax.view");
    id.parse(employeeId);
    z.enum(["NEW", "OLD"]).parse(regime);
    const { fy } = await getSetting("tax.policy");
    const before = await db.taxProfile.findUnique({ where: { employeeId_fy: { employeeId, fy } } });
    await db.taxProfile.upsert({ where: { employeeId_fy: { employeeId, fy } }, create: { employeeId, fy, regime, regimeOverridden: true }, update: { regime, regimeOverridden: true } });
    await audit({ actorId: u.id, action: "tax.regime_change", entity: "tax_profile", entityId: `${employeeId}:${fy}`, before: { regime: before?.regime ?? "NEW" }, after: { regime, by: "finance_override" }, ip: (await requestMeta()).ip });
    if ((before?.regime ?? "NEW") !== regime) await notify(employeeId, `Finance set your tax regime to the ${regime === "NEW" ? "new" : "old"} regime`, "/declare");
    refresh();
    return { ok: true, msg: `Regime set to ${regime === "NEW" ? "new" : "old"} — TDS updates from this payroll` };
  });
}

const prevSchema = z.object({ employeeId: id, income: z.number().int().min(0).max(1_000_000_000), tds: z.number().int().min(0).max(1_000_000_000) });

/** Previous employer income and TDS this FY (Form 12B), in rupees. */
export async function setPreviousEmployer(i: z.input<typeof prevSchema>): Promise<ActionResult> {
  return run(async () => {
    const u = await assertPerm("tax.view");
    const f = prevSchema.parse(i);
    if (f.tds > f.income) throw new UserError("TDS can't be more than the income.");
    const { fy } = await getSetting("tax.policy");
    const before = await db.taxProfile.findUnique({ where: { employeeId_fy: { employeeId: f.employeeId, fy } } });
    const data = { prevEmployerIncome: BigInt(f.income * 100), prevEmployerTds: BigInt(f.tds * 100) };
    await db.taxProfile.upsert({ where: { employeeId_fy: { employeeId: f.employeeId, fy } }, create: { employeeId: f.employeeId, fy, ...data }, update: data });
    await audit({ actorId: u.id, action: "tax.previous_employer", entity: "tax_profile", entityId: `${f.employeeId}:${fy}`, before: before ? { income: Number(before.prevEmployerIncome), tds: Number(before.prevEmployerTds) } : null, after: { income: f.income * 100, tds: f.tds * 100 }, ip: (await requestMeta()).ip });
    refresh();
    return { ok: true, msg: "Previous employer figures saved" };
  });
}
