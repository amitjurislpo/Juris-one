import type { Metadata } from "next";
import { TokenPage } from "@/components/auth/TokenPage";

export const metadata: Metadata = { title: "Activate your account" };

export default async function InvitePage(props: PageProps<"/invite/[token]">) {
  const { token } = await props.params;
  return <TokenPage token={token} purpose="INVITE" />;
}
