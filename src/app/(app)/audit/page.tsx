import type { Metadata } from "next";
import { ComingInPhase, PageHeader } from "@/components/PageHeader";
import { requirePage } from "@/lib/auth";
import { PAGE_TITLES } from "@/lib/nav";

export const metadata: Metadata = { title: PAGE_TITLES.audit };

export default async function Page() {
  await requirePage("audit");
  return (
    <div className="col">
      <PageHeader eyebrow="Administration" title={"Audit"} em={"log"} sub="Who changed what, and when." />
      <ComingInPhase phase={6} what="Filterable audit trail of every sensitive change." />
    </div>
  );
}
