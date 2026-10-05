"use client";

export type Theme = "light" | "dark";

export const currentTheme = (): Theme => (document.documentElement.dataset.theme === "dark" ? "dark" : "light");

/** Applies the theme and remembers it in a cookie so the server renders it without a flash. */
export function setTheme(t: Theme) {
  document.documentElement.dataset.theme = t;
  document.cookie = `jo_theme=${t}; path=/; max-age=${60 * 60 * 24 * 365}; samesite=lax`;
}
