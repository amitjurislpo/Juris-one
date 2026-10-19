import type { Metadata } from "next";
import { Icon } from "@/components/Icon";
import { PageHeader } from "@/components/PageHeader";
import type { Form16Mine } from "@/lib/api-types";
import { requirePage } from "@/lib/auth";
import { apiData } from "@/lib/backend";
import { fmtDY, inr } from "@/lib/format";
import { getT } from "@/lib/i18n/server";

export const metadata: Metadata = { title: "Form 16" };

/** The signed-in employee's Form 16 certificates, one per financial year, published by Finance once the year's payroll is closed. */
export default async function Form16Page() {
  await requirePage("form16");
  const t = await getT();
  const rows = await apiData<Form16Mine[]>("/api/form16/mine");

  return (
    <div className="col">
      <PageHeader eyebrow={t("Tax")} title={t("My")} em={t("Form 16")} sub={t("Your tax-deducted-at-source certificate for each financial year. Finance publishes it after the year's last payroll is closed; a published Form 16 never changes.")} />
      <div className="slip-grid">
        {rows.map((r, i) => (
          <div key={r.id} className="card slip-card enter" style={{ ["--i" as string]: i }}>
            <span className="spot"></span>
            <span className="slip-ico"><Icon n="receipt" s={20} /></span>
            <span style={{ display: "flex", flexDirection: "column", gap: 3, textAlign: "left", flex: 1, minWidth: 0 }}>
              <b className="display" style={{ fontSize: 24 }}>{t("FY {fy}", { fy: r.fy })}</b>
              <span className="faint" style={{ fontSize: 12.5 }}>
                {t("Taxable income {income} · tax {tax} · TDS deducted {tds}", { income: inr(r.taxableIncome), tax: inr(r.totalTax), tds: inr(r.tdsTotal) })}
              </span>
              <span className="faint" style={{ fontSize: 11.5 }}>{t("Published {date}", { date: fmtDY(r.publishedAt.slice(0, 10)) })} · {t("fingerprint")} <span className="mono">{r.sha256.slice(0, 16)}</span></span>
            </span>
            <a className="btn btn-sm btn-gold" href={`/api/form16/forms/${r.id}/pdf`} download><Icon n="download" s={14} />{t("PDF")}</a>
          </div>
        ))}
      </div>
      {!rows.length && (
        <div className="empty">
          <b className="display">{t("No Form 16 yet")}</b>
          <span className="muted">{t("It appears here after Finance closes the last payroll month of the financial year (March) and publishes Form 16.")}</span>
        </div>
      )}
    </div>
  );
}
