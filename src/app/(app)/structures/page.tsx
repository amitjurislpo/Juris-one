import type { Metadata } from "next";
import { ComingInPhase, PageHeader } from "@/components/PageHeader";
import { requirePage } from "@/lib/auth";
import { PAGE_TITLES } from "@/lib/nav";

export const metadata: Metadata = { title: PAGE_TITLES.structures };

export default async function Page() {
  await requirePage("structures");
  return (
    <div className="col">
      <PageHeader eyebrow="Finance · Compensation" title={"Salary"} em={"structures"} sub="Set a breakup once. Payroll and TDS use it every month until you add a revision." />
      <ComingInPhase phase={4} what="Effective-dated salary structures." />
    </div>
  );
}
