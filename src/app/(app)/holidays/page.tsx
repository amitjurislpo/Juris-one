import type { Metadata } from "next";
import { ComingInPhase, PageHeader } from "@/components/PageHeader";
import { requirePage } from "@/lib/auth";
import { PAGE_TITLES } from "@/lib/nav";

export const metadata: Metadata = { title: PAGE_TITLES.holidays };

export default async function Page() {
  await requirePage("holidays");
  return (
    <div className="col">
      <PageHeader eyebrow="People operations" title={"Holiday"} em={"calendar"} sub="India and US teams each see their own holidays — plus the ones marked for everyone." />
      <ComingInPhase phase={2} what="Month calendar and holiday management." />
    </div>
  );
}
