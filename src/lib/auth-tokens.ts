import "server-only";
import type { AuthTokenPurpose } from "@/generated/prisma/client";
import { db } from "./db";
import { env } from "./env";
import { randomToken, sha256 } from "./crypto";
import { inviteEmail, resetEmail, sendEmail } from "./email";
import { getSetting } from "./settings";

async function issue(employeeId: string, purpose: AuthTokenPurpose, ttlMs: number) {
  const token = randomToken(32);
  await db.$transaction([
    // only the newest link of each kind works
    db.authToken.updateMany({ where: { employeeId, purpose, usedAt: null }, data: { usedAt: new Date() } }),
    db.authToken.create({ data: { tokenHash: sha256(token), employeeId, purpose, expiresAt: new Date(Date.now() + ttlMs) } }),
  ]);
  return token;
}

/** One-time "set your password" link for a new employee (REQUIREMENTS §4). */
export async function sendInvite(employeeId: string) {
  const e = await db.employee.findUniqueOrThrow({ where: { id: employeeId } });
  const { inviteTtlHours } = await getSetting("auth.policy");
  const token = await issue(employeeId, "INVITE", inviteTtlHours * 3600_000);
  const link = `${env().APP_URL}/invite/${token}`;
  await sendEmail({ to: e.email, ...inviteEmail(e.fullName, e.code, link, inviteTtlHours) });
}

export async function sendPasswordReset(employeeId: string) {
  const e = await db.employee.findUniqueOrThrow({ where: { id: employeeId } });
  const { resetTtlMinutes } = await getSetting("auth.policy");
  const token = await issue(employeeId, "RESET", resetTtlMinutes * 60_000);
  const link = `${env().APP_URL}/reset/${token}`;
  await sendEmail({ to: e.email, ...resetEmail(e.fullName, link, resetTtlMinutes) });
}

export async function findUsableToken(token: string, purpose: AuthTokenPurpose) {
  const t = await db.authToken.findUnique({ where: { tokenHash: sha256(token) }, include: { employee: true } });
  if (!t || t.purpose !== purpose || t.usedAt || t.expiresAt <= new Date()) return null;
  if (t.employee.status === "EXITED") return null;
  return t;
}
