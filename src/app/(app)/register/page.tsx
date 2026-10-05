import type { Metadata } from "next";
import { ComingInPhase, PageHeader } from "@/components/PageHeader";
import { requirePage } from "@/lib/auth";
import { PAGE_TITLES } from "@/lib/nav";

export const metadata: Metadata = { title: PAGE_TITLES.register };

export default async function Page() {
  await requirePage("register");
  return (
    <div className="col">
      <PageHeader eyebrow="People operations · Attendance" title={"Attendance"} em={"register"} sub="Everyone's check-ins across India and US teams — shown in the time zone you pick." />
      <ComingInPhase phase={2} what="Daily log with time-zone selector, monthly grid and export." />
    </div>
  );
}
