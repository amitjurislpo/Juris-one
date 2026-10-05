import type { Metadata } from "next";
import { ComingInPhase, PageHeader } from "@/components/PageHeader";
import { requirePage } from "@/lib/auth";
import { PAGE_TITLES } from "@/lib/nav";

export const metadata: Metadata = { title: PAGE_TITLES.attendance };

export default async function Page() {
  await requirePage("attendance");
  return (
    <div className="col">
      <PageHeader eyebrow="Attendance" title={"My"} em={"attendance"} sub="Tap any day to see its check-in, check-out and how it counts." />
      <ComingInPhase phase={2} what="Month calendar, day detail, KPIs and regularization requests." />
    </div>
  );
}
