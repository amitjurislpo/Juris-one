import type { Metadata } from "next";
import Link from "next/link";
import { redirect } from "next/navigation";
import { Icon } from "@/components/Icon";
import { landingPath, requireUser } from "@/lib/auth";
import { getT } from "@/lib/i18n/server";
import { BUSINESS_MANAGE } from "@/lib/nav";
import { ExportButtons } from "../subscription/ExportButtons";

export const metadata: Metadata = { title: "Subscription ended" };

/** Shown instead of every page while the business has no active subscription (sign-in still works). */
export default async function PausedPage() {
  const u = await requireUser();
  const t = await getT();
  if (u.subscriptionActive !== false) redirect(landingPath(u));
  const owner = (u.perms as ReadonlySet<string>).has(BUSINESS_MANAGE);

  return (
    <div className="col" style={{ alignItems: "center", paddingTop: 48 }}>
      <section className="card enter" style={{ maxWidth: 560, width: "100%", padding: 28, textAlign: "center" }}>
        <span className="kpi-ico" style={{ width: 48, height: 48, margin: "0 auto 14px", background: "var(--warnbg)", color: "var(--warn)", display: "grid", placeItems: "center", borderRadius: 12 }}>
          <Icon n="lock" s={22} />
        </span>
        <h1 className="display" style={{ fontSize: 30, margin: "0 0 8px" }}>{t("JurisOne is paused")}</h1>
        {owner ? (
          <>
            <p className="muted">{t("Your business's subscription isn't active, so JurisOne is paused for everyone in it. Your data is safe. Contact the JurisOne team to renew; until then you can still export your employees.")}</p>
            <div style={{ display: "flex", gap: 8, justifyContent: "center", flexWrap: "wrap", marginTop: 16 }}>
              <ExportButtons />
              <Link className="btn btn-ghost" href="/subscription">{t("View subscription")}</Link>
            </div>
          </>
        ) : (
          <p className="muted">{t("Your organisation's JurisOne subscription isn't active right now. Please contact your administrator. You'll have access again as soon as it's renewed.")}</p>
        )}
      </section>
    </div>
  );
}
