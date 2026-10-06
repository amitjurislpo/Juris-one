import "server-only";
import type { Prisma } from "@/generated/prisma/client";
import { db } from "./db";
import { decisionEmail, sendEmail } from "./email";

type Tx = Prisma.TransactionClient;

/** In-app notification (bell). */
export async function notify(employeeId: string, text: string, link?: string, tx: Tx = db) {
  await tx.notification.create({ data: { employeeId, text: text.slice(0, 300), link } });
}

/** Bell + email for a leave / regularization decision (REQUIREMENTS §5.14). */
export async function notifyDecision(employeeId: string, what: string, approved: boolean, by: string, link: string, note?: string | null) {
  const e = await db.employee.findUnique({ where: { id: employeeId }, select: { email: true, fullName: true } });
  if (!e) return;
  await notify(employeeId, `${what} ${approved ? "approved" : "declined"} by ${by}`, link);
  await sendEmail({ to: e.email, ...decisionEmail(e.fullName, what, approved, by, note ?? null) });
}
