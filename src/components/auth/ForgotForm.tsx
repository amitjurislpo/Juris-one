"use client";

import Link from "next/link";
import { useActionState } from "react";
import { forgotAction } from "@/app/(auth)/actions";
import type { FormState } from "@/app/(auth)/form-state";
import { Icon } from "@/components/Icon";

export function ForgotForm() {
  const [state, action, busy] = useActionState<FormState, FormData>(forgotAction, {});
  return (
    <form className="login-card" action={action} noValidate>
      <div>
        <h2 className="display">Reset password</h2>
        <p className="muted" style={{ margin: "10px 0 0", fontSize: 14.5 }}>
          Enter your official email. We&apos;ll send a one-time link to it.
        </p>
      </div>
      <label className="field">
        Email
        <input name="identifier" type="email" autoComplete="username" placeholder="name@company.com" autoFocus required maxLength={200} />
      </label>
      {state.error && <div className="err" role="alert">{state.error}</div>}
      {state.ok && <div className="chip ok" style={{ height: "auto", padding: "10px 12px", whiteSpace: "normal" }} role="status">{state.ok}</div>}
      <button className="btn btn-gold btn-lg" type="submit" disabled={busy} style={{ width: "100%" }}>
        {busy ? "Sending…" : "Send reset link"}
        {!busy && <Icon n="arrow" s={16} />}
      </button>
      <Link href="/login" className="btn btn-ghost" style={{ alignSelf: "flex-start" }}><Icon n="chevL" s={15} />Back to sign in</Link>
    </form>
  );
}
