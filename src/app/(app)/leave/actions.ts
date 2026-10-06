"use server";

import { revalidatePath } from "next/cache";
import { z } from "zod";
import { run, UserError, type ActionResult } from "@/lib/action";
import { loadAttEmployees } from "@/lib/attendance/service";
import { audit } from "@/lib/audit";
import { assertPerm } from "@/lib/auth";
import { db } from "@/lib/db";
import { fmtD, isoToDate } from "@/lib/format";
import { previewRequest, type Preview } from "@/lib/leave/service";
import { requestMeta } from "@/lib/request";

const input = z.object({
  type: z.string().min(1).max(10),
  from: z.string().regex(/^\d{4}-\d{2}-\d{2}$/, "Pick your dates on the calendar."),
  to: z.string().regex(/^\d{4}-\d{2}-\d{2}$/, "Pick your dates on the calendar."),
  part: z.enum(["FULL", "FIRST_HALF", "SECOND_HALF"]),
  reason: z.string().trim().max(500, "Keep the reason under 500 characters.").optional().default(""),
});
export type ApplyInput = z.input<typeof input>;

async function me() {
  const u = await assertPerm("self");
  const [emp] = await loadAttEmployees({ id: u.id });
  if (!emp) throw new UserError("Leave isn't tracked for your account.");
  return { u, emp };
}

/** Live numbers for the apply form — same rules the server enforces on submit. */
export async function preview(i: ApplyInput): Promise<ActionResult<Preview>> {
  return run(async () => {
    const { emp } = await me();
    const f = input.parse(i);
    return { ok: true, data: await previewRequest(emp, f.type, f.from, f.to, f.part) };
  });
}

export async function applyLeave(i: ApplyInput): Promise<ActionResult> {
  return run(async () => {
    const { u, emp } = await me();
    const f = input.parse(i);
    const p = await previewRequest(emp, f.type, f.from, f.to, f.part);
    if (p.error) throw new UserError(p.error);
    const part = p.dates.length === 1 ? f.part : "FULL";
    const type = await db.leaveType.findUniqueOrThrow({ where: { code: f.type } });
    const r = await db.leaveRequest.create({
      data: { employeeId: u.id, leaveType: f.type, fromDate: isoToDate(p.from), toDate: isoToDate(p.to), part, days: p.days, lopDays: p.lopDays, reason: f.reason || null },
    });
    await audit({ actorId: u.id, action: "leave.apply", entity: "leave_request", entityId: r.id, after: { type: f.type, from: p.from, to: p.to, part, days: p.days, lopDays: p.lopDays }, ip: (await requestMeta()).ip });
    ["/leave", "/approvals", "/home"].forEach((x) => revalidatePath(x));
    const span = p.from === p.to ? fmtD(p.from) : `${fmtD(p.from)} – ${fmtD(p.to)}`;
    return { ok: true, msg: `${type.name} for ${p.days} day${p.days === 1 ? "" : "s"} (${span}) sent to HR${p.lopDays ? ` · ${p.lopDays} day(s) unpaid` : ""}` };
  });
}

export async function cancelLeave(id: string): Promise<ActionResult> {
  return run(async () => {
    const u = await assertPerm("self");
    z.string().uuid().parse(id);
    // atomic: only a still-pending request of mine can be cancelled
    const { count } = await db.leaveRequest.updateMany({ where: { id, employeeId: u.id, status: "PENDING" }, data: { status: "CANCELLED" } });
    if (!count) throw new UserError("Only your pending requests can be cancelled.");
    await audit({ actorId: u.id, action: "leave.cancel", entity: "leave_request", entityId: id, before: { status: "PENDING" }, after: { status: "CANCELLED" } });
    ["/leave", "/approvals", "/home"].forEach((x) => revalidatePath(x));
    return { ok: true, msg: "Leave request cancelled" };
  });
}
