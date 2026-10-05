import type { Metadata } from "next";
import { ComingInPhase, PageHeader } from "@/components/PageHeader";
import { requirePage } from "@/lib/auth";
import { PAGE_TITLES } from "@/lib/nav";

export const metadata: Metadata = { title: PAGE_TITLES.roles };

export default async function Page() {
  await requirePage("roles");
  return (
    <div className="col">
      <PageHeader eyebrow="Administration" title={"Roles &"} em={"permissions"} sub="A person's role decides what they see after signing in." />
      <ComingInPhase phase={1} what="Toggle permissions, custom roles and assignable roles." />
    </div>
  );
}
