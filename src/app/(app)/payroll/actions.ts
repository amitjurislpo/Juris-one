"use server";

import { revalidatePath } from "next/cache";
import { z } from "zod";
import { run, UserError, type ActionResult } from "@/lib/action";
import { audit } from "@/lib/audit";
import { assertPerm } from "@/lib/auth";
import { db } from "@/lib/db";
import { ymLabel } from "@/lib/format";
import { closeRun } from "@/lib/payroll/close";
import { runStatus } from "@/lib/payroll/service";
import { requestMeta } from "@/lib/request";

const ym = z.string().regex(/^\d{4}-\d{2}$/);
const refresh = () => ["/payroll", "/payslips", "/home", "/overview", "/tax"].forEach((p) => revalidatePath(p));

async function openRun(month: string) {
  const { run } = await runStatus(month);
  if (run?.status === "CLOSED") throw new UserError(`${ymLabel(month)} payroll is closed. Corrections go into the next month as arrears.`);
  return run ?? db.payrollRun.create({ data: { month } });
}

const adjSchema = z.object({
  month: ym,
  employeeId: z.string().uuid(),
  lopDays: z.number().min(0).max(31).multipleOf(0.5).nullable(),
  bonus: z.number().int().min(0).max(100_000_000), // rupees
  otherDeduction: z.number().int().min(0).max(100_000_000),
  note: z.string().trim().max(200),
});

/** Finance edits LOP / bonus / other deduction / note while the run is open (each edit audited). */
export async function saveAdjustment(i: z.input<typeof adjSchema>): Promise<ActionResult> {
  return run(async () => {
    const u = await assertPerm("pay.view");
    const f = adjSchema.parse(i);
    const r = await openRun(f.month);
    const emp = await db.employee.findUnique({ where: { id: f.employeeId }, select: { fullName: true, region: true } });
    if (!emp || emp.region !== "IN") throw new UserError("Only India payroll employees can be edited.");
    const key = { runId_employeeId: { runId: r.id, employeeId: f.employeeId } };
    const before = await db.payrollAdjustment.findUnique({ where: key });
    const data = { lopDays: f.lopDays, bonus: f.bonus * 100, otherDeduction: f.otherDeduction * 100, note: f.note || null, updatedById: u.id };
    await db.$transaction(async (tx) => {
      await tx.payrollAdjustment.upsert({ where: key, create: { runId: r.id, employeeId: f.employeeId, ...data }, update: data });
      await tx.payrollRun.update({ where: { id: r.id }, data: { stage: "REVIEW", approvedAt: null, approvedById: null } });
      await audit({
        actorId: u.id, action: "payroll.edit", entity: "payroll_adjustment", entityId: `${f.month}:${f.employeeId}`,
        before: before ? { lopDays: before.lopDays === null ? null : Number(before.lopDays), bonus: before.bonus, otherDeduction: before.otherDeduction, note: before.note } : null,
        after: { lopDays: f.lopDays, bonus: data.bonus, otherDeduction: data.otherDeduction, note: data.note }, ip: (await requestMeta()).ip,
      }, tx);
    });
    refresh();
    return { ok: true, msg: `Saved payroll changes for ${emp.fullName}` };
  });
}

export async function resetAdjustment(month: string, employeeId: string): Promise<ActionResult> {
  return run(async () => {
    const u = await assertPerm("pay.view");
    ym.parse(month);
    z.string().uuid().parse(employeeId);
    const r = await openRun(month);
    const { count } = await db.payrollAdjustment.deleteMany({ where: { runId: r.id, employeeId } });
    if (count) await audit({ actorId: u.id, action: "payroll.edit", entity: "payroll_adjustment", entityId: `${month}:${employeeId}`, after: { reset: true } });
    refresh();
    return { ok: true, msg: "Back to attendance figures" };
  });
}

/** Approve & close (pay.close only): snapshots every payslip and publishes them. */
export async function approveAndClose(month: string): Promise<ActionResult> {
  return run(async () => {
    const u = await assertPerm("pay.close");
    ym.parse(month);
    const { run: existing, attendanceLocked } = await runStatus(month);
    if (!attendanceLocked) throw new UserError("Attendance for this month locks on the 1st of next month at 10:00 AM. Close after that.");
    if (existing?.status === "CLOSED") throw new UserError("Already closed.");
    const r = existing ?? (await db.payrollRun.create({ data: { month } }));
    await db.payrollRun.update({ where: { id: r.id }, data: { stage: "APPROVED", approvedById: u.id, approvedAt: new Date() } });
    await audit({ actorId: u.id, action: "payroll.approve", entity: "payroll_run", entityId: r.id, after: { month } });
    const res = await closeRun(month, u.id).catch((e: Error) => { throw new UserError(e.message); });
    refresh();
    return { ok: true, msg: `${ymLabel(month)} payroll closed — ${res.payslips} payslips published` };
  });
}
