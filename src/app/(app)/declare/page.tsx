import type { Metadata } from "next";
import { ComingInPhase, PageHeader } from "@/components/PageHeader";
import { requirePage } from "@/lib/auth";
import { PAGE_TITLES } from "@/lib/nav";

export const metadata: Metadata = { title: PAGE_TITLES.declare };

export default async function Page() {
  await requirePage("declare");
  return (
    <div className="col">
      <PageHeader eyebrow="Tax year 2026–27" title={"Tax"} em={"declarations"} sub="Choose your tax regime. On the old regime, declare investments and upload proofs." />
      <ComingInPhase phase={5} what="Regime comparison, investment declarations and proof upload." />
    </div>
  );
}
