import type { Metadata } from "next";
import { PageHeader } from "@/components/PageHeader";
import type { AssetRow } from "@/lib/api-types";
import { requirePage } from "@/lib/auth";
import { apiData } from "@/lib/backend";
import { todayIn } from "@/lib/format";
import { AddAssetButton, AssetsTable } from "./AssetsClient";

export const metadata: Metadata = { title: "Assets" };

/** HR / the owner: the asset register, who holds what, assign and take back. */
export default async function AssetsPage() {
  const u = await requirePage("assets");
  const [rows, people] = await Promise.all([
    apiData<AssetRow[]>("/api/assets"),
    apiData<{ id: string; code: string; name: string }[]>("/api/assets/assignees"),
  ]);
  const today = todayIn(u.timeZone);
  const count = (s: string) => rows.filter((r) => r.status === s).length;

  return (
    <div className="col">
      <PageHeader eyebrow="People ops" title={<>Asset <em>tracking</em></>}
        sub={`${rows.length} assets · ${count("ASSIGNED")} assigned · ${count("AVAILABLE")} available · ${count("IN_REPAIR")} in repair · ${count("RETIRED")} retired`}>
        <AddAssetButton />
      </PageHeader>
      <AssetsTable rows={rows} people={people} today={today} />
    </div>
  );
}
