import type { Metadata } from "next";
import Link from "next/link";
import { Icon } from "@/components/Icon";
import { PageHeader } from "@/components/PageHeader";
import type { Form16Overview } from "@/lib/api-types";
import { requirePage } from "@/lib/auth";
import { apiData, qs as toQs } from "@/lib/backend";
import { fmtDY } from "@/lib/format";
import { getT } from "@/lib/i18n/server";
import { DeductorForm, PublishButton } from "./Form16AdminClient";

export const metadata: Metadata = { title: "Form 16 & 24Q" };

/** Finance: publish Form 16 for a financial year once all 12 months are closed; the Form 24Q working file per quarter. */
export default async function Form16AdminPage(props: PageProps<"/form16-admin">) {
  const sp = await props.searchParams;
  await requirePage("form16-admin");
  const t = await getT();
  const d = await apiData<Form16Overview>(`/api/form16${toQs({ fy: sp.fy })}`);
  const pending = d.rows.filter((r) => !r.formId).length;

  return (
    <div className="col">
      <PageHeader eyebrow={t("Finance · Financial year {fy}", { fy: d.fy })} title={t("Form 16")} em="& 24Q"
        sub={t("Form 16 is built from the year's closed payslips and each person's tax profile. It can be published only when all 12 months are closed, and a published Form 16 never changes.")}>
        {d.canPublish && d.ready && d.rows.length > 0 && <PublishButton fy={d.fy} pending={pending} />}
      </PageHeader>

      <div className="seg enter" role="group" aria-label={t("Financial year")} style={{ ["--i" as string]: 1, alignSelf: "flex-start" }}>
        {d.fys.map((fy) => <Link key={fy} href={`/form16-admin?fy=${fy}`} className={fy === d.fy ? "on" : ""}>{t("FY {fy}", { fy })}</Link>)}
      </div>

      <section className="card enter" style={{ ["--i" as string]: 2, padding: 18 }}>
        <div className="card-h"><h3>{t("Payroll months")}</h3>{d.ready ? <span className="chip ok">{t("All 12 closed")}</span> : <span className="chip warn">{t("{n} not closed", { n: d.missing.length })}</span>}</div>
        <div style={{ display: "flex", flexWrap: "wrap", gap: 8 }}>
          {d.months.map((m) => <span key={m.ym} className={`chip ${m.closed ? "ok" : "warn"}`}>{m.label}</span>)}
        </div>
        {!d.ready && <p className="muted" style={{ marginTop: 12 }}>{t("Form 16 for FY {fy} can't be published yet. Still open or missing:", { fy: d.fy })} <b>{d.missing.join(", ")}</b>.</p>}
      </section>

      <section className="card enter" style={{ ["--i" as string]: 3, padding: 18 }}>
        <div className="card-h"><h3>{t("Deductor details")}</h3></div>
        <DeductorForm d={d.deductor} canEdit={d.canPublish} />
      </section>

      <section className="card enter" style={{ ["--i" as string]: 4, padding: 18 }}>
        <div className="card-h"><h3>{t("Form 24Q working file")}</h3></div>
        <p className="muted" style={{ marginTop: 0 }}>
          {t("The data a quarterly TDS return (Form 24Q) needs: employee PAN, amount paid and TDS deducted for each month. The challan columns are left blank for you to fill.")}
          {" "}{t("This is a")} <b>{t("working file, not a filed return")}</b>. {t("Downloading it is recorded in the audit log because it contains PAN numbers.")}
        </p>
        {d.canPublish ? (
          <div style={{ display: "flex", gap: 8, flexWrap: "wrap" }}>
            {["Q1", "Q2", "Q3", "Q4"].map((q) => (
              <a key={q} className="btn" href={`/api/form16/${d.fy}/24q?quarter=${q}`} download><Icon n="download" s={14} />{q} ({t(["Apr–Jun", "Jul–Sep", "Oct–Dec", "Jan–Mar"][Number(q[1]) - 1])})</a>
            ))}
          </div>
        ) : <span className="muted">{t("Needs the permission to close payroll.")}</span>}
      </section>

      <section className="card enter" style={{ ["--i" as string]: 5 }}>
        <div className="table-wrap">
          <table className="t">
            <thead><tr><th>{t("Employee")}</th><th className="hide-sm">{t("Payslips")}</th><th>{t("Form 16")}</th><th></th></tr></thead>
            <tbody>
              {d.rows.map((r) => (
                <tr key={r.employeeId}>
                  <td><b>{r.name}</b> <span className="muted mono" style={{ fontSize: 12 }}>{r.code}</span></td>
                  <td className="muted hide-sm">{r.payslips === 1 ? t("1 month") : t("{n} months", { n: r.payslips })}</td>
                  <td>{r.formId ? <span className="chip ok">{t("Published {date}", { date: fmtDY((r.publishedAt ?? "").slice(0, 10)) })}</span> : <span className="chip neutral">{t("Not published")}</span>}</td>
                  <td className="r">{r.formId && <a className="btn btn-sm" href={`/api/form16/forms/${r.formId}/pdf`} download><Icon n="download" s={14} />{t("PDF")}</a>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {!d.rows.length && <div className="empty"><b className="display">{t("No payslips in FY {fy} yet", { fy: d.fy })}</b><span className="muted">{t("Employees appear here once a payroll month of the year is closed.")}</span></div>}
      </section>
    </div>
  );
}
