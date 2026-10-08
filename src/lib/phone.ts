/** Country codes for phone numbers and how many digits the national number has (same list as backend Phones.java). */
export const PHONE_COUNTRIES = [
  { code: "+91", name: "India", digits: 10, first: "6789" },
  { code: "+1", name: "United States / Canada", digits: 10, first: "23456789" },
  { code: "+44", name: "United Kingdom", digits: 10, first: "123789" },
  { code: "+971", name: "United Arab Emirates", digits: 9, first: "5" },
  { code: "+966", name: "Saudi Arabia", digits: 9, first: "5" },
  { code: "+974", name: "Qatar", digits: 8, first: "3567" },
  { code: "+65", name: "Singapore", digits: 8, first: "689" },
  { code: "+61", name: "Australia", digits: 9, first: "45" },
  { code: "+977", name: "Nepal", digits: 10, first: "9" },
  { code: "+880", name: "Bangladesh", digits: 10, first: "1" },
  { code: "+94", name: "Sri Lanka", digits: 9, first: "7" },
  { code: "+27", name: "South Africa", digits: 9, first: "678" },
  { code: "+353", name: "Ireland", digits: 9, first: "8" },
  { code: "+33", name: "France", digits: 9, first: "67" },
  { code: "+81", name: "Japan", digits: 10, first: "789" },
] as const;

export const phoneCountry = (code: string) => PHONE_COUNTRIES.find((c) => c.code === code);

/** Digits allowed for this country's national number (the input stops there). */
export const phoneDigits = (code: string) => phoneCountry(code)?.digits ?? 15;

/** Problem with the number for this country, or null when valid. */
export function phoneProblem(code: string, number: string): string | null {
  const c = phoneCountry(code);
  if (!c) return "Choose the country code";
  const d = number.replace(/\D/g, "");
  if (d.length !== c.digits) return `${c.name} numbers have ${c.digits} digits`;
  if (c.first && !c.first.includes(d[0])) return `That isn't a valid ${c.name} mobile number`;
  return null;
}
