import type { Metadata } from "next";
import { ComingInPhase, PageHeader } from "@/components/PageHeader";
import { requirePage } from "@/lib/auth";
import { PAGE_TITLES } from "@/lib/nav";

export const metadata: Metadata = { title: PAGE_TITLES.overview };

export default async function Page() {
  await requirePage("overview");
  return (
    <div className="col">
      <PageHeader eyebrow="Administration" title={"Company"} em={"overview"} sub="Everything HR and Finance are working on, in one place." />
      <ComingInPhase phase={6} what="Headcount, check-ins, approvals, payroll status and queues." />
    </div>
  );
}
