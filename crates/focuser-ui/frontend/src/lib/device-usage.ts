import type { DeviceUsage } from "@/bindings";

/**
 * What an app or site is called across devices: `com.instagram.android` with the label "Instagram",
 * `www.instagram.com` and `instagram.exe` all become "instagram". ponytail: name-based; add an alias
 * table here if two targets of one service do not line up.
 */
export function usageKey(target: string, label = ""): string {
  const name = label.trim();
  if (name) return name.toLowerCase();
  const host = target.toLowerCase().replace(/^www\./, "");
  if (host.endsWith(".exe")) return host.slice(0, -4);
  const labels = host.split(".");
  return labels.length > 1 ? labels[labels.length - 2] : host;
}

export interface UsageRow {
  key: string;
  /** Seconds per device name. */
  byDevice: Record<string, number>;
  total: number;
}

export interface UsageSummary {
  devices: { device: string; seconds: number }[];
  rows: UsageRow[];
}

/** One row per app or site, one column per device, busiest first. */
export function summariseDeviceUsage(usage: DeviceUsage[]): UsageSummary {
  const rows = new Map<string, UsageRow>();
  const devices = new Map<string, number>();
  for (const u of usage) {
    const key = usageKey(u.target, u.label);
    const row = rows.get(key) ?? { key, byDevice: {}, total: 0 };
    row.byDevice[u.device] = (row.byDevice[u.device] ?? 0) + u.seconds;
    row.total += u.seconds;
    rows.set(key, row);
    devices.set(u.device, (devices.get(u.device) ?? 0) + u.seconds);
  }
  return {
    devices: [...devices]
      .map(([device, seconds]) => ({ device, seconds }))
      .sort((a, b) => b.seconds - a.seconds),
    rows: [...rows.values()].sort((a, b) => b.total - a.total),
  };
}
