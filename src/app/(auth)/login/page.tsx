import type { Metadata } from "next";
import { redirect } from "next/navigation";
import { AuthFrame } from "@/components/auth/AuthFrame";
import { LoginForm } from "@/components/auth/LoginForm";
import { getCurrentUser, landingPath } from "@/lib/auth";

export const metadata: Metadata = { title: "Sign in" };

export default async function LoginPage(props: PageProps<"/login">) {
  const u = await getCurrentUser();
  if (u) redirect(landingPath(u));
  const q = await props.searchParams;
  const notice = q.signedout
    ? "Signed out securely."
    : q.reset
      ? "Password updated. Sign in with your new password."
      : q.activated
        ? "Your account is ready. Sign in with your new password."
        : undefined;
  // only the dev environment shows the fixed code (every other environment sends real codes by email)
  const devCode = process.env.APP_ENV === "dev" ? process.env.OTP_FIXED_CODE || undefined : undefined;
  const next = typeof q.next === "string" ? q.next : undefined;
  return (
    <AuthFrame>
      <LoginForm notice={notice} next={next} devCode={devCode} />
    </AuthFrame>
  );
}
