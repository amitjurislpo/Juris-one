import type { Metadata } from "next";
import { apiCall } from "@/lib/backend";
import type { CandidateForm } from "../actions";
import { CandidateClient, type CandidateView } from "./CandidateClient";

export const metadata: Metadata = { title: "Your onboarding", robots: { index: false, follow: false } };

/** The candidate's onboarding form, reached through the one-time link HR emailed to their personal address. */
export default async function OnboardPage(props: PageProps<"/onboard/[token]">) {
  const { token } = await props.params;
  const r = await apiCall<CandidateView & { form: CandidateForm }>("GET", `/api/onboarding/${encodeURIComponent(token)}`);
  return (
    <main style={{ minHeight: "100vh", background: "var(--bg)", padding: "28px 16px 64px" }}>
      <div style={{ maxWidth: 880, margin: "0 auto" }}>
        <div className="brandmark" style={{ marginBottom: 22 }}><span className="monogram">J</span>JurisOne</div>
        {r.body.ok ? <CandidateClient token={token} v={r.body.data!} /> : (
          <section className="card" style={{ padding: 28, textAlign: "center" }}>
            <h1 className="display" style={{ fontSize: 30, margin: "0 0 8px" }}>This link can&apos;t be used</h1>
            <p className="muted">{r.body.error}</p>
          </section>
        )}
      </div>
    </main>
  );
}
