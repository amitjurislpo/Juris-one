"use server";

import { revalidatePath } from "next/cache";
import { z } from "zod";
import { run, UserError, type ActionResult } from "@/lib/action";
import { audit } from "@/lib/audit";
import { assertPerm } from "@/lib/auth";
import { db } from "@/lib/db";
import { requestMeta } from "@/lib/request";

const createSchema = z.object({
  name: z.string().trim().min(2, "Department name is required.").max(80, "Keep the name under 80 characters."),
  headId: z.union([z.string().uuid(), z.literal("")]).optional(),
  description: z.string().trim().max(300, "Keep the description under 300 characters.").optional(),
});

export async function createDepartment(input: z.input<typeof createSchema>): Promise<ActionResult> {
  return run(async () => {
    const u = await assertPerm("dept.manage");
    const { name, headId, description } = createSchema.parse(input);
    const existing = await db.department.findFirst({ where: { name: { equals: name, mode: "insensitive" } } });
    if (existing && !existing.archivedAt) throw new UserError("This department already exists.");
    if (headId) {
      const head = await db.employee.findUnique({ where: { id: headId }, select: { status: true } });
      if (!head || head.status === "EXITED") throw new UserError("Pick a current employee as head.");
    }
    const ip = (await requestMeta()).ip;
    await db.$transaction(async (tx) => {
      // A previously archived department with the same name is brought back rather than duplicated.
      const d = existing
        ? await tx.department.update({ where: { id: existing.id }, data: { name, archivedAt: null, headEmployeeId: headId || null, description: description || null, createdById: u.id, createdAt: new Date() } })
        : await tx.department.create({ data: { name, headEmployeeId: headId || null, description: description || null, createdById: u.id } });
      await audit({ actorId: u.id, action: existing ? "department.restore" : "department.create", entity: "department", entityId: d.id, after: { name, headEmployeeId: headId || null, description } , ip }, tx);
    });
    revalidatePath("/departments");
    return { ok: true, msg: `Department “${name}” created` };
  });
}

export async function deleteDepartment(id: string): Promise<ActionResult> {
  return run(async () => {
    const u = await assertPerm("dept.manage");
    z.string().uuid().parse(id);
    const d = await db.department.findUnique({ where: { id }, include: { employees: { select: { status: true } } } });
    if (!d || d.archivedAt) throw new UserError("That department no longer exists.");
    const current = d.employees.filter((e) => e.status !== "EXITED").length;
    if (current) throw new UserError(`${d.name} still has ${current} current employee${current === 1 ? "" : "s"}. Move them first.`);
    const ip = (await requestMeta()).ip;
    await db.$transaction(async (tx) => {
      // Past employees still point at it, so it is archived to keep their records intact.
      if (d.employees.length) await tx.department.update({ where: { id }, data: { archivedAt: new Date(), headEmployeeId: null } });
      else await tx.department.delete({ where: { id } });
      await audit({ actorId: u.id, action: "department.delete", entity: "department", entityId: id, before: { name: d.name, description: d.description }, after: d.employees.length ? { archived: true } : undefined, ip }, tx);
    });
    revalidatePath("/departments");
    return { ok: true, msg: `Department “${d.name}” removed` };
  });
}
