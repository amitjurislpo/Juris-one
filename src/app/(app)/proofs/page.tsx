import type { Metadata } from "next";
import { ComingInPhase, PageHeader } from "@/components/PageHeader";
import { requirePage } from "@/lib/auth";
import { PAGE_TITLES } from "@/lib/nav";

export const metadata: Metadata = { title: PAGE_TITLES.proofs };

export default async function Page() {
  await requirePage("proofs");
  return (
    <div className="col">
      <PageHeader eyebrow="Finance · Old-regime declarations" title={"Investment"} em={"proofs"} sub="Verify what employees declared. Approved amounts reduce their TDS." />
      <ComingInPhase phase={5} what="Proof verification queue." />
    </div>
  );
}
