import "server-only";
import type { Prisma } from "@/generated/prisma/client";
import { db } from "./db";

export interface AuditEntry {
  actorId: string | null;
  action: string;
  entity: string;
  entityId?: string | null;
  before?: unknown;
  after?: unknown;
  ip?: string | null;
}

type Tx = Prisma.TransactionClient;

const json = (v: unknown) => (v === undefined || v === null ? undefined : (JSON.parse(JSON.stringify(v, (_k, x) => (typeof x === "bigint" ? x.toString() : x))) as Prisma.InputJsonValue));

/** Who, what, when, before → after (REQUIREMENTS §5.16). Pass `tx` to keep it in the same transaction. */
export async function audit(e: AuditEntry, tx: Tx = db) {
  await tx.auditLog.create({
    data: {
      actorId: e.actorId,
      action: e.action,
      entity: e.entity,
      entityId: e.entityId ?? null,
      before: json(e.before),
      after: json(e.after),
      ip: e.ip ?? null,
    },
  });
}
