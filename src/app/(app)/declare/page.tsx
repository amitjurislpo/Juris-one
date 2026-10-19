import type { Metadata } from "next";
import { PageHeader } from "@/components/PageHeader";
import { getT } from "@/lib/i18n/server";
import { requirePage } from "@/lib/auth";
import { apiData } from "@/lib/backend";
import { DeclareClient, type ItemView } from "./DeclareClient";

export const metadata: Metadata = { title: "Tax declarations" };

type Declare =
  | { fy: string; india: false }
  | { fy: string; india: true; hasStructure: false }
  | {
      fy: string; india: true; hasStructure: true; regime: "NEW" | "OLD"; overridden: boolean; canChangeRegime: boolean; windowOpen: boolean; windowText: string;
      newTax: number; oldTax: number; monthlyTds: number; epf: number; profile: { metro: boolean; parentsSenior: boolean; landlordPan: string }; items: ItemView[];
    };

export default async function DeclarePage() {
  await requirePage("declare");
  const t = await getT();
  const d = await apiData<Declare>("/api/declarations");
  if (!d.india) {
    return <div className="col"><PageHeader eyebrow={t("Tax year {fy}", { fy: d.fy })} title={t("Tax")} em={t("declarations")} /><div className="empty"><b className="display">{t("Not applicable")}</b><span className="muted">{t("Tax declarations are for the India payroll. US team taxes are handled by the US payroll provider.")}</span></div></div>;
  }
  if (!d.hasStructure) {
    return <div className="col"><PageHeader eyebrow={t("Tax year {fy}", { fy: d.fy })} title={t("Tax")} em={t("declarations")} /><div className="empty"><b className="display">{t("No salary structure yet")}</b><span className="muted">{t("Finance hasn't set up your salary structure yet — tax estimates appear once it's in place.")}</span></div></div>;
  }
  return (
    <div className="col">
      <PageHeader eyebrow={t("Tax year {fy}", { fy: d.fy })} title={t("Tax")} em={t("declarations")} sub={t("Choose your tax regime. On the old regime, declare investments and upload proofs — Finance verifies them before they reduce your TDS.")} />
      <DeclareClient
        regime={d.regime}
        overridden={d.overridden}
        canChangeRegime={d.canChangeRegime}
        windowOpen={d.windowOpen}
        windowText={d.windowText}
        newTax={d.newTax}
        oldTax={d.oldTax}
        monthlyTds={d.monthlyTds}
        epf={d.epf}
        profile={d.profile}
        items={d.items}
      />
    </div>
  );
}
