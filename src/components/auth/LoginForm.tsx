"use client";

import Link from "next/link";
import { useActionState, useState } from "react";
import { loginAction, requestOtpAction } from "@/app/(auth)/actions";
import type { FormState } from "@/app/(auth)/form-state";
import { Icon } from "@/components/Icon";
import { useT } from "@/lib/i18n/client";

type Mode = "code" | "password";

export function LoginForm({ notice, next }: { notice?: string; next?: string }) {
  const t = useT();
  const [mode, setMode] = useState<Mode>("code");
  const [sent, sentAction, sending] = useActionState<FormState, FormData>(requestOtpAction, {});
  const [state, action, busy] = useActionState<FormState, FormData>(loginAction, {});
  const [show, setShow] = useState(false);
  const email = state.identifier ?? sent.identifier ?? "";
  const codeStep = mode === "code" && !!sent.ok;
  const noticeBox = notice && !state.error && !sent.ok;

  if (mode === "code" && !codeStep) {
    return (
      <form className="login-card" action={sentAction} noValidate>
        <div>
          <h2 className="display">{t("Welcome back")}</h2>
          <p className="muted" style={{ margin: "10px 0 0", fontSize: 14.5 }}>
            {t("Enter your official email. We'll send you a 6-digit sign-in code.")}
          </p>
        </div>
        {noticeBox && <div className="chip ok" style={{ height: "auto", padding: "10px 12px", whiteSpace: "normal" }} role="status">{t(notice ?? "")}</div>}
        <label className="field">
          {t("Email")}
          <input key={email} defaultValue={email} name="identifier" type="email" autoComplete="username" placeholder="name@company.com" autoFocus required maxLength={200} />
        </label>
        {sent.error && <div className="err" role="alert">{sent.error}</div>}
        <button className="btn btn-gold btn-lg" type="submit" disabled={sending} style={{ width: "100%" }}>
          {sending ? t("Sending code…") : t("Send code")}
          {!sending && <Icon n="arrow" s={16} />}
        </button>
        <button type="button" className="btn btn-ghost" onClick={() => setMode("password")} style={{ width: "100%" }}>{t("Use password instead")}</button>
      </form>
    );
  }

  if (codeStep) {
    return (
      <form className="login-card" action={action} noValidate>
        <div>
          <h2 className="display">{t("Enter your code")}</h2>
          <p className="muted" style={{ margin: "10px 0 0", fontSize: 14.5 }}>
            {t("We sent a 6-digit code to")} <strong>{email}</strong>. {t("It works once and expires in 10 minutes.")}
          </p>
        </div>
        <input type="hidden" name="identifier" value={email} />
        <label className="field">
          {t("Sign-in code")}
          <input name="otp" inputMode="numeric" autoComplete="one-time-code" pattern="[0-9]{6}" maxLength={6} placeholder="000000" autoFocus required
            style={{ letterSpacing: 6, fontSize: 20, fontFamily: "var(--mono, monospace)" }} />
        </label>
        <label style={{ display: "flex", gap: 8, alignItems: "center", cursor: "pointer", fontSize: 13.5, color: "var(--muted)" }}>
          <input type="checkbox" name="remember" defaultChecked style={{ accentColor: "var(--accent)", width: 15, height: 15 }} />
          {t("Keep me signed in for 30 days")}
        </label>
        {next && <input type="hidden" name="next" value={next} />}
        {state.error && <div className="err" role="alert">{state.error}</div>}
        <button className="btn btn-gold btn-lg" type="submit" disabled={busy} style={{ width: "100%" }}>
          {busy ? t("Signing in…") : t("Verify and sign in")}
          {!busy && <Icon n="arrow" s={16} />}
        </button>
        <div style={{ display: "flex", gap: 8 }}>
          <button type="submit" formAction={sentAction} className="btn btn-ghost" disabled={sending} style={{ flex: 1 }}>{sending ? t("Sending…") : t("Resend code")}</button>
          <button type="button" className="btn btn-ghost" onClick={() => setMode("password")} style={{ flex: 1 }}>{t("Use password")}</button>
        </div>
      </form>
    );
  }

  return (
    <form className="login-card" action={action} noValidate>
      <div>
        <h2 className="display">{t("Welcome back")}</h2>
        <p className="muted" style={{ margin: "10px 0 0", fontSize: 14.5 }}>
          {t("Sign in with your official email and password. You'll land on the pages your role can use.")}
        </p>
      </div>
      {noticeBox && <div className="chip ok" style={{ height: "auto", padding: "10px 12px", whiteSpace: "normal" }} role="status">{t(notice ?? "")}</div>}
      <label className="field">
        {t("Email")}
        <input key={state.identifier ?? ""} defaultValue={state.identifier ?? email} name="identifier" type="email" autoComplete="username" placeholder="name@company.com" autoFocus={!state.identifier} required maxLength={200} />
      </label>
      <label className="field">
        <span style={{ display: "flex", justifyContent: "space-between" }}>
          {t("Password")}
          <Link href="/forgot" style={{ fontWeight: 500, textDecoration: "none", fontSize: 13 }}>{t("Forgot?")}</Link>
        </span>
        <span className="pw">
          <input name="password" aria-label={t("Password")} type={show ? "text" : "password"} autoComplete="current-password" placeholder="••••••••" style={{ width: "100%" }} required maxLength={200} />
          <button type="button" onClick={() => setShow(!show)} aria-label={show ? t("Hide password") : t("Show password")}>
            <Icon n={show ? "eyeoff" : "eye"} s={16} />
          </button>
        </span>
      </label>
      <label style={{ display: "flex", gap: 8, alignItems: "center", cursor: "pointer", fontSize: 13.5, color: "var(--muted)" }}>
        <input type="checkbox" name="remember" defaultChecked style={{ accentColor: "var(--accent)", width: 15, height: 15 }} />
        {t("Keep me signed in for 30 days")}
      </label>
      {next && <input type="hidden" name="next" value={next} />}
      {state.error && <div className="err" role="alert">{state.error}</div>}
      <button className="btn btn-gold btn-lg" type="submit" disabled={busy} style={{ width: "100%" }}>
        {busy ? t("Signing in…") : t("Sign in")}
        {!busy && <Icon n="arrow" s={16} />}
      </button>
      <button type="button" className="btn btn-ghost" onClick={() => setMode("code")} style={{ width: "100%" }}>{t("Email me a code instead")}</button>
    </form>
  );
}
