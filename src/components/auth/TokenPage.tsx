import Link from "next/link";
import { AuthFrame } from "@/components/auth/AuthFrame";
import { SetPasswordForm } from "@/components/auth/SetPasswordForm";
import { findUsableToken } from "@/lib/auth-tokens";
import { PASSWORD_RULES } from "@/lib/password";

/** Shared by /invite/[token] and /reset/[token]. */
export async function TokenPage({ token, purpose }: { token: string; purpose: "INVITE" | "RESET" }) {
  const t = await findUsableToken(token, purpose);
  return (
    <AuthFrame>
      {t ? (
        <SetPasswordForm token={token} purpose={purpose} name={t.employee.fullName} code={t.employee.code} rules={PASSWORD_RULES} />
      ) : (
        <div className="login-card">
          <h2 className="display">Link expired</h2>
          <p className="muted" style={{ margin: 0, fontSize: 14.5 }}>
            This link has expired or was already used. {purpose === "INVITE" ? "Ask HR to resend your invite, or request" : "Request"} a new link from the sign-in page.
          </p>
          <Link href="/forgot" className="btn btn-gold btn-lg" style={{ width: "100%" }}>Get a new link</Link>
          <Link href="/login" className="btn btn-ghost" style={{ alignSelf: "flex-start" }}>Back to sign in</Link>
        </div>
      )}
    </AuthFrame>
  );
}
