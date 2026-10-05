"use server";

import { redirect } from "next/navigation";
import { z } from "zod";
import { db } from "@/lib/db";
import { audit } from "@/lib/audit";
import { getCurrentUser } from "@/lib/auth";
import { findUsableToken, sendPasswordReset } from "@/lib/auth-tokens";
import { dummyHash, hashPassword, passwordProblem, verifyPassword } from "@/lib/password";
import { hit, limited, rateLimit } from "@/lib/rate-limit";
import { requestMeta } from "@/lib/request";
import { createSession, destroyCurrentSession, revokeAllSessions } from "@/lib/session";
import { getSetting } from "@/lib/settings";

import type { FormState } from "./form-state";

const BAD_LOGIN = "That employee ID / email and password don't match.";
const MSG_DISABLED = "Your portal access is disabled. Please contact HR.";
const MSG_EXITED = "This account belongs to a past employee and can no longer sign in.";

const loginSchema = z.object({
  identifier: z.string().trim().min(1, "Enter your employee ID or email.").max(200),
  password: z.string().min(1, "Enter your password.").max(200),
  remember: z.boolean(),
});

const minutes = (ms: number) => Math.max(1, Math.ceil(ms / 60_000));

export async function loginAction(_prev: FormState, form: FormData): Promise<FormState> {
  const result = await login(form);
  return { ...result, identifier: String(form.get("identifier") ?? "").slice(0, 200) };
}

async function login(form: FormData): Promise<FormState> {
  const parsed = loginSchema.safeParse({
    identifier: form.get("identifier") ?? "",
    password: form.get("password") ?? "",
    remember: form.get("remember") === "on",
  });
  if (!parsed.success) return { error: parsed.error.issues[0].message };
  const { identifier, password, remember } = parsed.data;
  const meta = await requestMeta();
  const policy = await getSetting("auth.policy");

  // Only failed attempts count against the IP, so a shared office IP isn't throttled by normal sign-ins.
  const ipKey = `login:${meta.ip ?? "unknown"}`;
  const rl = limited(ipKey, policy.ipMaxAttempts);
  if (rl.limited) return { error: `Too many failed sign-in attempts. Try again in ${minutes(rl.retryInMs)} minute(s).` };
  const failedFromIp = () => hit(ipKey, policy.ipWindowMinutes * 60_000);

  const id = identifier.toLowerCase();
  const emp = await db.employee.findFirst({
    where: id.includes("@") ? { email: id } : { code: id.toUpperCase() },
    include: { user: true, role: { include: { permissions: true } } },
  });

  const history = (success: boolean, reason: string) =>
    db.loginHistory.create({ data: { employeeId: emp?.id ?? null, identifier: identifier.slice(0, 200), success, reason, ip: meta.ip, userAgent: meta.userAgent } });

  if (!emp || !emp.user) {
    await verifyPassword(await dummyHash(), password);
    failedFromIp();
    await history(false, "unknown_identifier");
    return { error: BAD_LOGIN };
  }
  const user = emp.user;
  if (user.lockedUntil && user.lockedUntil > new Date()) {
    await history(false, "locked");
    return { error: `This account is locked after repeated failed sign-ins. Try again in ${minutes(user.lockedUntil.getTime() - Date.now())} minute(s), or reset your password.` };
  }

  const ok = await verifyPassword(user.passwordHash, password);
  if (!ok) {
    failedFromIp();
    const failed = user.failedAttempts + 1;
    const lock = failed >= policy.maxFailedAttempts;
    await db.user.update({
      where: { employeeId: emp.id },
      data: lock ? { failedAttempts: 0, lockedUntil: new Date(Date.now() + policy.lockMinutes * 60_000) } : { failedAttempts: failed },
    });
    await history(false, lock ? "bad_password_locked" : user.passwordHash ? "bad_password" : "not_activated");
    if (lock) {
      await audit({ actorId: emp.id, action: "auth.locked", entity: "user", entityId: emp.id, after: { lockMinutes: policy.lockMinutes }, ip: meta.ip });
      return { error: `Too many failed attempts — this account is locked for ${policy.lockMinutes} minutes.` };
    }
    return { error: user.passwordHash ? BAD_LOGIN : "This account isn't activated yet. Use the invite link in your email, or choose “Forgot?” to get a new one." };
  }

  // Blocked accounts get their specific message only after the password is right (no enumeration).
  if (emp.status === "EXITED") {
    await history(false, "exited");
    return { error: MSG_EXITED };
  }
  if (!emp.portalAccess) {
    await history(false, "access_disabled");
    return { error: MSG_DISABLED };
  }

  await db.user.update({ where: { employeeId: emp.id }, data: { failedAttempts: 0, lockedUntil: null, lastLoginAt: new Date() } });
  await createSession(emp.id, remember, meta);
  await history(true, "ok");
  await audit({ actorId: emp.id, action: "auth.login", entity: "session", entityId: emp.id, after: { userAgent: meta.userAgent }, ip: meta.ip });

  const perms = new Set(emp.role.permissions.map((p) => p.permissionKey));
  const next = String(form.get("next") ?? "");
  redirect(next.startsWith("/") && !next.startsWith("//") ? next : perms.has("self") ? "/home" : perms.has("roles.manage") ? "/overview" : "/directory");
}

