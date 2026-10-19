/** Labels for the asset register and announcements (plain English; shared by server and client). */
export const ASSET_TYPES: Record<string, string> = {
  LAPTOP: "Laptop", DESKTOP: "Desktop", PHONE: "Phone", TABLET: "Tablet", MONITOR: "Monitor", ACCESSORY: "Accessory", OTHER: "Other",
};
export const ASSET_STATUS: Record<string, { cls: string; text: string }> = {
  AVAILABLE: { cls: "ok", text: "Available" }, ASSIGNED: { cls: "info", text: "Assigned" }, IN_REPAIR: { cls: "warn", text: "In repair" }, RETIRED: { cls: "neutral", text: "Retired" },
};
export const CONDITIONS: Record<string, string> = { GOOD: "Good", FAIR: "Fair", DAMAGED: "Damaged (send to repair)", LOST: "Lost (retire)" };
export const AUDIENCES: Record<string, string> = { ALL: "Everyone", IN: "India", US: "US" };
