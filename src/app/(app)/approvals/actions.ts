"use server";

import { revalidatePath } from "next/cache";
import { z } from "zod";
import { run, UserError, type ActionResult } from "@/lib/action";
import { addDays } from "@/lib/attendance/engine";
import { attendanceFor, attendancePolicy, isMonthLocked, loadAttEmployees } from "@/lib/attendance/service";
import { audit } from "@/lib/audit";
import { assertPerm } from "@/lib/auth";
import { db } from "@/lib/db";
import { dateToIso, fmtD, isoToDate } from "@/lib/format";
import { compOffDays } from "@/lib/leave/rules";
import { postUsage, previewRequest } from "@/lib/leave/service";
import { notifyDecision } from "@/lib/notify";
import { requestMeta } from "@/lib/request";

const decision = z.object({ id: z.string().uuid(), approve: z.boolean(), note: z.string().trim().max(300).optional() });
const refresh = () => ["/approvals", "/leave", "/attendance", "/home", "/register", "/team"].forEach((p) => revalidatePath(p));
const KIND: Record<string, string> = { ABSENT: "Absence regularization", LATE_CHECKIN: "Late check-in regularization", MISSED_CHECKOUT: "Missed check-out regularization", HOLIDAY_WORK: "Comp-off request" };

/** HR (`appr.leave`; Admin too). Approving posts the used days to the ledger; any shortfall is LOP. */
export async function decideLeave(i: z.input<typeof decision>): Promise<ActionResult> {
  return run(async () => {
    const u = await assertPerm("appr.leave");
    const { id, approve, note } = decision.parse(i);
    const r = await db.leaveRequest.findUnique({ where: { id }, include: { type: true, employee: { select: { fullName: true } } } });
    if (!r) throw new UserError("Request not found.");
    if (r.employeeId === u.id) throw new UserError("You can't decide your own leave.");
    if (r.status !== "PENDING") throw new UserError("This request was already decided.");

    let lopDays = Number(r.lopDays);
    let paidDays = Number(r.days) - lopDays;
    if (approve) {
      const [emp] = await loadAttEmployees({ id: r.employeeId });
      const p = await previewRequest(emp, r.leaveType, dateToIso(r.fromDate), dateToIso(r.toDate), r.part, { excludeRequestId: r.id, forDecision: true });
      if (p.error) throw new UserError(p.error);
      lopDays = p.lopDays; // balance may have changed since applying
      paidDays = p.paidDays;
    }
    const ip = (await requestMeta()).ip;
    await db.$transaction(async (tx) => {
      const { count } = await tx.leaveRequest.updateMany({
        where: { id, status: "PENDING" },
        data: { status: approve ? "APPROVED" : "DECLINED", decidedById: u.id, decidedAt: new Date(), lopDays },
      });
      if (!count) throw new UserError("Someone else decided this request a moment ago.");
      if (approve) await postUsage(tx, r, paidDays);
      await audit({ actorId: u.id, action: approve ? "leave.approve" : "leave.decline", entity: "leave_request", entityId: id, before: { status: "PENDING" }, after: { status: approve ? "APPROVED" : "DECLINED", paidDays, lopDays, note }, ip }, tx);
    });
    const span = r.fromDate.getTime() === r.toDate.getTime() ? fmtD(dateToIso(r.fromDate)) : `${fmtD(dateToIso(r.fromDate))} – ${fmtD(dateToIso(r.toDate))}`;
    await notifyDecision(r.employeeId, `${r.type.name} (${span})`, approve, u.name, "/leave", note);
    refresh();
    return { ok: true, msg: `${approve ? "Approved" : "Declined"} ${r.employee.fullName}’s ${r.type.name.toLowerCase()}` };
  });
}

/**
 * Regularization goes to HR, Finance and Admin together (`appr.reg`); the first decision is final.
 * Approval effects: absent → paid day; late → mark removed; missed check-out → punch added;
 * holiday work → comp-off credit.
 */
export async function decideRegularization(i: z.input<typeof decision>): Promise<ActionResult> {
  return run(async () => {
    const u = await assertPerm("appr.reg");
    const { id, approve, note } = decision.parse(i);
    const r = await db.regularizationRequest.findUnique({ where: { id }, include: { employee: { select: { fullName: true } } } });
    if (!r) throw new UserError("Request not found.");
    if (r.employeeId === u.id) throw new UserError("You can't decide your own request.");
    if (r.status !== "PENDING") throw new UserError("This request was already decided.");
    const date = dateToIso(r.workDate);
    const policy = await attendancePolicy();
    if (approve && isMonthLocked(date.slice(0, 7), policy)) throw new UserError("That month's attendance is locked — decline this and adjust LOP in payroll instead.");

    let compOff = 0;
    if (approve && r.kind === "HOLIDAY_WORK") {
      const [emp] = await loadAttEmployees({ id: r.employeeId });
      const d = (await attendanceFor([emp], date, date)).get(emp.id)![0];
      compOff = compOffDays(d.workedMinutes, policy.fullDayHours, policy.halfDayHours);
      if (!compOff) throw new UserError("Less than a half day was worked — no comp-off is due.");
    }
    if (approve && r.kind === "MISSED_CHECKOUT" && !r.proposedOut) throw new UserError("The request has no check-out time.");

    const ip = (await requestMeta()).ip;
    await db.$transaction(async (tx) => {
      const { count } = await tx.regularizationRequest.updateMany({
        where: { id, status: "PENDING" },
        data: { status: approve ? "APPROVED" : "DECLINED", decidedById: u.id, decidedAt: new Date(), decidedRole: u.roleName },
      });
      if (!count) throw new UserError("Someone else decided this request a moment ago.");
      if (approve && r.kind === "MISSED_CHECKOUT") {
        if (r.proposedIn) await tx.punch.create({ data: { employeeId: r.employeeId, at: r.proposedIn, kind: "IN", source: "REGULARIZATION" } });
        await tx.punch.create({ data: { employeeId: r.employeeId, at: r.proposedOut!, kind: "OUT", source: "REGULARIZATION" } });
      }
      if (compOff) {
        const co = await tx.leaveType.findUniqueOrThrow({ where: { code: "CO" } });
        await tx.leaveLedger.create({ data: { employeeId: r.employeeId, leaveType: "CO", delta: compOff, reason: "COMP_OFF_CREDIT", refId: r.id, expiresOn: isoToDate(addDays(date, co.expiryDays ?? 60)), note: `Worked on ${date}` } });
      }
      await audit({ actorId: u.id, action: approve ? "regularization.approve" : "regularization.decline", entity: "regularization", entityId: id, before: { status: "PENDING" }, after: { status: approve ? "APPROVED" : "DECLINED", kind: r.kind, date, compOff, note, role: u.roleName }, ip }, tx);
    });
    await notifyDecision(r.employeeId, `${KIND[r.kind]} for ${fmtD(date)}`, approve, `${u.name} (${u.roleName})`, `/attendance?m=${date.slice(0, 7)}&d=${date}`, note);
    refresh();
    return { ok: true, msg: `${approve ? "Approved" : "Declined"} ${r.employee.fullName}’s ${KIND[r.kind].toLowerCase()}${compOff ? ` · ${compOff} comp-off credited` : ""}` };
  });
}
