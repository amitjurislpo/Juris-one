"use client";

import { useRef, useState } from "react";
import { useT } from "@/lib/i18n/client";

const LENGTH = 6;

/**
 * Six one-digit boxes for the emailed sign-in code. Typing moves to the next box, Backspace to the previous one, and
 * pasting a code fills them all. The joined value is posted as the field `name`; `onComplete` fires when all six are
 * filled (the form submits itself).
 */
export function OtpInput({ name = "otp", invalid, disabled, onComplete }: { name?: string; invalid?: boolean; disabled?: boolean; onComplete?: (code: string) => void }) {
  const t = useT();
  const [digits, setDigits] = useState<string[]>(() => Array(LENGTH).fill(""));
  const refs = useRef<(HTMLInputElement | null)[]>([]);

  const commit = (next: string[]) => {
    setDigits(next);
    const code = next.join("");
    if (code.length === LENGTH && next.every(Boolean)) onComplete?.(code);
  };

  const onChange = (i: number, raw: string) => {
    const typed = raw.replace(/\D/g, "");
    if (!typed) {
      commit(digits.map((d, j) => (j === i ? "" : d)));
      return;
    }
    // several digits at once (paste, or autofill of the whole code into one box)
    const next = [...digits];
    typed.split("").slice(0, LENGTH - i).forEach((d, k) => { next[i + k] = d; });
    commit(next);
    refs.current[Math.min(i + typed.length, LENGTH - 1)]?.focus();
  };

  const onKeyDown = (i: number, e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === "Backspace" && !digits[i] && i > 0) {
      e.preventDefault();
      commit(digits.map((d, j) => (j === i - 1 ? "" : d)));
      refs.current[i - 1]?.focus();
    } else if (e.key === "ArrowLeft" && i > 0) {
      e.preventDefault();
      refs.current[i - 1]?.focus();
    } else if (e.key === "ArrowRight" && i < LENGTH - 1) {
      e.preventDefault();
      refs.current[i + 1]?.focus();
    }
  };

  return (
    <div className={`otp${invalid ? " bad" : ""}`} role="group" aria-label={t("Sign-in code")}>
      {digits.map((d, i) => (
        <input
          key={i}
          ref={(el) => { refs.current[i] = el; }}
          className={`otp-box${d ? " filled" : ""}`}
          value={d}
          onChange={(e) => onChange(i, e.target.value)}
          onKeyDown={(e) => onKeyDown(i, e)}
          onFocus={(e) => e.target.select()}
          inputMode="numeric"
          pattern="[0-9]*"
          autoComplete={i === 0 ? "one-time-code" : "off"}
          maxLength={LENGTH}
          aria-label={t("Digit {n} of 6", { n: i + 1 })}
          aria-invalid={invalid || undefined}
          disabled={disabled}
          autoFocus={i === 0}
        />
      ))}
      <input type="hidden" name={name} value={digits.join("")} />
    </div>
  );
}
