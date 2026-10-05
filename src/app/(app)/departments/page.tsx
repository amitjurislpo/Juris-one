import type { Metadata } from "next";
import { ComingInPhase, PageHeader } from "@/components/PageHeader";
import { requirePage } from "@/lib/auth";
import { PAGE_TITLES } from "@/lib/nav";

export const metadata: Metadata = { title: PAGE_TITLES.departments };

export default async function Page() {
  await requirePage("departments");
  return (
    <div className="col">
      <PageHeader eyebrow="People operations" title={<>Depart<em>ments</em></>} sub="Create departments first — new employees are added into one of these." />
      <ComingInPhase phase={1} what="Department list with heads and headcount." />
    </div>
  );
}
