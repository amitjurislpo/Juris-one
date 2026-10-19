import type { Metadata } from "next";
import { Icon } from "@/components/Icon";
import { PageHeader } from "@/components/PageHeader";
import type { IssuedLetter } from "@/lib/api-types";
import { requirePage } from "@/lib/auth";
import { apiData } from "@/lib/backend";
import { fmtDY } from "@/lib/format";
import { getT } from "@/lib/i18n/server";

export const metadata: Metadata = { title: "My letters" };

/** Letters HR has issued to the signed-in employee (offer, appointment, experience, relieving, employment verification). */
export default async function MyLettersPage() {
  await requirePage("my-letters");
  const t = await getT();
  const rows = await apiData<IssuedLetter[]>("/api/letters/mine");

  return (
    <div className="col">
      <PageHeader eyebrow={t("Documents")} title={t("My")} em={t("letters")} sub={t("Letters issued to you by HR. Each has a reference number and never changes once issued; download the PDF for your records.")} />
      <div className="slip-grid">
        {rows.map((r, i) => (
          <div key={r.id} className="card slip-card enter" style={{ ["--i" as string]: i }}>
            <span className="spot"></span>
            <span className="slip-ico"><Icon n="doc" s={20} /></span>
            <span style={{ display: "flex", flexDirection: "column", gap: 3, textAlign: "left", flex: 1, minWidth: 0 }}>
              <b className="display" style={{ fontSize: 20 }}>{r.subject}</b>
              <span className="faint" style={{ fontSize: 12.5 }}>{r.templateName} · {t("issued {date} by {name}", { date: fmtDY(r.issuedAt.slice(0, 10)), name: r.issuedBy })}</span>
              <span className="faint mono" style={{ fontSize: 11.5 }}>{r.reference}</span>
            </span>
            <a className="btn btn-sm btn-gold" href={`/api/letters/${r.id}/pdf`} download><Icon n="download" s={14} />{t("PDF")}</a>
          </div>
        ))}
      </div>
      {!rows.length && (
        <div className="empty">
          <b className="display">{t("No letters yet")}</b>
          <span className="muted">{t("Letters HR issues to you, such as an appointment or experience letter, will appear here.")}</span>
        </div>
      )}
    </div>
  );
}
