import "server-only";
import type { Prisma } from "@/generated/prisma/client";
import { can, type CurrentUser } from "./auth";
import { db } from "./db";
import { listState, pick, str, type SearchParams } from "./list";

/** Event groups for the filter; each matches action prefixes. */
export const AUDIT_GROUPS = {
  auth: { label: "Sign-in & accounts", prefixes: ["auth."] },
  people: { label: "Employees & access", prefixes: ["employee."] },
  roles: { label: "Roles & policies", prefixes: ["role.", "policy."] },
  time: { label: "Leave & attendance", prefixes: ["leave.", "regularization.", "attendance."] },
  org: { label: "Departments & holidays", prefixes: ["department.", "holiday."] },
  payroll: { label: "Salary & payroll", prefixes: ["salary.", "payroll."] },
  tax: { label: "Tax & proofs", prefixes: ["tax.", "declaration.", "proof."] },
} as const;
export type AuditGroup = keyof typeof AUDIT_GROUPS;

/** Salary / tax payloads are only shown to viewers who hold a salary permission (HR never sees pay). */
const SALARY_PREFIXES = [...AUDIT_GROUPS.payroll.prefixes, ...AUDIT_GROUPS.tax.prefixes];

export const AUDIT_SORTS = ["at", "actor", "action"] as const;
export const AUDIT_PERIODS = ["7", "30", "90", "365", "All"] as const;

export interface AuditRow {
  id: string;
  at: string; // ISO timestamp
  actor: string;
  actorCode: string | null;
  action: string;
  entity: string;
  entityId: string | null;
  subject: string | null; // employee the entity refers to, when it is one
  ip: string | null;
  before: unknown;
  after: unknown;
  redacted: boolean;
}

export async function auditRows(u: CurrentUser, sp: SearchParams) {
  const ls = listState(sp, AUDIT_SORTS, "at", 50, "desc");
  const group = pick(sp, "ev", ["All", ...(Object.keys(AUDIT_GROUPS) as AuditGroup[])] as const, "All");
  const period = pick(sp, "days", AUDIT_PERIODS, "30");
  const actorId = str(sp, "actor");

  const and: Prisma.AuditLogWhereInput[] = [];
  if (group !== "All") and.push({ OR: AUDIT_GROUPS[group].prefixes.map((p) => ({ action: { startsWith: p } })) });
  if (period !== "All") and.push({ at: { gte: new Date(Date.now() - Number(period) * 86400_000) } });
  if (/^[0-9a-f-]{36}$/i.test(actorId)) and.push({ actorId });
  if (ls.q) {
    const q = ls.q;
    and.push({ OR: [
      { action: { contains: q, mode: "insensitive" } },
      { entity: { contains: q, mode: "insensitive" } },
      { entityId: { contains: q, mode: "insensitive" } },
      { actor: { fullName: { contains: q, mode: "insensitive" } } },
      { actor: { code: { contains: q, mode: "insensitive" } } },
    ] });
  }
  const where: Prisma.AuditLogWhereInput = and.length ? { AND: and } : {};
  const dir = ls.dir;
  const orderBy: Prisma.AuditLogOrderByWithRelationInput[] =
    ls.sort === "actor" ? [{ actor: { fullName: dir } }, { at: "desc" }] : ls.sort === "action" ? [{ action: dir }, { at: "desc" }] : [{ at: dir }];

  const [logs, total, actors] = await Promise.all([
    db.auditLog.findMany({ where, orderBy, skip: ls.skip, take: ls.take, include: { actor: { select: { fullName: true, code: true } } } }),
    db.auditLog.count({ where }),
    db.employee.findMany({ where: { auditEntries: { some: {} } }, select: { id: true, fullName: true, code: true }, orderBy: { fullName: "asc" } }),
  ]);

  // Name the employee an entry is about (entity ids are employee uuids for these entities).
  const empIds = [...new Set(logs.filter((l) => ["employee", "user"].includes(l.entity) && l.entityId).map((l) => l.entityId!))].filter((id) => /^[0-9a-f-]{36}$/i.test(id));
  const subjects = new Map((await db.employee.findMany({ where: { id: { in: empIds } }, select: { id: true, fullName: true, code: true } })).map((e) => [e.id, `${e.fullName} (${e.code})`]));

  const seePay = can(u, "pay.view", "struct.manage", "tax.view", "proofs.verify");
  const rows: AuditRow[] = logs.map((l) => {
    const redacted = !seePay && SALARY_PREFIXES.some((p) => l.action.startsWith(p));
    return {
      id: l.id, at: l.at.toISOString(), actor: l.actor?.fullName ?? "System", actorCode: l.actor?.code ?? null,
      action: l.action, entity: l.entity, entityId: l.entityId, subject: l.entityId ? subjects.get(l.entityId) ?? null : null, ip: l.ip,
      before: redacted ? null : l.before, after: redacted ? null : l.after, redacted,
    };
  });
  return { ls, total, rows, actors: actors.map((a) => ({ v: a.id, l: `${a.fullName} (${a.code})` })) };
}

/** "employee.access_toggle" → "Employee · access toggle" */
export const actionLabel = (a: string) => {
  const [head, ...rest] = a.split(".");
  return `${head.charAt(0).toUpperCase()}${head.slice(1)} · ${rest.join(" ").replace(/_/g, " ")}`;
};

