"use client";

import { useRouter } from "next/navigation";
import { startTransition } from "react";
import { setLanguageAction } from "@/lib/i18n/actions";
import { LANGUAGES } from "@/lib/i18n/config";
import { useLang, useT } from "@/lib/i18n/client";

/** Language picker: stores the choice in a cookie and re-renders the page in that language. */
export function LanguageSwitcher({ className = "" }: { className?: string }) {
  const lang = useLang();
  const t = useT();
  const router = useRouter();
  return (
    <select
      className={`lang-switch ${className}`.trim()}
      value={lang}
      aria-label={t("Language")}
      onChange={(e) => startTransition(() => { void setLanguageAction(e.target.value).then(() => router.refresh()); })}
    >
      {LANGUAGES.map((l) => <option key={l.code} value={l.code}>{l.label}</option>)}
    </select>
  );
}
