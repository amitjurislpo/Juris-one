"use client";

import { createContext, useContext, useMemo } from "react";
import { DEFAULT_LANG, type Lang } from "./config";
import { translator, type T } from "./translate";

const LangContext = createContext<Lang>(DEFAULT_LANG);

export function I18nProvider({ lang, children }: { lang: Lang; children: React.ReactNode }) {
  return <LangContext.Provider value={lang}>{children}</LangContext.Provider>;
}

export const useLang = () => useContext(LangContext);

/** Translator for client components: `const t = useT(); t("Sign out")`. */
export function useT(): T {
  const lang = useContext(LangContext);
  return useMemo(() => translator(lang), [lang]);
}
