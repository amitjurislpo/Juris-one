import { readdirSync, readFileSync, statSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";
import { NAV } from "@/lib/nav";
import { isLang, LANGUAGES } from "@/lib/i18n/config";
import { hi } from "@/lib/i18n/messages/hi";
import { translate, translator } from "@/lib/i18n/translate";

function sources(dir: string): string[] {
  return readdirSync(dir).flatMap((f) => {
    const p = path.join(dir, f);
    return statSync(p).isDirectory() ? sources(p) : /\.tsx?$/.test(f) ? [p] : [];
  });
}

describe("translate", () => {
  it("returns the English text for English and for unknown keys", () => {
    expect(translate("en", "Home")).toBe("Home");
    expect(translate("hi", "A sentence nobody translated")).toBe("A sentence nobody translated");
  });

  it("translates to Hindi", () => {
    expect(translate("hi", "Home")).toBe(hi["Home"]);
    expect(translate("hi", "Sign out")).toBe("साइन आउट");
  });

  it("fills {placeholders} in both languages and leaves unknown ones alone", () => {
    expect(translate("en", "Welcome, {name}", { name: "Amit" })).toBe("Welcome, Amit");
    expect(translate("hi", "Welcome, {name}", { name: "Amit" })).toContain("Amit");
    expect(translate("en", "Hi {who}", { other: 1 })).toBe("Hi {who}");
    expect(translate("en", "{n} items", { n: 3 })).toBe("3 items");
  });

  it("translator() binds a language", () => {
    expect(translator("hi")("Search")).toBe(hi["Search"]);
  });
});

describe("languages", () => {
  it("recognises only the supported codes", () => {
    for (const l of LANGUAGES) expect(isLang(l.code)).toBe(true);
    expect(isLang("fr")).toBe(false);
    expect(isLang(undefined)).toBe(false);
  });
});

describe("Hindi dictionary", () => {
  it("has no empty translations and keeps every {placeholder} of its key", () => {
    for (const [k, v] of Object.entries(hi)) {
      expect(v.trim(), k).not.toBe("");
      const want = (k.match(/\{\w+\}/g) ?? []).sort();
      const got = (v.match(/\{\w+\}/g) ?? []).sort();
      expect(got, k).toEqual(want);
    }
  });

  it("translates every sidebar label", () => {
    for (const n of NAV) expect(hi[n.label], `nav label "${n.label}"`).toBeTruthy();
  });

  it("translates every string passed to t(\"…\") in the source", () => {
    const missing = new Set<string>();
    for (const file of sources(path.resolve(import.meta.dirname, "../../src"))) {
      const text = readFileSync(file, "utf8");
      for (const m of text.matchAll(/\bt\("((?:[^"\\]|\\.)*)"/g)) {
        const key = m[1].replaceAll("\\'", "'");
        if (!hi[key]) missing.add(`${path.basename(file)}: ${key}`);
      }
    }
    expect([...missing]).toEqual([]);
  });
});
