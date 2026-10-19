"use client";

import { useActionState, useEffect, useRef, useState } from "react";
import { loginAction, requestOtpAction } from "@/app/(auth)/actions";
import type { FormState } from "@/app/(auth)/form-state";
import { Icon } from "@/components/Icon";
import { useT } from "@/lib/i18n/client";
import { OtpInput } from "./OtpInput";

const RESEND_SECONDS = 30;

/** a***@company.com — enough for the person to spot a mistyped address, without printing the whole thing. */
function maskEmail(email: string) {
  const at = email.indexOf("@");
  if (at < 1) return email;
  return `${email[0]}${"•".repeat(Math.min(Math.max(at - 1, 2), 6))}${email.slice(at)}`;
}

/**
 * Sign-in is by emailed code only: step 1 asks for the email, step 2 for the 6-digit code. Step 2 always offers a way
 * back to step 1 with the address filled in, so a mistyped email can be corrected.
 * `devCode` is set only in the dev environment, where every code is that fixed value.
 */
export function LoginForm({ notice, next, devCode }: { notice?: string; next?: string; devCode?: string }) {
  const t = useT();
  const [sent, sentAction, sending] = useActionState<FormState, FormData>(requestOtpAction, {});
  const [state, action, busy] = useActionState<FormState, FormData>(loginAction, {});
  const formRef = useRef<HTMLFormElement>(null);
  // "Change email" hides the code step for the code that was sent; asking for a new code (a new result) shows it again
  const [dismissed, setDismissed] = useState<FormState | null>(null);
  const codeStep = !!sent.ok && dismissed !== sent;
  const email = sent.identifier ?? state.identifier ?? "";

  // a new code was sent, or a code was refused: clear the boxes and restart the resend timer (adjusted while rendering)
  const [seenSent, setSeenSent] = useState(sent);
  const [seenState, setSeenState] = useState(state);
  const [boxes, setBoxes] = useState(0);
  const [cooldown, setCooldown] = useState(0);
  if (seenSent !== sent) {
    setSeenSent(sent);
    if (sent.ok) { setBoxes((n) => n + 1); setCooldown(RESEND_SECONDS); }
  }
  if (seenState !== state) {
    setSeenState(state);
    setBoxes((n) => n + 1);
  }
  useEffect(() => {
    if (cooldown <= 0) return;
    const id = setTimeout(() => setCooldown((c) => c - 1), 1000);
    return () => clearTimeout(id);
  }, [cooldown]);

  const noticeBox = notice && !state.error && !sent.ok;

  if (!codeStep) {
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
      </form>
    );
  }

  return (
    <form ref={formRef} className="login-card" action={action} noValidate>
      <button type="button" className="btn btn-ghost btn-sm" onClick={() => setDismissed(sent)} style={{ alignSelf: "flex-start", marginLeft: -8 }}>
        <Icon n="chevL" s={15} />{t("Back")}
      </button>
      <div>
        <span className="otp-mail" aria-hidden="true"><Icon n="inbox" s={22} /></span>
        <h2 className="display">{t("Check your email")}</h2>
        <p className="muted" style={{ margin: "10px 0 0", fontSize: 14.5 }}>
          {t("We sent a 6-digit code to")} <strong style={{ color: "var(--text, inherit)" }}>{maskEmail(email)}</strong>. {t("It works once and expires in 10 minutes.")}
        </p>
        <p className="otp-wrong">
          {t("Wrong email?")}{" "}
          <button type="button" className="linklike" onClick={() => setDismissed(sent)}>{t("Change email")}</button>
        </p>
      </div>
      <input type="hidden" name="identifier" value={email} />
      <OtpInput key={boxes} invalid={!!state.error} disabled={busy} onComplete={() => setTimeout(() => formRef.current?.requestSubmit(), 0)} />
      {devCode && <div className="chip info" style={{ height: "auto", padding: "8px 12px", whiteSpace: "normal" }} role="note">{t("Development: the code is")} <b className="num">{devCode}</b></div>}
      <div aria-live="polite">{state.error && <div className="err" role="alert">{state.error}</div>}{sent.error && !state.error && <div className="err" role="alert">{sent.error}</div>}</div>
      <label style={{ display: "flex", gap: 8, alignItems: "center", cursor: "pointer", fontSize: 13.5, color: "var(--muted)" }}>
        <input type="checkbox" name="remember" defaultChecked style={{ accentColor: "var(--accent)", width: 15, height: 15 }} />
        {t("Keep me signed in for 30 days")}
      </label>
      {next && <input type="hidden" name="next" value={next} />}
      <button className="btn btn-gold btn-lg" type="submit" disabled={busy} style={{ width: "100%" }}>
        {busy ? t("Signing in…") : t("Verify and sign in")}
        {!busy && <Icon n="arrow" s={16} />}
      </button>
      <div className="otp-resend">
        <span className="muted">{t("Didn't get it?")}</span>
        <button type="submit" formAction={sentAction} className="linklike" disabled={sending || cooldown > 0}>
          {sending ? t("Sending…") : cooldown > 0 ? t("Resend code in {s}s", { s: cooldown }) : t("Resend code")}
        </button>
      </div>
    </form>
  );
}