export async function logoutAction() {
  const u = await getCurrentUser();
  const meta = await requestMeta();
  await destroyCurrentSession();
  if (u) await audit({ actorId: u.id, action: "auth.logout", entity: "session", entityId: u.id, ip: meta.ip });
  redirect("/login?signedout=1");
}

export async function forgotAction(_prev: FormState, form: FormData): Promise<FormState> {
  const identifier = String(form.get("identifier") ?? "").trim().toLowerCase();
  if (!identifier) return { error: "Enter your employee ID or official email." };
  const meta = await requestMeta();
  const rl = rateLimit(`forgot:${meta.ip ?? "unknown"}`, 5, 15 * 60_000);
  if (!rl.ok) return { error: `Too many requests. Try again in ${minutes(rl.retryInMs)} minute(s).` };
  const emp = await db.employee.findFirst({
    where: identifier.includes("@") ? { email: identifier } : { code: identifier.toUpperCase() },
    include: { user: true },
  });
  if (emp?.user && emp.status !== "EXITED" && emp.portalAccess) {
    await sendPasswordReset(emp.id);
    await audit({ actorId: emp.id, action: "auth.reset_requested", entity: "user", entityId: emp.id, ip: meta.ip });
  }
  return { ok: "If that account exists and is active, a reset link is on its way to the official email." };
}

const setPwSchema = z.object({
  token: z.string().min(10).max(200),
  purpose: z.enum(["INVITE", "RESET"]),
  password: z.string(),
  confirm: z.string(),
});

export async function setPasswordAction(_prev: FormState, form: FormData): Promise<FormState> {
  const p = setPwSchema.safeParse(Object.fromEntries(["token", "purpose", "password", "confirm"].map((k) => [k, String(form.get(k) ?? "")])));
  if (!p.success) return { error: "This link is invalid." };
  const { token, purpose, password, confirm } = p.data;
  const problem = passwordProblem(password);
  if (problem) return { error: problem };
  if (password !== confirm) return { error: "The two passwords don't match." };

  const t = await findUsableToken(token, purpose);
  if (!t) return { error: "This link has expired or was already used. Ask HR for a new invite, or use “Forgot?” on the sign-in page." };

  const meta = await requestMeta();
  const hash = await hashPassword(password);
  await db.$transaction(async (tx) => {
    await tx.authToken.update({ where: { id: t.id }, data: { usedAt: new Date() } });
    await tx.user.upsert({
      where: { employeeId: t.employeeId },
      create: { employeeId: t.employeeId, passwordHash: hash, passwordSetAt: new Date() },
      update: { passwordHash: hash, passwordSetAt: new Date(), failedAttempts: 0, lockedUntil: null },
    });
    await revokeAllSessions(t.employeeId, purpose === "RESET" ? "password_reset" : "invite_accepted", tx);
    await audit({ actorId: t.employeeId, action: purpose === "RESET" ? "auth.password_reset" : "auth.invite_accepted", entity: "user", entityId: t.employeeId, ip: meta.ip }, tx);
  });
  redirect(`/login?${purpose === "RESET" ? "reset" : "activated"}=1`);
}

