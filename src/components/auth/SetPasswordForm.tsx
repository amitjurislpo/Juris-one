"use client";

import { useActionState, useState } from "react";
import { setPasswordAction } from "@/app/(auth)/actions";
import type { FormState } from "@/app/(auth)/form-state";
import { Icon } from "@/components/Icon";

export function SetPasswordForm({ token, purpose, name, code, rules }: { token: string; purpose: "INVITE" | "RESET"; name: string; code: string; rules: string }) {
  const [state, action, busy] = useActionState<FormState, FormData>(setPasswordAction, {});
  const [show, setShow] = useState(false);
  return (
    <form className="login-card" action={action} noValidate>
      <div>
        <span className="eyebrow">{code}</span>
        <h2 className="display">{purpose === "INVITE" ? `Welcome, ${name.split(" ")[0]}` : "Choose a new password"}</h2>
        <p className="muted" style={{ margin: "10px 0 0", fontSize: 14.5 }}>
          {purpose === "INVITE" ? "Set a password to activate your JurisOne account." : "Signing in again will be required on every device."}
        </p>
      </div>
      <input type="hidden" name="token" value={token} />
      <input type="hidden" name="purpose" value={purpose} />
      <label className="field">
        New password <span className="hint">{rules}</span>
        <span className="pw">
          <input name="password" type={show ? "text" : "password"} autoComplete="new-password" style={{ width: "100%" }} required minLength={10} maxLength={200} autoFocus />
          <button type="button" onClick={() => setShow(!show)} aria-label={show ? "Hide password" : "Show password"}><Icon n={show ? "eyeoff" : "eye"} s={16} /></button>
        </span>
      </label>
      <label className="field">
        Confirm password
        <input name="confirm" type={show ? "text" : "password"} autoComplete="new-password" required maxLength={200} />
      </label>
      {state.error && <div className="err" role="alert">{state.error}</div>}
      <button className="btn btn-gold btn-lg" type="submit" disabled={busy} style={{ width: "100%" }}>
        {busy ? "Saving…" : purpose === "INVITE" ? "Activate account" : "Update password"}
        {!busy && <Icon n="arrow" s={16} />}
      </button>
    </form>
  );
}
