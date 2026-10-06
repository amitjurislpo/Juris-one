"use server";

import { revalidatePath } from "next/cache";
import { z } from "zod";
import { run, UserError, type ActionResult } from "@/lib/action";
import { audit } from "@/lib/audit";
import { assertPerm } from "@/lib/auth";
import { db } from "@/lib/db";
import { dateToIso, fmtDY, isoToDate, ymLabel } from "@/lib/format";
import { requestMeta } from "@/lib/request";

const schema = z.object({
  employeeId: z.string().uuid(),
  effectiveFrom: z.string().regex(/^\d{4}-\d{2}-\d{2}$/, "Pick the effective date"),
  reason: z.enum(["JOINING", "APPRAISAL", "PROMOTION", "CORRECTION"]),
  basic: z.number().int().min(1, "Basic is required").max(100_000_000),
  hra: z.number().int().min(0).max(100_000_000),
  lta: z.number().int().min(0).max(100_000_000),
  special: z.number().int().min(0).max(100_000_000),
});

/**
 * Adds a salary revision (REQUIREMENTS §5.7). History is never edited — only added to.
 * Amounts arrive in rupees and are stored in paise. India employees only.
 */
export async function addStructure(i: z.input<typeof schema>): Promise<ActionResult> {
  return run(async () => {
    const u = await assertPerm("struct.manage");
    const f = schema.parse(i);
    const e = await db.employee.findUnique({ where: { id: f.employeeId }, select: { fullName: true, region: true, dateOfJoining: true, status: true } });
    if (!e) throw new UserError("Employee not found.");
    if (e.region !== "IN") throw new UserError("Salary structures are for the India payroll only.");
    if (e.status === "EXITED") throw new UserError("This person is a past employee.");
    if (f.effectiveFrom < dateToIso(e.dateOfJoining)) throw new UserError("The structure can't start before the joining date.");
    const lastClosed = await db.payrollRun.findFirst({ where: { status: "CLOSED" }, orderBy: { month: "desc" } });
    if (lastClosed && f.effectiveFrom.slice(0, 7) <= lastClosed.month)
      throw new UserError(`Payroll is closed up to ${ymLabel(lastClosed.month)}. Start the revision after that and pay any difference as arrears.`);
    const clash = await db.salaryStructure.findUnique({ where: { employeeId_effectiveFrom: { employeeId: f.employeeId, effectiveFrom: isoToDate(f.effectiveFrom) } } });
    if (clash) throw new UserError(`There's already a revision effective ${fmtDY(f.effectiveFrom)}. History can't be edited — pick another date.`);
    const data = { basic: f.basic * 100, hra: f.hra * 100, lta: f.lta * 100, special: f.special * 100 };
    const prev = await db.salaryStructure.findFirst({ where: { employeeId: f.employeeId, effectiveFrom: { lt: isoToDate(f.effectiveFrom) } }, orderBy: { effectiveFrom: "desc" } });
    await db.$transaction(async (tx) => {
      const s = await tx.salaryStructure.create({ data: { employeeId: f.employeeId, effectiveFrom: isoToDate(f.effectiveFrom), reason: f.reason, createdById: u.id, ...data } });
      await audit({
        actorId: u.id, action: "salary.structure_change", entity: "salary_structure", entityId: s.id,
        before: prev ? { effectiveFrom: dateToIso(prev.effectiveFrom), basic: prev.basic, hra: prev.hra, lta: prev.lta, special: prev.special } : null,
        after: { employeeId: f.employeeId, effectiveFrom: f.effectiveFrom, reason: f.reason, ...data }, ip: (await requestMeta()).ip,
      }, tx);
      // the "salary structure pending" reminder is done
      await tx.notification.updateMany({ where: { readAt: null, AND: [{ text: { startsWith: e.fullName } }, { text: { contains: "salary structure pending" } }] }, data: { readAt: new Date() } });
    });
    ["/structures", "/payroll", "/tax", "/people"].forEach((p) => revalidatePath(p));
    return { ok: true, msg: `Salary structure saved for ${e.fullName} · effective ${fmtDY(f.effectiveFrom)}` };
  });
}
