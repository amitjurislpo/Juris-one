"use server";

import { redirect } from "next/navigation";
import { apiCall, applySetCookies } from "@/lib/backend";

import type { FormState } from "./form-state";

// Sign-in flows run in the Spring Boot API (/api/auth/*): rate limits, lockout, login history, audit and the
// session cookie. Signing in creates the session resource, signing out deletes it.

export async function loginAction(_prev: FormState, form: FormData): Promise<FormState> {
  const identifier = String(form.get("identifier") ?? "");
  const r = await apiCall<{ redirect: string }>("POST", "/api/auth/session", {
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
  const r = await apiCall("DELETE", "/api/auth/session");
  await applySetCookies(r.setCookies); // clears the access and refresh cookies
  redirect("/login?signedout=1");
}

export async function forgotAction(_prev: FormState, form: FormData): Promise<FormState> {
  const r = await apiCall("POST", "/api/auth/password-resets", { identifier: String(form.get("identifier") ?? "") });
  return r.body.ok ? { ok: r.body.msg } : { error: r.body.error };
}

export async function setPasswordAction(_prev: FormState, form: FormData): Promise<FormState> {
  const r = await apiCall<{ redirect: string }>("PUT", "/api/auth/password",
    Object.fromEntries(["token", "purpose", "password", "confirm"].map((k) => [k, String(form.get(k) ?? "")])));
  if (!r.body.ok) return { error: r.body.error };
  redirect(r.body.data!.redirect);
}
