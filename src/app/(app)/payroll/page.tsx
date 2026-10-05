import type { Metadata } from "next";
import { ComingInPhase, PageHeader } from "@/components/PageHeader";
import { requirePage } from "@/lib/auth";
import { PAGE_TITLES } from "@/lib/nav";

export const metadata: Metadata = { title: PAGE_TITLES.payroll };

export default async function Page() {
  await requirePage("payroll");
  return (
    <div className="col">
      <PageHeader eyebrow="Finance · Payroll" title={"Monthly"} em={"payroll"} sub="Salary comes from each employee's current salary structure." />
      <ComingInPhase phase={4} what="Payroll run: lock, compute, review, approve, close & publish." />
    </div>
  );
}
