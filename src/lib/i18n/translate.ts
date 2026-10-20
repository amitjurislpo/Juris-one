import { DEFAULT_LANG, type Lang } from "./config";
import { hi } from "./messages/hi";
import { hiApi } from "./messages/hi-api";

const DICTIONARIES: Record<Lang, Record<string, string>> = { en: {}, hi: { ...hiApi, ...hi } };

export type Vars = Record<string, string | number>;

/**
 * The English text is the key: a missing translation simply shows the English text, so strings can be wrapped
 * one page at a time. `{name}` placeholders are filled from `vars`.
 */
export function translate(lang: Lang, text: string, vars?: Vars): string {
  const out = (lang !== DEFAULT_LANG && DICTIONARIES[lang]?.[text]) || text;
  return vars ? out.replace(/\{(\w+)\}/g, (m, k: string) => (k in vars ? String(vars[k]) : m)) : out;
}

export type T = (text: string, vars?: Vars) => string;

export const translator = (lang: Lang): T => (text, vars) => translate(lang, text, vars);
