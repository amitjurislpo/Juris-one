import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { Shell } from "@/components/shell/Shell";
import Link from "next/link";
import { requireUser } from "@/lib/auth";
import { apiGet } from "@/lib/backend";
import type { ShellNote, SubscriptionCurrent } from "@/lib/api-types";
import { fmtDY } from "@/lib/format";
import { getT } from "@/lib/i18n/server";
import { BUSINESS_MANAGE, type NavGroup } from "@/lib/nav";

interface ShellData {
  me: { code: string; name: string; designation: string; roleName: string; department: string; manager: string; doj: string; shift: string | null; isAdminLike: boolean };
  groups: { g: NavGroup; items: { id: string; label: string; icon: string }[] }[];
  badges: { approvals: number; proofs: number };
  notes: ShellNote[];
  unread: number;
  landing: string;
  canSelf: boolean;
  businessName: string | null;
  subscription: SubscriptionCurrent | null;
}

export default async function AppLayout({ children }: { children: React.ReactNode }) {
  const u = await requireUser();
  const t = await getT();
  const [data, jar] = await Promise.all([apiGet<ShellData>("/api/shell"), cookies()]);
  if (!data) redirect("/login");
  // the owner is warned on every page during the last week of the subscription
  const s = data.subscription;
  const ending = s?.active && s.daysLeft !== null && s.daysLeft <= 7 && (u.perms as ReadonlySet<string>).has(BUSINESS_MANAGE);
  return (
    <Shell
      me={data.me}
      groups={data.groups}
      badges={data.badges}
      notes={data.notes}
      unread={data.unread}
      landing={data.landing}
      canSelf={data.canSelf}
      businessName={data.businessName}
      initialTheme={jar.get("jo_theme")?.value === "dark" ? "dark" : "light"}
    >
      {ending && (
        <div className="card" role="status" style={{ display: "flex", alignItems: "center", gap: 12, padding: "12px 16px", marginBottom: 20, background: "var(--warnbg)", borderColor: "transparent", color: "var(--warn)" }}>
          <b style={{ flex: 1 }}>
            {s.daysLeft === 0 ? t("Your JurisOne subscription ends today") : s.daysLeft === 1 ? t("Your JurisOne subscription ends in 1 day") : t("Your JurisOne subscription ends in {n} days", { n: s.daysLeft ?? 0 })} ({fmtDY(s.endsOn!)}).
            {" "}{t("After that, everyone can still sign in but JurisOne is paused. Contact the JurisOne team to renew.")}
          </b>
          <Link className="btn btn-sm" href="/subscription">{t("Subscription")}</Link>
        </div>
      )}
      {children}
    </Shell>
  );
}
