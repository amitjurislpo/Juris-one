/** Languages the UI is available in. To add one: add it here and create `messages/<code>.ts` (English text -> translation). */
export const LANGUAGES = [
  { code: "en", label: "English", short: "EN" },
  { code: "hi", label: "हिन्दी", short: "हि" },
] as const;

export type Lang = (typeof LANGUAGES)[number]["code"];
export const DEFAULT_LANG: Lang = "en";
export const LANG_COOKIE = "jo_lang";

export const isLang = (v: unknown): v is Lang => LANGUAGES.some((l) => l.code === v);
