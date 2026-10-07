/** Old-regime investment declarations (REQUIREMENTS §5.10, prototype DECL). Client-safe. */
export interface DeclItem { k: string; label: string; hint?: string; auto?: boolean; cap?: number }
export interface DeclSection { sec: string; key: string; title: string; cap?: number; note: string; items: DeclItem[] }

export const DECL: DeclSection[] = [
  { sec: "HRA", key: "HRA", title: "House rent allowance", note: "Exemption = least of HRA received, rent − 10% of Basic, or 50% (metro) / 40% of Basic.", items: [{ k: "rent", label: "Annual rent paid", hint: "Rent receipts; landlord PAN if rent is above ₹1,00,000 a year" }] },
  { sec: "80C", key: "80C", title: "Section 80C", cap: 150000, note: "Combined limit ₹1,50,000. Your PF contribution is included automatically.", items: [
    { k: "epf", label: "Employee provident fund (auto)", auto: true }, { k: "ppf", label: "Public provident fund (PPF)" }, { k: "elss", label: "ELSS mutual funds" },
    { k: "lic", label: "Life insurance premium" }, { k: "ssy", label: "Sukanya Samriddhi Yojana" }, { k: "tuition", label: "Children’s tuition fees" },
    { k: "hlp", label: "Home loan principal" }, { k: "nsc", label: "NSC / 5-year tax-saving FD" }] },
  { sec: "80CCD(1B)", key: "80CCD1B", title: "National Pension System", cap: 50000, note: "Additional ₹50,000 over the 80C limit.", items: [{ k: "nps", label: "NPS contribution (Tier I)" }] },
  { sec: "80D", key: "80D_SELF", title: "Medical insurance", note: "Self & family up to ₹25,000. Parents up to ₹25,000, or ₹50,000 if senior citizens.", items: [{ k: "medSelf", label: "Self, spouse & children", cap: 25000 }, { k: "medParents", label: "Parents" }] },
  { sec: "24(b)", key: "24B", title: "Home loan interest", cap: 200000, note: "Self-occupied property, up to ₹2,00,000.", items: [{ k: "hli", label: "Interest on housing loan" }] },
  { sec: "80E", key: "80E", title: "Education loan interest", note: "No upper limit; for higher studies of self, spouse or children.", items: [{ k: "edu", label: "Interest paid this year" }] },
  { sec: "80G", key: "80G", title: "Donations", note: "Eligible amount as per the receipt (50% or 100% category).", items: [{ k: "don", label: "Eligible donation amount" }] },
  { sec: "80TTA", key: "80TTA", title: "Savings account interest", cap: 10000, note: "Up to ₹10,000 of savings bank interest.", items: [{ k: "sav", label: "Savings interest earned" }] },
];

export const ITEM_SECTION: Record<string, string> = Object.fromEntries(DECL.flatMap((s) => s.items.filter((i) => !i.auto).map((i) => [i.k, i.k === "medParents" ? "80D_PARENTS" : s.key])));
export const ITEM_LABEL: Record<string, string> = Object.fromEntries(DECL.flatMap((s) => s.items.map((i) => [i.k, i.label])));
export const SECTION_OF_ITEM: Record<string, string> = Object.fromEntries(DECL.flatMap((s) => s.items.map((i) => [i.k, s.sec])));
export const DECLARABLE = Object.keys(ITEM_SECTION);
