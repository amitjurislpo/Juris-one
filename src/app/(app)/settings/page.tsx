import type { Metadata } from "next";
import { ComingInPhase, PageHeader } from "@/components/PageHeader";
import { requirePage } from "@/lib/auth";
import { PAGE_TITLES } from "@/lib/nav";

export const metadata: Metadata = { title: PAGE_TITLES.settings };

export default async function Page() {
  await requirePage("settings");
  return (
    <div className="col">
      <PageHeader eyebrow="Administration" title={"Policies &"} em={"access"} sub="Who can see what, and the rules the portal runs on." />
      <ComingInPhase phase={1} what="Read-only permission matrix and policy settings." />
    </div>
  );
}
