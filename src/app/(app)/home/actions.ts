"use server";

import { revalidatePath } from "next/cache";
import { z } from "zod";
import { run, UserError, type ActionResult } from "@/lib/action";
import { loadAttEmployees } from "@/lib/attendance/service";
import { assertPerm } from "@/lib/auth";
import { db } from "@/lib/db";
import { fmtH } from "@/lib/format";
import { requestMeta } from "@/lib/request";
import { workedMinutes, workDateOf } from "@/lib/attendance/engine";

const MIN_GAP_MS = 30_000;

/**
 * Check in / check out (`self`). `expect` is the action the user saw on the button, so a
 * double-click or a stale tab can't record the opposite of what they meant.
 */
export async function punch(expect: "IN" | "OUT"): Promise<ActionResult<{ kind: "IN" | "OUT" }>> {
  return run<{ kind: "IN" | "OUT" }>(async () => {
    const u = await assertPerm("self");
    z.enum(["IN", "OUT"]).parse(expect);
    const [emp] = await loadAttEmployees({ id: u.id });
    if (!emp) throw new UserError("Attendance isn't tracked for your account.");
    const now = new Date();
    const today = new Intl.DateTimeFormat("en-CA", { timeZone: emp.tz }).format(now);
    if (today < emp.doj) throw new UserError("You can check in from your joining date.");

    const last = await db.punch.findFirst({ where: { employeeId: u.id }, orderBy: { at: "desc" } });
    const current = last?.kind === "IN" ? "IN" : "OUT";
    if (expect === "IN" && current === "IN") throw new UserError("You're already checked in.");
    if (expect === "OUT" && current !== "IN") throw new UserError("You're not checked in.");
    if (last && now.getTime() - last.at.getTime() < MIN_GAP_MS) throw new UserError("Just recorded — give it a few seconds.");

    const { ip } = await requestMeta();
    await db.punch.create({ data: { employeeId: u.id, at: now, kind: expect, source: "WEB", ip } });
    revalidatePath("/home");
    revalidatePath("/attendance");

    if (expect === "IN") return { ok: true, msg: "Checked in. Have a productive shift.", data: { kind: "IN" } };
    const wd = workDateOf(now, emp.shift, emp.tz);
    const since = new Date(now.getTime() - 40 * 3600_000);
    const ps = (await db.punch.findMany({ where: { employeeId: u.id, at: { gte: since } }, orderBy: { at: "asc" } })).filter((p) => workDateOf(p.at, emp.shift, emp.tz) === wd);
    return { ok: true, msg: `Checked out — ${fmtH(workedMinutes(ps).minutes / 60)} recorded today`, data: { kind: "OUT" } };
  });
}
