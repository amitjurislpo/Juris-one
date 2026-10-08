import Link from "next/link";
import { AuthFrame } from "@/components/auth/AuthFrame";
import { SetPasswordForm } from "@/components/auth/SetPasswordForm";
import { apiCall } from "@/lib/backend";
import { PASSWORD_RULES } from "@/lib/password-rules";

/** Shared by /invite/[token] and /reset/[token]. */
export async function TokenPage({ token, purpose }: { token: string; purpose: "INVITE" | "RESET" }) {
  const r = await apiCall<{ name: string; code: string }>("GET", `/api/auth/tokens/${purpose}/${encodeURIComponent(token)}`);
  const t = r.body.ok ? r.body.data! : null;
  return (
    <AuthFrame>
      {t ? (
        <SetPasswordForm token={token} purpose={purpose} name={t.name} code={t.code} rules={PASSWORD_RULES} />
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
