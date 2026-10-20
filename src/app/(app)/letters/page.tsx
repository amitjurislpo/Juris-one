import type { Metadata } from "next";
import { Icon } from "@/components/Icon";
import { PageHeader } from "@/components/PageHeader";
import type { IssuedLetter, LetterField, LetterTemplateRow } from "@/lib/api-types";
import { can, requirePage } from "@/lib/auth";
import { apiData } from "@/lib/backend";
import { fmtDY, isoDateIn } from "@/lib/format";
import { getT } from "@/lib/i18n/server";
import { IssueLetter, TemplatesTable, TYPE_LABEL, type EmployeeChoice } from "./LettersClient";

export const metadata: Metadata = { title: "HR letters" };

/** HR: issue offer, appointment, experience, relieving and employment-verification letters from editable templates. */
export default async function LettersPage() {
  const u = await requirePage("letters");
  const t = await getT();
  const [templates, employees, issued, meta] = await Promise.all([
    apiData<LetterTemplateRow[]>("/api/letters/templates"),
    apiData<EmployeeChoice[]>("/api/letters/employees"),
    apiData<IssuedLetter[]>("/api/letters"),
    apiData<{ fields: LetterField[] }>("/api/letters/fields"),
  ]);

  return (
    <div className="col">
      <PageHeader eyebrow={t("People ops")} title={t("HR")} em={t("letters")}
        sub={t("Pick a template and an employee. The letter gets a reference number, a PDF and a permanent record, and the employee finds it under My letters. Letters that state a salary can only be issued by someone who manages salary structures.")} />
      <IssueLetter templates={templates} employees={employees} canSalary={can(u, "struct.manage")} />
      <TemplatesTable templates={templates} fields={meta.fields} />

      <section className="card enter" style={{ ["--i" as string]: 3 }}>
        <div className="card-h" style={{ padding: "16px 18px 0" }}><h3>{t("Issued letters")}</h3></div>
        <div className="table-wrap">
          <table className="t">
            <thead><tr><th>{t("Reference")}</th><th>{t("Employee")}</th><th className="hide-sm">{t("Letter")}</th><th className="hide-sm">{t("Issued")}</th><th></th></tr></thead>
            <tbody>
              {issued.map((l) => (
                <tr key={l.id}>
                  <td className="mono" style={{ fontSize: 12.5 }}>{l.reference}</td>
                  <td><b>{l.employeeName}</b> <span className="muted mono" style={{ fontSize: 12 }}>{l.employeeCode}</span></td>
                  <td className="muted hide-sm">{t(TYPE_LABEL[l.type])}{l.needsSalary && <span className="chip warn nodot" style={{ marginLeft: 6 }}>{t("States salary")}</span>}</td>
                  <td className="muted hide-sm">{fmtDY(isoDateIn(l.issuedAt, u.timeZone))} · {l.issuedBy}</td>
                  <td className="r">
                    {l.canOpen ? <a className="btn btn-sm" href={`/api/letters/${l.id}/pdf`} download><Icon n="download" s={14} />{t("PDF")}</a>
                      : <span className="faint" style={{ fontSize: 12 }}>{t("Salary letter")}</span>}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {!issued.length && <div className="empty"><b className="display">{t("No letters issued yet")}</b><span className="muted">{t("Choose a template and an employee above.")}</span></div>}
      </section>
    </div>
  );
}
