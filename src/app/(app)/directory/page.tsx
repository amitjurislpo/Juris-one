import type { Metadata } from "next";
import { ComingInPhase, PageHeader } from "@/components/PageHeader";
import { requirePage } from "@/lib/auth";
import { PAGE_TITLES } from "@/lib/nav";

export const metadata: Metadata = { title: PAGE_TITLES.directory };

export default async function Page() {
  await requirePage("directory");
  return (
    <div className="col">
      <PageHeader eyebrow="Directory" title={"Find"} em={"anyone"} sub="Search by name, email or designation." />
      <ComingInPhase phase={1} what="Searchable company directory." />
    </div>
  );
}
