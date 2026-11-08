import type { Schedule, TimeSlot } from "@/bindings";
import { uses24Hour } from "./clock";

export const DAYS = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"] as const;
export type Day = (typeof DAYS)[number];

export const HOURS = Array.from({ length: 24 }, (_, h) => h);

/** The grid's unit is a quarter of an hour; a cell is keyed by its quarter of the day. */
export const PER_HOUR = 4;
export const SLOTS = 24 * PER_HOUR;
const QUARTERS = Array.from({ length: SLOTS }, (_, q) => q);
export const WEEK_SLOTS = 7 * SLOTS;

/** A selected hour, keyed as `Mon-9`. */
export type CellKey = `${Day}-${number}`;

export const cellKey = (day: Day, hour: number): CellKey => `${day}-${hour}`;

const pad = (n: number) => String(n).padStart(2, "0");
const toTime = (slot: number) =>
  `${pad(Math.floor(slot / PER_HOUR))}:${pad((slot % PER_HOUR) * 15)}:00`;

/** The last quarter of the day ends at midnight; the backend treats that as a wrap. */
const endTime = (slot: number) => toTime((slot + 1) % SLOTS);

const slotOf = (time: string) =>
  Number(time.slice(0, 2)) * PER_HOUR + Math.floor(Number(time.slice(3, 5)) / 15);

export function slotsToCells(slots: TimeSlot[]): Set<CellKey> {
  const cells = new Set<CellKey>();

  for (const slot of slots) {
    const day = slot.day as Day;
    if (!DAYS.includes(day)) continue;

    const start = slotOf(slot.start);
    const rawEnd = slotOf(slot.end);
    // An end of 00:00 always means midnight at the end of the day. This used to
    // exclude slots starting at 00:00, which made a full day — saved as
    // 00:00–00:00 — read back as nothing at all. A zero-length slot is never
    // written, so there is no other reading of it.
    const end = rawEnd === 0 ? SLOTS : rawEnd;

    if (end < start) {
      const nextDay = DAYS[(DAYS.indexOf(day) + 1) % DAYS.length];
      for (let h = start; h < SLOTS; h++) cells.add(cellKey(day, h));
      for (let h = 0; h < end; h++) cells.add(cellKey(nextDay, h));
    } else {
      for (let h = start; h < end; h++) cells.add(cellKey(day, h));
    }
  }

  return cells;
}

/** A day's selected hours, collapsed to `[start, endExclusive]` pairs. */
export function runsForDay(cells: Set<CellKey>, day: Day): [number, number][] {
  const hours = QUARTERS.filter((h) => cells.has(cellKey(day, h)));
  if (hours.length === 0) return [];

  const runs: [number, number][] = [];
  let start = hours[0];
  let prev = hours[0];

  for (const hour of hours.slice(1)) {
    if (hour !== prev + 1) {
      runs.push([start, prev + 1]);
      start = hour;
    }
    prev = hour;
  }

  runs.push([start, prev + 1]);
  return runs;
}

/** Merge each day's selected hours into as few contiguous slots as possible. */
export function cellsToSlots(cells: Set<CellKey>): TimeSlot[] {
  return DAYS.flatMap((day) =>
    runsForDay(cells, day).map(([start, end]) => ({
      day,
      start: toTime(start),
      end: endTime(end - 1),
    })),
  );
}

/** Hours selected on a day; a quarter counts as 0.25. */
export function hoursOn(cells: Set<CellKey>, day: Day): number {
  return QUARTERS.reduce((n, q) => n + (cells.has(cellKey(day, q)) ? 1 : 0), 0) / PER_HOUR;
}

/** "9am–5pm", "9am–12pm, 2–5pm", or "Off". Used in the week summary. */
export function describeDay(cells: Set<CellKey>, day: Day): string {
  const runs = runsForDay(cells, day);
  if (runs.length === 0) return "Off";
  if (runs.length === 1 && runs[0][0] === 0 && runs[0][1] === SLOTS) return "All day";
  return runs.map(([start, end]) => `${formatSlot(start)}–${formatSlot(end % SLOTS)}`).join(", ");
}

