"use server";

import { revalidatePath } from "next/cache";
import { z } from "zod";
import { run, UserError, type ActionResult } from "@/lib/action";
import { attendancePolicy, isMonthLocked } from "@/lib/attendance/service";
import { audit } from "@/lib/audit";
import { assertPerm } from "@/lib/auth";
import { db } from "@/lib/db";
import { dateToIso, fmtDL, isoToDate, todayIn } from "@/lib/format";
import { requestMeta } from "@/lib/request";

const schema = z.object({
  date: z.string().regex(/^\d{4}-\d{2}-\d{2}$/, "Pick a date"),
  name: z.string().trim().min(2, "Enter the holiday name").max(80),
  region: z.enum(["IN", "US", "ALL"]),
});

const refresh = () => ["/holidays", "/home", "/attendance", "/register", "/settings", "/team"].forEach((p) => revalidatePath(p));
const label = (r: string) => (r === "ALL" ? "everyone" : r === "IN" ? "India team" : "US team");

export async function addHoliday(input: z.input<typeof schema>): Promise<ActionResult> {
  return run(async () => {
    const u = await assertPerm("holidays.manage");
    const f = schema.parse(input);
    const policy = await attendancePolicy();
    if (isMonthLocked(f.date.slice(0, 7), policy)) throw new UserError("That month's attendance is already locked.");
    const clash = await db.holiday.findFirst({
      where: { date: isoToDate(f.date), region: f.region === "ALL" ? { in: ["IN", "US", "ALL"] } : { in: [f.region, "ALL"] } },
    });
    if (clash) throw new UserError(`There's already a holiday on this date for this team (${clash.name}).`);
    const h = await db.holiday.create({ data: { date: isoToDate(f.date), name: f.name, region: f.region, createdById: u.id } });
    await audit({ actorId: u.id, action: "holiday.create", entity: "holiday", entityId: h.id, after: f, ip: (await requestMeta()).ip });
    refresh();
    return { ok: true, msg: `${f.name} added for ${label(f.region)} · ${fmtDL(f.date)}` };
  });
}

export async function deleteHoliday(id: string): Promise<ActionResult> {
  return run(async () => {
    const u = await assertPerm("holidays.manage");
    z.string().uuid().parse(id);
    const h = await db.holiday.findUnique({ where: { id } });
    if (!h) throw new UserError("That holiday no longer exists.");
    if (dateToIso(h.date) < todayIn("Asia/Kolkata")) throw new UserError("Past holidays can't be deleted.");
    await db.holiday.delete({ where: { id } });
    await audit({ actorId: u.id, action: "holiday.delete", entity: "holiday", entityId: id, before: { date: dateToIso(h.date), name: h.name, region: h.region }, ip: (await requestMeta()).ip });
    refresh();
    return { ok: true, msg: `${h.name} removed from the calendar` };
  });
}
