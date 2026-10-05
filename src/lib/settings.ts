import "server-only";
import { db } from "./db";
import { DEFAULT_SETTINGS } from "./settings-defaults";

export { DEFAULT_SETTINGS };

export type SettingKey = keyof typeof DEFAULT_SETTINGS;
type Widen<T> = T extends readonly (infer U)[] ? Widen<U>[] : T extends object ? { -readonly [K in keyof T]: Widen<T[K]> } : T extends string ? string : T extends number ? number : T;
export type SettingValue<K extends SettingKey> = Widen<(typeof DEFAULT_SETTINGS)[K]>;

export async function getSetting<K extends SettingKey>(key: K): Promise<SettingValue<K>> {
  const row = await db.setting.findUnique({ where: { key } });
  const base = DEFAULT_SETTINGS[key] as object;
  return (row ? { ...base, ...(row.value as object) } : base) as SettingValue<K>;
}
