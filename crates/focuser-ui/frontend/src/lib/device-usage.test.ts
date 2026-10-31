import { describe, expect, it } from "vitest";
import { summariseDeviceUsage, usageKey } from "./device-usage";

describe("usageKey", () => {
  it("lines up the same service on every device", () => {
    expect(usageKey("com.instagram.android", "Instagram")).toBe("instagram");
    expect(usageKey("www.instagram.com")).toBe("instagram");
    expect(usageKey("Instagram.exe")).toBe("instagram");
  });
});

describe("summariseDeviceUsage", () => {
  it("adds a service up across devices, busiest first", () => {
    const row = (device: string, target: string, label: string, seconds: number) => ({
      device,
      date: "2026-10-30",
      target,
      label,
      seconds,
    });
    const out = summariseDeviceUsage([
      row("phone", "com.instagram.android", "Instagram", 600),
      row("laptop", "instagram.com", "", 300),
      row("laptop", "youtube.com", "", 1200),
    ]);
    expect(out.rows.map((r) => r.key)).toEqual(["youtube", "instagram"]);
    expect(out.rows[1].byDevice).toEqual({ phone: 600, laptop: 300 });
    expect(out.devices[0]).toEqual({ device: "laptop", seconds: 1500 });
  });
});
