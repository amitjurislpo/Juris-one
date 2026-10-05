import "server-only";
import { cookies } from "next/headers";
import type { Prisma } from "@/generated/prisma/client";
import { db } from "./db";
import { env } from "./env";
import { randomToken, sha256 } from "./crypto";
import type { RequestMeta } from "./request";

export const SESSION_COOKIE = "jo_session";
const SHORT_SESSION_HOURS = 12; // "Keep me signed in" unchecked
const TOUCH_EVERY_MS = 5 * 60 * 1000;

type Tx = Prisma.TransactionClient;

export async function createSession(employeeId: string, remember: boolean, meta: RequestMeta) {
  const token = randomToken(32);
  const ttlMs = remember ? env().SESSION_TTL_DAYS * 86400_000 : SHORT_SESSION_HOURS * 3600_000;
  const expiresAt = new Date(Date.now() + ttlMs);
  await db.session.create({
    data: { id: sha256(token), employeeId, expiresAt, ip: meta.ip, userAgent: meta.userAgent },
  });
  const jar = await cookies();
  jar.set(SESSION_COOKIE, token, {
    httpOnly: true,
    sameSite: "lax",
    secure: env().NODE_ENV === "production",
    path: "/",
    ...(remember ? { expires: expiresAt } : {}),
  });
}

/** Looks up the session behind the cookie. Validity checks live in auth.ts. */
export async function readSessionCookie() {
  const jar = await cookies();
  const token = jar.get(SESSION_COOKIE)?.value;
  return token ? sha256(token) : null;
}

export async function touchSession(id: string, lastSeenAt: Date) {
  if (Date.now() - lastSeenAt.getTime() < TOUCH_EVERY_MS) return;
  await db.session.update({ where: { id }, data: { lastSeenAt: new Date() } }).catch(() => {});
}

/** Ends every active session of an employee immediately (access disabled, exit, password reset). */
export async function revokeAllSessions(employeeId: string, reason: string, tx: Tx = db) {
  await tx.session.updateMany({
    where: { employeeId, revokedAt: null },
    data: { revokedAt: new Date(), revokedReason: reason },
  });
}

export async function destroyCurrentSession() {
  const id = await readSessionCookie();
  if (id) await db.session.updateMany({ where: { id, revokedAt: null }, data: { revokedAt: new Date(), revokedReason: "logout" } });
  (await cookies()).delete(SESSION_COOKIE);
}
