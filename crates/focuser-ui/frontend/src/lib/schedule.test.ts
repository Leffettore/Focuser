import { describe, expect, it } from "vitest";
import {
  type CellKey,
  cellKey,
  cellsToSlots,
  DAYS,
  type Day,
  describeDay,
  hasEndingHours,
  hoursOn,
  slotsToCells,
  summarizeWeek,
  toggleDay,
  toggleHour,
} from "./schedule";

const cells = (...keys: CellKey[]) => new Set(keys);
// A whole hour: the grid works in quarters.
const hr = (day: Day, hour: number): CellKey[] =>
  [0, 1, 2, 3].map((q) => cellKey(day, hour * 4 + q));

const wholeWeek = () => {
  const all = new Set<CellKey>();
  for (const day of DAYS) for (let q = 0; q < 96; q++) all.add(cellKey(day, q));
  return all;
};

describe("cellsToSlots", () => {
  it("merges contiguous hours into one slot", () => {
    const slots = cellsToSlots(cells(...hr("Mon", 9), ...hr("Mon", 10), ...hr("Mon", 11)));

    expect(slots).toEqual([{ day: "Mon", start: "09:00:00", end: "12:00:00" }]);
  });

  it("splits non-contiguous hours", () => {
    const slots = cellsToSlots(cells(...hr("Mon", 9), ...hr("Mon", 10), ...hr("Mon", 14)));

    expect(slots).toEqual([
      { day: "Mon", start: "09:00:00", end: "11:00:00" },
      { day: "Mon", start: "14:00:00", end: "15:00:00" },
    ]);
  });

  it("ends the last hour of the day at midnight", () => {
    expect(cellsToSlots(cells(...hr("Sun", 23)))).toEqual([
      { day: "Sun", start: "23:00:00", end: "00:00:00" },
    ]);
  });

  it("keeps days separate", () => {
    const slots = cellsToSlots(cells(...hr("Mon", 9), ...hr("Tue", 9)));

    expect(slots).toHaveLength(2);
    expect(slots.map((s) => s.day)).toEqual(["Mon", "Tue"]);
  });
});

describe("slotsToCells", () => {
  it("expands a slot into one cell per hour", () => {
    const set = slotsToCells([{ day: "Mon", start: "09:00:00", end: "12:00:00" }]);

    expect(set).toEqual(new Set([...hr("Mon", 9), ...hr("Mon", 10), ...hr("Mon", 11)]));
  });

  it("reads a midnight end as the end of the day", () => {
    const set = slotsToCells([{ day: "Sun", start: "22:00:00", end: "00:00:00" }]);

    expect([...set].sort()).toEqual([...hr("Sun", 22), ...hr("Sun", 23)]);
  });

  // A whole day is stored as 00:00–00:00. Reading the end as the *start* of
  // the day made it expand to nothing, so a day filled in one click came back
  // empty after a reload.
  it("expands a midnight-to-midnight slot into the whole day", () => {
    const set = slotsToCells([{ day: "Mon", start: "00:00:00", end: "00:00:00" }]);

    expect(set.size).toBe(96);
    expect(describeDay(set, "Mon")).toBe("All day");
  });

  it("ignores days it does not recognise", () => {
    expect(slotsToCells([{ day: "Funday", start: "09:00:00", end: "10:00:00" }]).size).toBe(0);
  });
});

describe("describeDay", () => {
  it("reads one run as a range", () => {
    expect(describeDay(cells(...hr("Mon", 9), ...hr("Mon", 10), ...hr("Mon", 11)), "Mon")).toBe(
      "9am–12pm",
    );
  });

  it("lists each run when the day is split", () => {
    expect(
      describeDay(
        cells(...hr("Mon", 9), ...hr("Mon", 10), ...hr("Mon", 14), ...hr("Mon", 15)),
        "Mon",
      ),
    ).toBe("9am–11am, 2pm–4pm");
  });

  it("names a full day rather than spelling out midnight to midnight", () => {
    expect(describeDay(wholeWeek(), "Wed")).toBe("All day");
  });

  it("ends the last hour of the day at midnight, not 12pm", () => {
    expect(describeDay(cells(...hr("Sun", 22), ...hr("Sun", 23)), "Sun")).toBe("10pm–12am");
  });

  it("says so when nothing is selected", () => {
    expect(describeDay(cells(), "Fri")).toBe("Off");
  });
});

describe("toggleDay", () => {
  it("fills a partly-selected row rather than clearing it", () => {
    expect(hoursOn(toggleDay(cells(...hr("Mon", 9)), "Mon"), "Mon")).toBe(24);
  });

  it("clears a row that is already full", () => {
    expect(hoursOn(toggleDay(wholeWeek(), "Mon"), "Mon")).toBe(0);
  });

  it("leaves the other days alone", () => {
    const next = toggleDay(cells(...hr("Tue", 3)), "Mon");

    expect(hr("Tue", 3).every((k) => next.has(k))).toBe(true);
    expect(hoursOn(next, "Tue")).toBe(1);
  });
});

