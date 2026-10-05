import type { Metadata } from "next";
import { ComingInPhase, PageHeader } from "@/components/PageHeader";
import { requirePage } from "@/lib/auth";
import { PAGE_TITLES } from "@/lib/nav";

export const metadata: Metadata = { title: PAGE_TITLES.approvals };

export default async function Page() {
  await requirePage("approvals");
  return (
    <div className="col">
      <PageHeader eyebrow="Approvals" title={"Requests"} em={"awaiting you"} sub="Leave and attendance regularizations." />
      <ComingInPhase phase={3} what="Leave and attendance approval queues." />
    </div>
  );
}
