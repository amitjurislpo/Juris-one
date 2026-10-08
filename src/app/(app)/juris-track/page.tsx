import type { Metadata } from "next";
import { Icon } from "@/components/Icon";
import { PageHeader } from "@/components/PageHeader";
import { requirePage } from "@/lib/auth";
import { apiData } from "@/lib/backend";

export const metadata: Metadata = { title: "Juris Track" };

/** Juris Track — only for businesses with the module (plan or grant); everyone else gets a 404. */
export default async function JurisTrackPage() {
  await requirePage("juris-track");
  await apiData<{ module: string; status: string }>("/api/juris-track");

  return (
    <div className="col">
      <PageHeader eyebrow="Juris Track" title={<>Juris <em>Track</em></>} sub="Included in your JurisOne subscription." />
      <section className="card enter" style={{ ["--i" as string]: 1, padding: "48px 28px", textAlign: "center" }}>
        <span style={{ width: 56, height: 56, margin: "0 auto 16px", background: "var(--accentsoft)", color: "var(--accent)", display: "grid", placeItems: "center", borderRadius: 14 }}>
          <Icon n="sparkle" s={26} />
        </span>
        <span className="chip gold nodot" style={{ marginBottom: 12 }}>Coming soon</span>
        <h2 className="display" style={{ fontSize: 34, margin: "0 0 10px" }}>Something new is on its way</h2>
        <p className="muted" style={{ maxWidth: 520, margin: "0 auto" }}>
          Juris Track is being built and will appear right here. Your business already has access, so there&apos;s nothing to set up: you&apos;ll be able to use it as soon as it launches.
        </p>
      </section>
    </div>
  );
}
