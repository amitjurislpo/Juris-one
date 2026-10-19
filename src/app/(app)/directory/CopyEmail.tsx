"use client";

import { useToast } from "@/components/shell/Toasts";
import { useT } from "@/lib/i18n/client";

export function CopyEmail({ email }: { email: string }) {
  const toast = useToast();
  const t = useT();
  return (
    <button className="linkish" title={t("Copy {email}", { email })} onClick={() => { navigator.clipboard?.writeText(email).catch(() => {}); toast(t("Copied {email}", { email })); }}>
      {email}
    </button>
  );
}
