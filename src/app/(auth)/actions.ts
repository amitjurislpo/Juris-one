"use server";

import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { apiCall, applySetCookies } from "@/lib/backend";
import { SESSION_COOKIE } from "@/lib/session-cookie";

import type { FormState } from "./form-state";

// Sign-in flows run in the Spring Boot API (/api/auth/*): rate limits, lockout, login history, audit and the
// session cookie. These actions pass the form through and apply the result.

export async function loginAction(_prev: FormState, form: FormData): Promise<FormState> {
  const identifier = String(form.get("identifier") ?? "");
  const r = await apiCall<{ redirect: string }>("POST", "/api/auth/login", {
    identifier,
    password: String(form.get("password") ?? ""),
    remember: form.get("remember") === "on",
    next: String(form.get("next") ?? ""),
  });
  if (!r.body.ok) return { error: r.body.error, identifier: identifier.slice(0, 200) };
  await applySetCookies(r.setCookies);
  redirect(r.body.data!.redirect);
}

export async function logoutAction() {
  await apiCall("POST", "/api/auth/logout");
  (await cookies()).delete(SESSION_COOKIE);
  redirect("/login?signedout=1");
}

export async function forgotAction(_prev: FormState, form: FormData): Promise<FormState> {
  const r = await apiCall("POST", "/api/auth/forgot", { identifier: String(form.get("identifier") ?? "") });
  return r.body.ok ? { ok: r.body.msg } : { error: r.body.error };
}

export async function setPasswordAction(_prev: FormState, form: FormData): Promise<FormState> {
  const r = await apiCall<{ redirect: string }>("POST", "/api/auth/set-password",
    Object.fromEntries(["token", "purpose", "password", "confirm"].map((k) => [k, String(form.get(k) ?? "")])));
  if (!r.body.ok) return { error: r.body.error };
  redirect(r.body.data!.redirect);
}
