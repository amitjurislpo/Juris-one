import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { Shell } from "@/components/shell/Shell";
import { requireUser } from "@/lib/auth";
import { apiGet } from "@/lib/backend";
import type { ShellNote } from "@/lib/shell-data";
import type { NavGroup } from "@/lib/nav";

interface ShellData {
  me: { code: string; name: string; designation: string; roleName: string; department: string; manager: string; doj: string; shift: string | null; isAdminLike: boolean };
  groups: { g: NavGroup; items: { id: string; label: string; icon: string }[] }[];
  badges: { approvals: number; proofs: number };
  notes: ShellNote[];
  unread: number;
  landing: string;
  canSelf: boolean;
}

export default async function AppLayout({ children }: { children: React.ReactNode }) {
  await requireUser();
  const [data, jar] = await Promise.all([apiGet<ShellData>("/api/shell"), cookies()]);
  if (!data) redirect("/login");
  return (
    <Shell
      me={data.me}
      groups={data.groups}
      badges={data.badges}
      notes={data.notes}
      unread={data.unread}
      landing={data.landing}
      canSelf={data.canSelf}
      initialTheme={jar.get("jo_theme")?.value === "dark" ? "dark" : "light"}
    >
      {children}
    </Shell>
  );
}
