/** Password policy shown on the set-password form (the API enforces the same rule). */
export const PASSWORD_RULES = "At least 10 characters, with a letter and a number or symbol.";

export function passwordProblem(pw: string): string | null {
  if (pw.length < 10) return "Use at least 10 characters.";
  if (pw.length > 200) return "That password is too long.";
  if (!/[A-Za-z]/.test(pw) || !/[^A-Za-z]/.test(pw)) return "Include at least one letter and one number or symbol.";
  return null;
}