describe("toggleHour", () => {
  it("fills the column across every day", () => {
    const next = toggleHour(cells(...hr("Mon", 9).slice(0, 1)), 9);

    expect(DAYS.every((day) => hr(day, 9).every((k) => next.has(k)))).toBe(true);
  });

  it("clears a column that every day already has", () => {
    const next = toggleHour(wholeWeek(), 9);

    expect(DAYS.some((day) => hr(day, 9).some((k) => next.has(k)))).toBe(false);
    // Only that hour goes; the rest of the week is untouched.
    expect(next.size).toBe(96 * 7 - 7 * 4);
  });
});

describe("round trip", () => {
  it("survives a full week", () => {
    const original = new Set<CellKey>();
    for (const day of ["Mon", "Wed", "Sun"] as const) {
      for (const hour of [0, 1, 9, 10, 11, 23]) for (const k of hr(day, hour)) original.add(k);
    }

    expect(slotsToCells(cellsToSlots(original))).toEqual(original);
  });

  it("survives a full week that is entirely selected", () => {
    const all = wholeWeek();

    expect(slotsToCells(cellsToSlots(all))).toEqual(all);
  });

  it("collapses a full week into seven slots", () => {
    const all = new Set<CellKey>();
    for (const day of ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"] as const) {
      for (let h = 0; h < 96; h++) all.add(cellKey(day, h));
    }

    // 168 selected hours should not become 168 rows in the database.
    expect(cellsToSlots(all)).toHaveLength(7);
  });
});

describe("overnight schedule editing", () => {
  it.each([
    ["Fri", "Sat"],
    ["Sun", "Mon"],
  ] as const)("keeps %s overnight hours on %s after a round trip", (day, nextDay) => {
    const overnight = slotsToCells([{ day, start: "22:00:00", end: "06:00:00" }]);
    expect(overnight).toEqual(
      new Set([
        ...hr(day, 22),
        ...hr(day, 23),
        ...Array.from({ length: 6 * 4 }, (_, q) => cellKey(nextDay, q)),
      ]),
    );
    expect(slotsToCells(cellsToSlots(overnight))).toEqual(overnight);
  });
});

describe("hasEndingHours", () => {
  const schedule = (cells: Set<CellKey>, enabled = true) => ({
    id: "s",
    name: "s",
    enabled,
    time_slots: cellsToSlots(cells),
  });

  it("is false for a list that is on all week", () => {
    // No schedule, no slots, and every hour set are the same thing to a lock:
    // there is no block of hours for it to follow.
    expect(hasEndingHours(null)).toBe(false);
    expect(hasEndingHours(schedule(cells()))).toBe(false);
    expect(hasEndingHours(schedule(wholeWeek()))).toBe(false);
  });

  it("is true for hours that leave a gap in the week", () => {
    expect(hasEndingHours(schedule(cells(...hr("Mon", 9), ...hr("Mon", 10))))).toBe(true);
    const allButOne = wholeWeek();
    allButOne.delete(cellKey("Sun", 12));
    expect(hasEndingHours(schedule(allButOne))).toBe(true);
  });

  it("is false for a schedule that is switched off", () => {
    expect(hasEndingHours(schedule(cells(...hr("Mon", 9)), false))).toBe(false);
  });
});

describe("summarizeWeek", () => {
  const week = (...keys: CellKey[]) => summarizeWeek(cellsToSlots(cells(...keys)));

  it("joins neighbouring days that have the same hours", () => {
    const work = DAYS.slice(0, 5).flatMap((d) => [9, 10, 11].flatMap((h) => hr(d, h)));
    expect(week(...work)).toBe("Mon–Fri 9am–12pm");
  });

  it("names a single day on its own", () => {
    expect(week(...hr("Sat", 10), ...hr("Sat", 11))).toBe("Sat 10am–12pm");
  });

  it("keeps days with different hours apart", () => {
    expect(week(...hr("Mon", 9), ...hr("Tue", 9), ...hr("Thu", 14))).toBe(
      "Mon–Tue 9am–10am · Thu 2pm–3pm",
    );
  });

  it("does not join days across a day that is off", () => {
    expect(week(...hr("Mon", 9), ...hr("Wed", 9))).toBe("Mon 9am–10am · Wed 9am–10am");
  });

  it("says nothing for no hours", () => {
    expect(summarizeWeek([])).toBe("");
  });
});

describe("quarter hours", () => {
  it("writes a quarter as a 15-minute slot", () => {
    expect(cellsToSlots(cells(cellKey("Mon", 37), cellKey("Mon", 38)))).toEqual([
      { day: "Mon", start: "09:15:00", end: "09:45:00" },
    ]);
  });

  it("reads minutes back and names them", () => {
    const set = slotsToCells([{ day: "Mon", start: "09:15:00", end: "10:30:00" }]);

    expect(set.size).toBe(5);
    expect(describeDay(set, "Mon")).toBe("9:15am–10:30am");
  });
});
