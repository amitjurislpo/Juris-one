import type { Metadata } from "next";
import { ComingInPhase, PageHeader } from "@/components/PageHeader";
import { requirePage } from "@/lib/auth";
import { PAGE_TITLES } from "@/lib/nav";

export const metadata: Metadata = { title: PAGE_TITLES.people };

export default async function Page() {
  await requirePage("people");
  return (
    <div className="col">
      <PageHeader eyebrow="People operations" title={"The"} em={"Juris team"} sub="Employee records, portal access and reporting lines." />
      <ComingInPhase phase={1} what="Employee list, 3-step add/edit, profile drawer and access toggle." />
    </div>
  );
}