/**
 * Whether the hours leave a gap in the week.
 *
 * A scheduled lock and a shared allowance last for one block of hours. A list
 * that is on all week, with no hours set or with every hour set, has no block
 * for them to follow. The command core refuses them for the same reason
 * (`ensure_schedule_ends`); this lets the screen say so before the click.
 */
export function hasEndingHours(schedule: Schedule | null | undefined): boolean {
  if (!schedule?.enabled) return false;
  const on = slotsToCells(schedule.time_slots).size;
  return on > 0 && on < WEEK_SLOTS;
}

/**
 * The week in one line: "Mon–Fri 9am–5pm · Sat 10am–2pm". Days in a row with
 * the same hours share an entry.
 */
export function summarizeWeek(slots: TimeSlot[]): string {
  const cells = slotsToCells(slots);
  const groups: { first: Day; last: Day; hours: string }[] = [];

  DAYS.forEach((day, index) => {
    if (hoursOn(cells, day) === 0) return;
    const hours = describeDay(cells, day);
    const previous = groups.at(-1);
    if (previous?.hours === hours && DAYS.indexOf(previous.last) === index - 1) {
      previous.last = day;
    } else {
      groups.push({ first: day, last: day, hours });
    }
  });

  return groups
    .map(({ first, last, hours }) => `${first === last ? first : `${first}–${last}`} ${hours}`)
    .join(" · ");
}

/** Fill a whole row, or clear it when it is already full. */
export function toggleDay(cells: Set<CellKey>, day: Day): Set<CellKey> {
  const next = new Set(cells);
  const full = hoursOn(cells, day) === 24;
  for (const hour of QUARTERS) {
    if (full) next.delete(cellKey(day, hour));
    else next.add(cellKey(day, hour));
  }
  return next;
}

/** Fill one hour across every day, or clear it when every day already has it. */
export function toggleHour(cells: Set<CellKey>, hour: number): Set<CellKey> {
  const next = new Set(cells);
  const quarters = QUARTERS.slice(hour * PER_HOUR, (hour + 1) * PER_HOUR);
  const full = DAYS.every((day) => quarters.every((q) => cells.has(cellKey(day, q))));
  for (const day of DAYS) {
    for (const q of quarters) {
      if (full) next.delete(cellKey(day, q));
      else next.add(cellKey(day, q));
    }
  }
  return next;
}

// Presets are written in hours; the grid wants quarters.
const range = (from: number, to: number) =>
  QUARTERS.filter((q) => q >= from * PER_HOUR && q < to * PER_HOUR);

export const PRESETS: { id: string; label: string; build: () => Set<CellKey> }[] = [
  {
    id: "work",
    label: "Work hours",
    build: () => fill(["Mon", "Tue", "Wed", "Thu", "Fri"], range(9, 17)),
  },
  {
    id: "evenings",
    label: "Evenings",
    build: () => fill([...DAYS], range(18, 24)),
  },
  {
    id: "weekends",
    label: "Weekends",
    build: () => fill(["Sat", "Sun"], QUARTERS),
  },
  {
    id: "always",
    label: "Every hour",
    build: () => fill([...DAYS], QUARTERS),
  },
];

function fill(days: Day[], hours: number[]): Set<CellKey> {
  const cells = new Set<CellKey>();
  for (const day of days) for (const hour of hours) cells.add(cellKey(day, hour));
  return cells;
}

export function formatHour(hour: number) {
  if (uses24Hour()) return `${pad(hour)}:00`;
  if (hour === 0) return "12am";
  if (hour === 12) return "12pm";
  return hour < 12 ? `${hour}am` : `${hour - 12}pm`;
}

/** A quarter of the day as a time: "9am", "9:15am", or "09:15" on a 24-hour clock. */
export function formatSlot(slot: number) {
  const hour = Math.floor(slot / PER_HOUR);
  const minutes = (slot % PER_HOUR) * 15;
  if (minutes === 0) return formatHour(hour);
  if (uses24Hour()) return `${pad(hour)}:${pad(minutes)}`;
  return `${formatHour(hour).replace(/(am|pm)$/, "")}:${pad(minutes)}${hour < 12 ? "am" : "pm"}`;
}
