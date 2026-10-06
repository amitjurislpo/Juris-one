"use server";

import { revalidatePath } from "next/cache";
import { z } from "zod";
import { run, UserError, type ActionResult } from "@/lib/action";
import { audit } from "@/lib/audit";
import { assertPerm } from "@/lib/auth";
import { db } from "@/lib/db";
import { isPermission, PERMISSION_LABEL, type Permission } from "@/lib/permissions";
import { requestMeta } from "@/lib/request";

const id = z.string().uuid();

async function editableRole(roleId: string) {
  const role = await db.role.findUnique({ where: { id: roleId } });
  if (!role) throw new UserError("That role no longer exists.");
  if (role.isLocked) throw new UserError(`${role.name} has full access and can't be changed.`);
  return role;
}

const done = (msg: string): ActionResult => {
  revalidatePath("/roles");
  revalidatePath("/settings");
  return { ok: true, msg };
};

export async function setPermission(roleId: string, perm: string, on: boolean): Promise<ActionResult> {
  return run(async () => {
    const u = await assertPerm("roles.manage");
    id.parse(roleId);
    if (!isPermission(perm)) throw new UserError("Unknown permission.");
    const role = await editableRole(roleId);
    const before = (await db.rolePermission.findMany({ where: { roleId } })).map((p) => p.permissionKey).sort();
    if (on) await db.rolePermission.upsert({ where: { roleId_permissionKey: { roleId, permissionKey: perm } }, create: { roleId, permissionKey: perm }, update: {} });
    else await db.rolePermission.deleteMany({ where: { roleId, permissionKey: perm } });
    const after = on ? [...new Set([...before, perm])].sort() : before.filter((p) => p !== perm);
    await audit({ actorId: u.id, action: "role.permission_change", entity: "role", entityId: roleId, before: { permissions: before }, after: { permissions: after }, ip: (await requestMeta()).ip });
    return done(`${role.name}: ${on ? "granted" : "removed"} “${PERMISSION_LABEL[perm as Permission]}”`);
  });
}

export async function setAssignable(roleId: string, assignableRoleId: string, on: boolean): Promise<ActionResult> {
  return run(async () => {
    const u = await assertPerm("roles.manage");
    id.parse(roleId);
    id.parse(assignableRoleId);
    const role = await editableRole(roleId);
    const target = await db.role.findUnique({ where: { id: assignableRoleId } });
    if (!target) throw new UserError("That role no longer exists.");
    if (on) await db.roleAssignableRole.upsert({ where: { roleId_assignableRoleId: { roleId, assignableRoleId } }, create: { roleId, assignableRoleId }, update: {} });
    else await db.roleAssignableRole.deleteMany({ where: { roleId, assignableRoleId } });
    await audit({ actorId: u.id, action: "role.assignable_change", entity: "role", entityId: roleId, before: { [target.name]: !on }, after: { [target.name]: on }, ip: (await requestMeta()).ip });
    return done(`${role.name} ${on ? "can now" : "can no longer"} add ${target.name}s`);
  });
}

const createSchema = z.object({
  name: z.string().trim().min(2, "Role name is required.").max(60, "Keep the name under 60 characters."),
  fromRoleId: id,
});

export async function createRole(input: { name: string; fromRoleId: string }): Promise<ActionResult<{ id: string }>> {
  return run(async () => {
    const u = await assertPerm("roles.manage");
    const { name, fromRoleId } = createSchema.parse(input);
    if (await db.role.findFirst({ where: { name: { equals: name, mode: "insensitive" } } })) throw new UserError("A role with that name already exists.");
    const src = await db.role.findUnique({ where: { id: fromRoleId }, include: { permissions: true } });
    if (!src) throw new UserError("Pick a role to start from.");
    // Copies the source's permissions, but never Administration — that must be granted deliberately.
    const perms = src.permissions.map((p) => p.permissionKey).filter((p) => p !== "roles.manage");
    const key = `custom_${name.toLowerCase().replace(/[^a-z0-9]+/g, "_").replace(/^_|_$/g, "").slice(0, 30)}_${Date.now().toString(36)}`;
    const role = await db.$transaction(async (tx) => {
      const r = await tx.role.create({ data: { key, name, isBuiltin: false, permissions: { create: perms.map((permissionKey) => ({ permissionKey })) } } });
      await audit({ actorId: u.id, action: "role.create", entity: "role", entityId: r.id, after: { name, copiedFrom: src.name, permissions: perms } }, tx);
      return r;
    });
    revalidatePath("/roles");
    revalidatePath("/settings");
    return { ok: true, msg: `Role “${name}” created — adjust its permissions`, data: { id: role.id } };
  });
}

export async function deleteRole(roleId: string): Promise<ActionResult> {
  return run(async () => {
    const u = await assertPerm("roles.manage");
    id.parse(roleId);
    const role = await db.role.findUnique({ where: { id: roleId }, include: { permissions: true, _count: { select: { employees: true } } } });
    if (!role) throw new UserError("That role no longer exists.");
    if (role.isBuiltin) throw new UserError("Built-in roles can't be deleted.");
    // Past employees keep their role on record, so they count too.
    if (role._count.employees > 0) throw new UserError("Move everyone (including past employees) to another role first.");
    await db.$transaction(async (tx) => {
      await tx.role.delete({ where: { id: roleId } });
      await audit({ actorId: u.id, action: "role.delete", entity: "role", entityId: roleId, before: { name: role.name, permissions: role.permissions.map((p) => p.permissionKey) } }, tx);
    });
    return done(`Role “${role.name}” deleted`);
  });
}
