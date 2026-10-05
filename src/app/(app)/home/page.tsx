import type { Metadata } from "next";
import Link from "next/link";
import { Greeting } from "@/components/home/Greeting";
import { Icon } from "@/components/Icon";
import { ComingInPhase } from "@/components/PageHeader";
import { requirePage } from "@/lib/auth";

export const metadata: Metadata = { title: "Home" };

export default async function HomePage() {
  const u = await requirePage("home");
  return (
    <div className="col">
      <div className="page-h enter">
        <Greeting first={u.name.split(" ")[0]} timeZone={u.timeZone} initialIso={new Date().toISOString()} />
        <div style={{ display: "flex", gap: 8, flexWrap: "wrap" }}>
          <Link className="btn" href="/attendance"><Icon n="clock" s={15} />My attendance</Link>
          <Link className="btn btn-gold" href="/leave?apply=1"><Icon n="plus" s={15} />Apply leave</Link>
        </div>
      </div>
      <ComingInPhase phase={2} what="Check-in card, attendance KPIs, heatmap and upcoming holidays. Full dashboard cards arrive in phase 6." />
    </div>
  );
}
