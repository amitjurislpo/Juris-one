"use client";

import Link from "next/link";
import { useActionState, useState } from "react";
import { loginAction } from "@/app/(auth)/actions";
import type { FormState } from "@/app/(auth)/form-state";
import { Icon } from "@/components/Icon";

export function LoginForm({ notice, next }: { notice?: string; next?: string }) {
  const [state, action, busy] = useActionState<FormState, FormData>(loginAction, {});
  const [show, setShow] = useState(false);
  return (
    <form className="login-card" action={action} noValidate>
      <div>
        <h2 className="display">Welcome back</h2>
        <p className="muted" style={{ margin: "10px 0 0", fontSize: 14.5 }}>
          Sign in with your employee ID or official email. You&apos;ll land on the pages your role can use.
        </p>
      </div>
      {notice && !state.error && <div className="chip ok" style={{ height: "auto", padding: "10px 12px", whiteSpace: "normal" }} role="status">{notice}</div>}
      <label className="field">
        Employee ID or email
        <input key={state.identifier ?? ""} defaultValue={state.identifier} name="identifier" autoComplete="username" placeholder="e.g. JC-074" autoFocus={!state.identifier} required maxLength={200} />
      </label>
      <label className="field">
        <span style={{ display: "flex", justifyContent: "space-between" }}>
          Password
          <Link href="/forgot" style={{ fontWeight: 500, textDecoration: "none", fontSize: 13 }}>Forgot?</Link>
        </span>
        <span className="pw">
          <input name="password" aria-label="Password" type={show ? "text" : "password"} autoComplete="current-password" placeholder="••••••••" style={{ width: "100%" }} required maxLength={200} />
          <button type="button" onClick={() => setShow(!show)} aria-label={show ? "Hide password" : "Show password"}>
            <Icon n={show ? "eyeoff" : "eye"} s={16} />
          </button>
        </span>
      </label>
      <label style={{ display: "flex", gap: 8, alignItems: "center", cursor: "pointer", fontSize: 13.5, color: "var(--muted)" }}>
        <input type="checkbox" name="remember" defaultChecked style={{ accentColor: "var(--accent)", width: 15, height: 15 }} />
        Keep me signed in for 30 days
      </label>
      {next && <input type="hidden" name="next" value={next} />}
      {state.error && <div className="err" role="alert">{state.error}</div>}
      <button className="btn btn-gold btn-lg" type="submit" disabled={busy} style={{ width: "100%" }}>
        {busy ? "Signing in…" : "Sign in"}
        {!busy && <Icon n="arrow" s={16} />}
      </button>
    </form>
  );
}
