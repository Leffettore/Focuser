import { Link } from "react-router-dom";
import type { BlockList } from "@/bindings";
import { Badge } from "@/components/ui/badge";
import { Card } from "@/components/ui/card";
import { assignColors } from "@/lib/chart-colors";
import { DAYS, formatSlot, runsForDay, SLOTS, slotsToCells } from "@/lib/schedule";
import { m } from "@/paraglide/messages.js";

/** Where a chip leads: the editor, already on that list and device. */
const editor = (list: BlockList, device: string) =>
  `/schedule?list=${list.id}&device=${encodeURIComponent(device)}`;

/**
 * The week of every enabled list, by device: when each one starts blocking and
 * where. Hours and places live on the server; this only reads them.
 */
export function WeekCalendar({ lists }: { lists: BlockList[] }) {
  const on = lists.filter((l) => l.enabled);
  const entries = on.flatMap((list) =>
    Object.entries(list.device_schedules ?? {}).map(([device, hours]) => ({
      list,
      device,
      hours,
    })),
  );
  const always = entries.filter((e) => !e.hours.enabled);
  const timed = entries
    .filter((e) => e.hours.enabled)
    .map((e) => ({ ...e, cells: slotsToCells(e.hours.time_slots) }));
  // A side line in the device's colour, so blocks of different devices tell apart.
  const devices = [...new Set(entries.map((e) => e.device))].sort();
  const colors = assignColors(devices);
  const withPlaces = on.filter((l) => (l.locations ?? []).length > 0);

  if (entries.length === 0 && withPlaces.length === 0) {
    return <p className="text-muted-foreground text-sm">{m.dashboard_week_empty()}</p>;
  }

  return (
    <div className="space-y-3">
      <div className="grid grid-cols-2 gap-2 sm:grid-cols-4 lg:grid-cols-7">
        {DAYS.map((day) => {
          const chips = timed
            .flatMap((e) => runsForDay(e.cells, day).map(([start, end]) => ({ ...e, start, end })))
            .sort((a, b) => a.start - b.start);
          return (
            <Card key={day} padding="none" elevation="raised" className="min-h-24 p-2.5">
              <p className="mb-1.5 font-medium text-foreground text-xs">{day}</p>
              <div className="flex flex-col gap-1.5">
                {chips.map((c) => (
                  <Link
                    key={`${c.list.id}-${c.device}-${c.start}`}
                    to={editor(c.list, c.device)}
                    style={{ borderLeftColor: colors.get(c.device) }}
                    className="rounded-md border-l-[3px] bg-primary/15 px-2 py-1 text-xs ring-1 ring-primary/20 ring-inset hover:bg-primary/25"
                  >
                    <span className="block font-medium text-primary tabular-nums">
                      {formatSlot(c.start)}–{formatSlot(c.end % SLOTS)}
                    </span>
                    <span className="block truncate text-muted-foreground">
                      {c.list.name} · {c.device}
                    </span>
                  </Link>
                ))}
              </div>
            </Card>
          );
        })}
      </div>

      {devices.length > 1 && (
        <ul className="flex flex-wrap items-center gap-x-3 gap-y-1 text-muted-foreground text-xs">
          {devices.map((device) => (
            <li key={device} className="flex items-center gap-1.5">
              <span
                aria-hidden
                className="h-3.5 w-[3px] rounded-full"
                style={{ backgroundColor: colors.get(device) }}
              />
              {device}
            </li>
          ))}
        </ul>
      )}

      {always.length > 0 && (
        <div className="flex flex-wrap items-center gap-1.5 text-xs">
          <span className="text-muted-foreground">{m.dashboard_week_always()}</span>
          {always.map((e) => (
            <Link key={`${e.list.id}-${e.device}`} to={editor(e.list, e.device)}>
              <Badge tone="primary">
                {e.list.name} · {e.device}
              </Badge>
            </Link>
          ))}
        </div>
      )}

      {withPlaces.length > 0 && (
        <div className="flex flex-wrap items-center gap-1.5 text-xs">
          <span className="text-muted-foreground">{m.dashboard_week_places()}</span>
          {withPlaces.flatMap((list) =>
            (list.locations ?? []).map((place) => (
              <Link key={`${list.id}-${place.name}`} to={editor(list, "phone")}>
                <Badge tone={place.mode === "Block" ? "info" : "neutral"}>
                  {list.name} · {place.name} ·{" "}
                  {place.mode === "Block"
                    ? m.schedule_location_block()
                    : m.schedule_location_skip()}
                </Badge>
              </Link>
            )),
          )}
        </div>
      )}
    </div>
  );
}
