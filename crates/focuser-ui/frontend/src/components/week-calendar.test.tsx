import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it } from "vitest";
import type { BlockList } from "@/bindings";
import { WeekCalendar } from "./week-calendar";

const hours = (enabled: boolean, slots: { day: string; start: string; end: string }[] = []) => ({
  id: "s",
  name: "s",
  enabled,
  time_slots: slots,
});

describe("the week calendar", () => {
  it("shows each device's start times, always-on devices and phone places", () => {
    const list = {
      id: "l1",
      name: "Focus",
      enabled: true,
      device_schedules: {
        laptop: hours(true, [{ day: "Mon", start: "09:00:00", end: "17:00:00" }]),
        phone: hours(false),
      },
      locations: [{ name: "Office", lat: 1, lon: 2, radius_m: 100, mode: "Block" }],
    } as unknown as BlockList;
    render(
      <MemoryRouter>
        <WeekCalendar lists={[list]} />
      </MemoryRouter>,
    );
    expect(screen.getByText("9am–5pm")).toBeVisible();
    expect(screen.getByText("Focus · laptop")).toBeVisible();
    expect(screen.getByText("Focus · phone")).toBeVisible();
    expect(screen.getByText(/Focus · Office · Block here/)).toBeVisible();
  });
});
