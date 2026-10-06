"use server";

import { revalidatePath } from "next/cache";
import { z } from "zod";
import { run, UserError, type ActionResult } from "@/lib/action";
import { audit } from "@/lib/audit";
import { assertPerm } from "@/lib/auth";
import { db } from "@/lib/db";
import { requestMeta } from "@/lib/request";
import { getSetting } from "@/lib/settings";

const schema = z
  .object({
    fullDayHours: z.number().min(1).max(14),
    halfDayHours: z.number().min(0.5).max(12),
    graceMinutes: z.number().int().min(0).max(120),
    lateMarksPerHalfDayLop: z.number().int().min(1).max(31),
    weeklyOffs: z.array(z.number().int().min(0).max(6)).max(6),
    absenceJobBufferMinutes: z.number().int().min(0).max(720),
    lockDayOfNextMonth: z.number().int().min(1).max(28),
    lockTime: z.string().regex(/^\d{2}:\d{2}$/),
    trackingStartDate: z.string().regex(/^\d{4}-\d{2}-\d{2}$/),
  })
  .refine((v) => v.halfDayHours < v.fullDayHours, "Half-day hours must be less than full-day hours.");

export type AttendancePolicyInput = z.infer<typeof schema>;

/** Attendance policy (REQUIREMENTS §5.4 "configurable in Policies"). Locked months keep their frozen values. */
export async function updateAttendancePolicy(input: AttendancePolicyInput): Promise<ActionResult> {
  return run(async () => {
    const u = await assertPerm("roles.manage");
    const f = schema.parse(input);
    const before = await getSetting("attendance.policy");
    if (f.trackingStartDate !== before.trackingStartDate && (await db.jobRun.count({ where: { name: "attendance.lock" } })) > 0)
      throw new UserError("The go-live date can't change once a month has been locked.");
    const value = { ...before, ...f };
    await db.$transaction(async (tx) => {
      await tx.setting.upsert({ where: { key: "attendance.policy" }, create: { key: "attendance.policy", value }, update: { value } });
      await tx.shift.updateMany({ data: { graceMinutes: f.graceMinutes } });
      await audit({ actorId: u.id, action: "policy.attendance_change", entity: "setting", entityId: "attendance.policy", before, after: value, ip: (await requestMeta()).ip }, tx);
    });
    ["/settings", "/home", "/attendance", "/register", "/team"].forEach((p) => revalidatePath(p));
    return { ok: true, msg: "Attendance policy saved" };
  });
}
