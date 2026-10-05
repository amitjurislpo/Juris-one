import type { Metadata } from "next";
import { ComingInPhase, PageHeader } from "@/components/PageHeader";
import { requirePage } from "@/lib/auth";
import { PAGE_TITLES } from "@/lib/nav";

export const metadata: Metadata = { title: PAGE_TITLES.tax };

export default async function Page() {
  await requirePage("tax");
  return (
    <div className="col">
      <PageHeader eyebrow="Finance · Tax year 2026–27" title={"Tax"} em={"& TDS"} sub="Monthly TDS for every employee, based on their regime and verified investment proofs." />
      <ComingInPhase phase={5} what="Per-employee tax computation and regime override." />
    </div>
  );
}
