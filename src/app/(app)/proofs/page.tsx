import type { Metadata } from "next";
import { PageHeader } from "@/components/PageHeader";
import { ClearFilters, Pending, SearchBox, SegFilter, SortTh } from "@/components/table/Table";
import { requirePage } from "@/lib/auth";
import { db } from "@/lib/db";
import { avc, inr, initials } from "@/lib/format";
import { listState, pick } from "@/lib/list";
import { getSetting } from "@/lib/settings";
import { ReviewButton } from "./ProofsClient";

export const metadata: Metadata = { title: "Investment proofs" };

const SORTS = ["name", "reg", "dec", "pend"] as const;

export default async function ProofsPage(props: PageProps<"/proofs">) {
  await requirePage("proofs");
  const sp = await props.searchParams;
  const ls = listState(sp, SORTS, "pend", 100, "desc");
  const flt = pick(sp, "st", ["All", "Pending", "Verified"] as const, "All");
  const { fy } = await getSetting("tax.policy");

  const emps = await db.employee.findMany({
    where: { declarationItems: { some: { fy, OR: [{ amount: { gt: 0 } }, { proofPath: { not: null } }] } } },
    include: { declarationItems: { where: { fy } }, taxProfiles: { where: { fy } }, department: { select: { name: true } } },
  });
  const q = ls.q.toLowerCase();
  const all = emps.map((e) => {
    const items = e.declarationItems;
    return {
      id: e.id, name: e.fullName, code: e.code, dept: e.department?.name ?? "—", reg: e.taxProfiles[0]?.regime ?? "NEW",
      dec: items.reduce((a, i) => a + Number(i.amount), 0), pend: items.filter((i) => i.status === "PENDING").length, appr: items.filter((i) => i.status === "APPROVED").length,
    };
  });
  const rows = all.filter((r) => (!q || `${r.name} ${r.code} ${r.dept}`.toLowerCase().includes(q)) && (flt === "All" || (flt === "Pending" ? r.pend > 0 : r.pend === 0 && r.appr > 0)));
  const get = { name: (r: (typeof rows)[number]) => r.name, reg: (r: (typeof rows)[number]) => r.reg, dec: (r: (typeof rows)[number]) => r.dec, pend: (r: (typeof rows)[number]) => r.pend }[ls.sort];
  rows.sort((a, b) => { const x = get(a), y = get(b); return (x > y ? 1 : x < y ? -1 : 0) * (ls.dir === "asc" ? 1 : -1); });

  return (
    <div className="col">
      <PageHeader eyebrow="Finance · Old-regime declarations" title="Investment" em="proofs" sub="Verify what employees declared. Approved amounts reduce their TDS from the next payroll." />
      <div className="toolbar enter" style={{ ["--i" as string]: 1 }}>
        <SearchBox placeholder="Search name, ID or department" label="Search proofs" />
        <SegFilter name="st" dflt="All" label="Status" options={[{ v: "All", l: "All" }, { v: "Pending", l: "To verify" }, { v: "Verified", l: "Verified" }]} />
        <span className="tcount">{rows.length} of {all.length}</span>
      </div>
      <section className="card enter" style={{ ["--i" as string]: 1 }}>
        <Pending>
          <div className="table-wrap">
            <table className="t">
              <thead>
                <tr>
                  <SortTh k="name" dflt="pend" dfltDir="desc">Employee</SortTh>
                  <SortTh k="reg" dflt="pend" dfltDir="desc">Regime</SortTh>
                  <SortTh k="dec" dflt="pend" dfltDir="desc" right>Declared</SortTh>
                  <SortTh k="pend" dflt="pend" dfltDir="desc">Status</SortTh>
                  <th><span className="sr-only">Actions</span></th>
                </tr>
              </thead>
              <tbody>
                {rows.map((r, i) => (
                  <tr key={r.id} style={{ animationDelay: `${i * 40}ms` }}>
                    <td><div className="person"><span className={`avatar ${avc(initials(r.name))}`}>{initials(r.name)}</span><div><b>{r.name}</b><small>{r.code} · {r.dept}</small></div></div></td>
                    <td><span className={`chip nodot ${r.reg === "OLD" ? "info" : "gold"}`}>{r.reg === "OLD" ? "Old" : "New"}</span></td>
                    <td className="r num">{inr(r.dec)}</td>
                    <td>{r.pend ? <span className="chip warn">{r.pend} to verify</span> : r.appr ? <span className="chip ok">Verified</span> : <span className="chip neutral">Nothing submitted</span>}</td>
                    <td className="r"><ReviewButton employeeId={r.id} name={r.name} gold={r.pend > 0} /></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {!all.length && <div className="empty"><b className="display">No declarations yet</b><span className="muted">Employees on the old regime will appear here.</span></div>}
          {all.length > 0 && !rows.length && <div className="empty"><b>No one matches</b><ClearFilters /></div>}
        </Pending>
      </section>
    </div>
  );
}
