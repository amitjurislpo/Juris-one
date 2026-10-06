import "server-only";
import { db } from "./db";
import type { CurrentUser } from "./auth";
import type { Actor, Target } from "./people-rules";

/** Everything the people rules need to know about the signed-in user. */
export async function loadActor(u: CurrentUser): Promise<Actor> {
  const [assign, me, employeeRole] = await Promise.all([
    db.roleAssignableRole.findMany({ where: { roleId: u.roleId }, select: { assignableRoleId: true } }),
    db.employee.findUniqueOrThrow({ where: { id: u.id }, select: { departmentId: true } }),
    db.role.findUnique({ where: { key: "employee" }, select: { id: true } }),
  ]);
  return {
    id: u.id,
    roleKey: u.roleKey,
    perms: u.perms,
    assignable: new Set(assign.map((a) => a.assignableRoleId)),
    departmentId: me.departmentId,
    employeeRoleId: employeeRole?.id ?? "",
  };
}

export async function loadTarget(id: string): Promise<Target | null> {
  const e = await db.employee.findUnique({ where: { id }, select: { id: true, roleId: true, status: true, role: { select: { key: true } }, user: { select: { isSuperAdmin: true } } } });
  return e ? { id: e.id, roleId: e.roleId, roleKey: e.role.key, status: e.status, isSuperAdmin: !!e.user?.isSuperAdmin } : null;
}
