import type { Metadata } from "next";
import { Icon } from "@/components/Icon";
import { PageHeader } from "@/components/PageHeader";
import type { AssetAssignment } from "@/lib/api-types";
import { requirePage } from "@/lib/auth";
import { ASSET_TYPES, CONDITIONS } from "@/lib/assets";
import { apiData } from "@/lib/backend";
import { fmtDY } from "@/lib/format";

export const metadata: Metadata = { title: "My assets" };

const describe = (a: AssetAssignment) => [a.make, a.model].filter(Boolean).join(" ") || ASSET_TYPES[a.type] || a.type;

/** The company equipment assigned to the signed-in user, now and before. */
export default async function MyAssetsPage() {
  await requirePage("my-assets");
  const { current, past } = await apiData<{ current: AssetAssignment[]; past: AssetAssignment[] }>("/api/assets/mine");

  return (
    <div className="col">
      <PageHeader eyebrow="Company" title={<>My <em>assets</em></>} sub="Laptops, phones and other equipment the company has given you. Please return them to HR when you leave or swap them." />
      <section className="card enter" style={{ ["--i" as string]: 1 }}>
        <div className="card-h" style={{ padding: "16px 18px 0" }}><h3>Assigned to me</h3></div>
        <div className="table-wrap">
          <table className="t">
            <thead><tr><th>Asset</th><th>Tag</th><th className="hide-sm">Serial</th><th>Since</th><th className="hide-sm">Notes</th></tr></thead>
            <tbody>
              {current.map((a) => (
                <tr key={a.id}>
                  <td><div className="person"><span className="kpi-ico" style={{ width: 34, height: 34, background: "var(--accentsoft)", color: "var(--accent)" }}><Icon n="layers" s={16} /></span><div><b>{describe(a)}</b><small>{ASSET_TYPES[a.type] ?? a.type}</small></div></div></td>
                  <td className="num">{a.tag}</td>
                  <td className="muted hide-sm">{a.serial ?? "—"}</td>
                  <td>{fmtDY(a.assignedOn)}</td>
                  <td className="muted hide-sm">{a.assignNotes ?? "—"}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {!current.length && <div className="empty"><b className="display">Nothing assigned</b><span className="muted">When HR gives you a laptop, phone or other equipment it will show up here.</span></div>}
      </section>
      {past.length > 0 && (
        <section className="card enter" style={{ ["--i" as string]: 2 }}>
          <div className="card-h" style={{ padding: "16px 18px 0" }}><h3>Returned</h3></div>
          <div className="table-wrap">
            <table className="t">
              <thead><tr><th>Asset</th><th>Tag</th><th>From</th><th>Returned</th><th>Condition</th></tr></thead>
              <tbody>
                {past.map((a) => (
                  <tr key={a.id}>
                    <td><b>{describe(a)}</b></td>
                    <td className="num">{a.tag}</td>
                    <td>{fmtDY(a.assignedOn)}</td>
                    <td>{a.returnedOn ? fmtDY(a.returnedOn) : "—"}</td>
                    <td>{a.returnCondition ? CONDITIONS[a.returnCondition] : "—"}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      )}
    </div>
  );
}
