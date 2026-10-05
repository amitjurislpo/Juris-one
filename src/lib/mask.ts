/** Masked display formats. Only the last four characters are ever stored in clear. */
export const maskAadhaar = (l4?: string | null) => (l4 ? `XXXX XXXX ${l4}` : "—");
export const maskPan = (l4?: string | null) => (l4 ? `XXXXXX${l4}` : "—");
export const maskUan = (l4?: string | null) => (l4 ? `XXXX XXXX ${l4}` : "—");
export const maskPhone = (l4?: string | null) => (l4 ? `•••• •• ${l4}` : "—");
export const maskBank = (l4?: string | null) => (l4 ? `XXXX XXXX ${l4}` : "—");
