import type { Metadata } from "next";
import { ComingInPhase, PageHeader } from "@/components/PageHeader";
import { requirePage } from "@/lib/auth";
import { PAGE_TITLES } from "@/lib/nav";

export const metadata: Metadata = { title: PAGE_TITLES.team };

export default async function Page() {
  await requirePage("team");
  return (
    <div className="col">
      <PageHeader eyebrow="My team" title={"Your"} em={"team"} sub="People who report to you. Approvals are handled by HR." />
      <ComingInPhase phase={1} what="Team table, today's status and who's away." />
    </div>
  );
}
