import type { Metadata } from "next";
import { ComingInPhase, PageHeader } from "@/components/PageHeader";
import { requirePage } from "@/lib/auth";
import { PAGE_TITLES } from "@/lib/nav";

export const metadata: Metadata = { title: PAGE_TITLES.leave };

export default async function Page() {
  await requirePage("leave");
  return (
    <div className="col">
      <PageHeader eyebrow="Leave" title={"Time away,"} em={"well planned"} sub="Apply in three steps — leave type, dates, and an optional reason." />
      <ComingInPhase phase={3} what="Balances, leave requests and the holiday calendar." />
    </div>
  );
}
