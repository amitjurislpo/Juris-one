"use server";

import { revalidatePath } from "next/cache";
import { z } from "zod";
import { run, UserError, type ActionResult } from "@/lib/action";
import { addDays, atLocal } from "@/lib/attendance/engine";
import { attendanceFor, attendancePolicy, currentWorkDate, isMonthLocked, loadAttEmployees } from "@/lib/attendance/service";
import { audit } from "@/lib/audit";
import { assertPerm } from "@/lib/auth";
import { db } from "@/lib/db";
import { isoToDate } from "@/lib/format";
import { requestMeta } from "@/lib/request";

const schema = z.object({
  date: z.string().regex(/^\d{4}-\d{2}-\d{2}$/),
  kind: z.enum(["ABSENT", "LATE_CHECKIN", "MISSED_CHECKOUT", "HOLIDAY_WORK"]),
  proposedIn: z.union([z.string().regex(/^\d{2}:\d{2}$/), z.literal("")]),
  proposedOut: z.union([z.string().regex(/^\d{2}:\d{2}$/), z.literal("")]),
  reason: z.string().trim().max(500, "Keep the reason under 500 characters."),
});

export type RegInput = z.infer<typeof schema>;

/**
 * Regularization request (REQUIREMENTS §5.4). Goes to HR, Finance and Admin together;
 * allowed only before that month's attendance lock.
 */
export async function requestRegularization(input: RegInput): Promise<ActionResult> {
  return run(async () => {
    const u = await assertPerm("self");
    const f = schema.parse(input);
    const [emp] = await loadAttEmployees({ id: u.id });
    if (!emp) throw new UserError("Attendance isn't tracked for your account.");
    const policy = await attendancePolicy();
    const now = new Date();
    if (f.date > currentWorkDate(emp, now)) throw new UserError("You can't regularize a future day.");
    if (isMonthLocked(f.date.slice(0, 7), policy, now)) throw new UserError("Attendance for this month is locked. Ask Finance to adjust LOP in payroll.");

    const day = (await attendanceFor([emp], f.date, f.date, now)).get(emp.id)![0];
    const fits = {
      ABSENT: day.lopDays > 0 && day.status !== "LEAVE",
      LATE_CHECKIN: day.late,
      MISSED_CHECKOUT: day.missedCheckout || (day.punches.length > 0 && !day.live && !day.lastOut),
      HOLIDAY_WORK: day.offDayWork,
    }[f.kind];
    if (!fits) throw new UserError({ ABSENT: "This day isn't marked as loss of pay.", LATE_CHECKIN: "There's no late mark on this day.", MISSED_CHECKOUT: "There's no missing check-out on this day.", HOLIDAY_WORK: "You need 4h+ of work on a holiday or weekly off for a comp-off." }[f.kind]);

    const dup = await db.regularizationRequest.findFirst({ where: { employeeId: u.id, workDate: isoToDate(f.date), kind: f.kind, status: { in: ["PENDING", "APPROVED"] } } });
    if (dup) throw new UserError(dup.status === "PENDING" ? "You already have a pending request for this day." : "This day is already regularized.");

    // Proposed times are local to the employee; a night-shift check-out rolls to the next day.
    const toInstant = (hm: string, after?: Date) => {
      let t = atLocal(f.date, hm, emp.tz);
      if (after && t <= after) t = atLocal(addDays(f.date, 1), hm, emp.tz);
      else if (!after && hm < "12:00" && emp.shift.startLocal >= "12:00") t = atLocal(addDays(f.date, 1), hm, emp.tz);
      return t;
    };
    const proposedIn = f.proposedIn ? toInstant(f.proposedIn) : null;
    const proposedOut = f.proposedOut ? toInstant(f.proposedOut, proposedIn ?? day.firstIn ?? undefined) : null;
    if (f.kind === "MISSED_CHECKOUT" && !proposedOut) throw new UserError("Enter the time you actually checked out.");

    const r = await db.regularizationRequest.create({
      data: { employeeId: u.id, workDate: isoToDate(f.date), kind: f.kind, proposedIn, proposedOut, reason: f.reason || null },
    });
    await audit({ actorId: u.id, action: "regularization.request", entity: "regularization", entityId: r.id, after: { date: f.date, kind: f.kind, proposedIn, proposedOut }, ip: (await requestMeta()).ip });
    revalidatePath("/attendance");
    return { ok: true, msg: "Regularization sent — HR, Finance or Admin can approve it" };
  });
}

export async function cancelRegularization(id: string): Promise<ActionResult> {
  return run(async () => {
    const u = await assertPerm("self");
    z.string().uuid().parse(id);
    const r = await db.regularizationRequest.findUnique({ where: { id } });
    if (!r || r.employeeId !== u.id) throw new UserError("Request not found.");
    if (r.status !== "PENDING") throw new UserError("Only pending requests can be withdrawn.");
    await db.regularizationRequest.update({ where: { id }, data: { status: "CANCELLED" } });
    await audit({ actorId: u.id, action: "regularization.cancel", entity: "regularization", entityId: id, before: { status: "PENDING" }, after: { status: "CANCELLED" } });
    revalidatePath("/attendance");
    return { ok: true, msg: "Request withdrawn" };
  });
}
