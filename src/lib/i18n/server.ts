import "server-only";
import { cookies } from "next/headers";
import { DEFAULT_LANG, LANG_COOKIE, isLang, type Lang } from "./config";
import { translator, type T } from "./translate";

export async function getLang(): Promise<Lang> {
  const v = (await cookies()).get(LANG_COOKIE)?.value;
  return isLang(v) ? v : DEFAULT_LANG;
}

/** Translator for server components and pages: `const t = await getT(); t("Home")`. */
export async function getT(): Promise<T> {
  return translator(await getLang());
}
