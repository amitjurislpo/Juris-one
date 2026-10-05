import type { Metadata } from "next";
import { ComingInPhase, PageHeader } from "@/components/PageHeader";
import { requirePage } from "@/lib/auth";
import { PAGE_TITLES } from "@/lib/nav";

export const metadata: Metadata = { title: PAGE_TITLES.payslips };

export default async function Page() {
  await requirePage("payslips");
  return (
    <div className="col">
      <PageHeader eyebrow="Payslips" title={"My"} em={"payslips"} sub="A month's payslip appears here once Finance closes that month's payroll." />
      <ComingInPhase phase={4} what="Monthly payslips with PDF download." />
    </div>
  );
}
