import type { Metadata } from "next";
import { TokenPage } from "@/components/auth/TokenPage";

export const metadata: Metadata = { title: "Choose a new password" };

export default async function ResetPage(props: PageProps<"/reset/[token]">) {
  const { token } = await props.params;
  return <TokenPage token={token} purpose="RESET" />;
}
