import type { Metadata } from "next";
import { PageHeader } from "@/components/PageHeader";
import type { AssetRow } from "@/lib/api-types";
import { requirePage } from "@/lib/auth";
import { apiData } from "@/lib/backend";
import { todayIn } from "@/lib/format";
import { getT } from "@/lib/i18n/server";
import { AddAssetButton, AssetsTable } from "./AssetsClient";

export const metadata: Metadata = { title: "Assets" };

/** HR / the owner: the asset register, who holds what, assign and take back. */
export default async function AssetsPage() {
  const u = await requirePage("assets");
  const t = await getT();
  const [rows, people] = await Promise.all([
    apiData<AssetRow[]>("/api/assets"),
    apiData<{ id: string; code: string; name: string }[]>("/api/assets/assignees"),
  ]);
  const today = todayIn(u.timeZone);
  const count = (s: string) => rows.filter((r) => r.status === s).length;

  return (
    <div className="col">
      <PageHeader eyebrow={t("People ops")} title={<>{t("Asset")} <em>{t("tracking")}</em></>}
        sub={t("{total} assets · {assigned} assigned · {available} available · {repair} in repair · {retired} retired", { total: rows.length, assigned: count("ASSIGNED"), available: count("AVAILABLE"), repair: count("IN_REPAIR"), retired: count("RETIRED") })}>
        <AddAssetButton />
      </PageHeader>
      <AssetsTable rows={rows} people={people} today={today} />
    </div>
  );
}
