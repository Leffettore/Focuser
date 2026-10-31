import {
  AlertTriangle,
  Briefcase,
  CalendarRange,
  Check,
  Eraser,
  Infinity as InfinityIcon,
  Moon,
  Sunrise,
} from "lucide-react";
import type { ReactNode } from "react";
import { useEffect, useMemo, useState } from "react";
import { useSearchParams } from "react-router-dom";
import { ListHoursOptions } from "@/components/list-hours-options";
import { ListPicker, resolveSelected } from "@/components/list-picker";
import { LocationsPanel } from "@/components/locations-panel";
import { ScheduleGrid } from "@/components/schedule-grid";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, EmptyState, PageHeader } from "@/components/ui/card";
import { Dialog, ErrorDialog } from "@/components/ui/dialog";
import { QueryState } from "@/components/ui/feedback";
import { Page } from "@/components/ui/page";
import { Tabs } from "@/components/ui/tabs";
import {
  useBlockLists,
  useDevices,
  useDeviceUsage,
  useThisDevice,
  useUpdateDeviceSchedule,
  useUpdateSchedule,
} from "@/lib/commands";
import { rangeFor } from "@/lib/date-range";
import { formatDuration } from "@/lib/duration";
import {
  type CellKey,
  cellKey,
  cellsToSlots,
  DAYS,
  describeDay,
  HOURS,
  hoursOn,
  PRESETS,
  slotsToCells,
} from "@/lib/schedule";
import { cn } from "@/lib/utils";
import { m } from "@/paraglide/messages.js";

const sameCells = (a: Set<CellKey>, b: Set<CellKey>) =>
  a.size === b.size && [...a].every((k) => b.has(k));

const WEEK_HOURS = DAYS.length * HOURS.length;

/** Presets are defined in the lib; the icon is presentation, so it lives here. */
const PRESET_ICONS: Record<string, ReactNode> = {
  work: <Briefcase />,
  evenings: <Moon />,
  weekends: <Sunrise />,
  always: <InfinityIcon />,
};

export function Schedule() {
  const lists = useBlockLists();
  const saveShared = useUpdateSchedule();
  const saveDevice = useUpdateDeviceSchedule();
  const last30 = useMemo(() => rangeFor("30d"), []);
  const usage = useDeviceUsage(last30.from, last30.to);
  const known = useDevices();
  // "" is every device that has no hours of its own.
  const [device, setDevice] = useState("");
  const me = useThisDevice();
  // "Set schedule" and "Edit schedule" on a list card land here with that list
  // named in the address, so the page opens on it instead of on the first one.
  const [params] = useSearchParams();
  const [rawSelected, setSelected] = useState(() => params.get("list") ?? "");
  // "Set schedule" also asks for the grid of that list: it has no hours yet,
  // and the "always active" panel has nowhere to set them.
  const gridFor = params.get("set") === "hours" ? params.get("list") : null;
  const [needsGap, setNeedsGap] = useState(false);

  const all = lists.data ?? [];
  const selected = resolveSelected(all, rawSelected);
  const list = all.find((l) => l.id === selected);

  // A device's own hours replace the list's on that device only. "Always" is
  // stored there as a disabled schedule, and no entry means "same as all".
  const own = device ? list?.device_schedules?.[device] : undefined;
  const shown = device ? (own?.enabled ? own : undefined) : list?.schedule;
  const saved = useMemo(() => slotsToCells(shown?.time_slots ?? []), [shown]);
  const savedMode: Mode = device
    ? !own
      ? "same"
      : shown
        ? "scheduled"
        : "always"
    : list?.only_devices
      ? "none"
      : shown
        ? "scheduled"
        : "always";

  const [draft, setDraft] = useState<Set<CellKey>>(saved);
  const [mode, setMode] = useState<Mode>("always");

  // Reset the draft whenever we switch lists or devices, or the saved schedule changes.
  const listId = list?.id;
  useEffect(() => {
    setDraft(saved);
    setMode(savedMode === "always" && listId === gridFor ? "scheduled" : savedMode);
  }, [saved, savedMode, listId, gridFor]);

  // Hours are edited from the device they belong to, and not while they run:
  // cutting them short is how a block gets dodged. The command core refuses a
  // running edit too; the other-device rule is the screen's only (ponytail: no
  // auth on /command, so a determined caller can still set another device's hours).
  const now = new Date();
  const today = DAYS[(now.getDay() + 6) % 7];
  const running =
    savedMode === "scheduled" &&
    saved.size < WEEK_HOURS &&
    saved.has(cellKey(today, now.getHours()));
  const foreign = device !== "" && device !== me.device;
  const locked = foreign || running;
  const dirty =
    !locked &&
    list != null &&
    (mode !== savedMode || (mode === "scheduled" && !sameCells(draft, saved)));

  const save = device ? saveDevice : saveShared;
  const devices = [
    ...new Set([
      "phone",
      ...(me.device ? [me.device] : []),
      ...(known.data ?? []),
      ...(usage.data ?? []).map((u) => u.device),
      ...Object.keys(list?.device_schedules ?? {}),
    ]),
  ];

  // A scheduled lock and a shared allowance last for one block of hours, so a
  // list that has either cannot be saved as on all week. The command core
  // refuses that too; this says it in the reader's language, before the call.
  const followsHours = list?.scheduled_protection != null || list?.shared_allowance != null;
  const leavesGap = mode === "scheduled" && draft.size > 0 && draft.size < WEEK_HOURS;

  function commit() {
    if (!list) return;
    if (device) {
      saveDevice.mutate({
        listId: list.id,
        device,
        slots: mode === "scheduled" ? cellsToSlots(draft) : [],
        alwaysActive: mode === "always",
        sameAsAll: mode === "same",
      });
      return;
    }
    if (followsHours && !leavesGap) {
      setNeedsGap(true);
      return;
    }
    saveShared.mutate({
      listId: list.id,
      slots: mode === "scheduled" ? cellsToSlots(draft) : [],
      alwaysActive: mode === "always",
      none: mode === "none",
    });
  }

  return (
    <Page wide>
      <PageHeader
        title={m.schedule_title()}
        description={m.schedule_description()}
        actions={<ListPicker lists={all} value={selected} onChange={setSelected} />}
      />

      <QueryState
        isPending={lists.isPending}
        error={lists.error}
        onRetry={() => lists.refetch()}
        isRetrying={lists.isFetching}
      >
        {!list ? (
          <EmptyState
            title={m.websites_no_lists_title()}
            description={m.websites_no_lists_description()}
          />
        ) : (
          <>
            <div className="mb-3 flex flex-wrap items-center gap-2">
              <Tabs
                value={device}
                onChange={setDevice}
                items={[
                  { id: "", label: m.schedule_device_all() },
                  ...devices.map((d) => ({ id: d, label: d })),
                ]}
              />
              {me.choose && (
                <select
                  value={me.device}
                  onChange={(e) => me.choose?.(e.target.value)}
                  aria-label={m.schedule_this_device()}
                  className="h-9 rounded-md border border-border bg-transparent px-2 text-sm"
                >
                  <option value="">{m.schedule_this_device()}</option>
                  {devices.map((d) => (
                    <option key={d} value={d}>
                      {d}
                    </option>
                  ))}
                </select>
              )}
            </div>
            <Tabs
              className="mb-5"
              value={mode}
              onChange={(next) => !locked && setMode(next)}
              items={[
                ...(device ? [{ id: "same" as const, label: m.schedule_mode_same() }] : []),
                ...(device ? [] : [{ id: "none" as const, label: m.schedule_mode_none() }]),
                { id: "always" as const, label: m.schedule_mode_always() },
                { id: "scheduled" as const, label: m.schedule_mode_scheduled() },
              ]}
            />
            {locked && (
              <p className="-mt-2 mb-4 text-muted-foreground text-sm">
                {foreign ? m.schedule_other_device({ device }) : m.schedule_running()}
              </p>
            )}

            {/* Editor first, summary under it. Side by side squeezed the grid
                into a column too narrow for 24 legible hours. */}
            <div className="space-y-5">
              {mode === "scheduled" ? (
                <div
                  className={locked ? "pointer-events-none opacity-60" : undefined}
                  aria-disabled={locked}
                >
                  <GridPanel draft={draft} onChange={setDraft} />
                </div>
              ) : mode === "none" ? (
                <Card padding="lg" elevation="raised" className="edge-light">
                  <p className="text-muted-foreground text-sm">{m.schedule_none_body()}</p>
                </Card>
              ) : mode === "same" ? (
                <Card padding="lg" elevation="raised" className="edge-light">
                  <p className="text-muted-foreground text-sm">{m.schedule_same_body()}</p>
                </Card>
              ) : (
                <AlwaysPanel />
              )}

              <WeekSummary
                cells={mode === "scheduled" ? draft : null}
                dirty={dirty}
                saving={save.isPending}
                onSave={commit}
              />

              <LocationsPanel
                listId={list.id}
                locations={list.locations ?? []}
                others={all.filter((l) => l.id !== list.id).flatMap((l) => l.locations ?? [])}
                disabled={false}
              />

              <Card padding="none" elevation="raised" className="edge-light overflow-hidden">
                <ListHoursOptions list={list} page="schedule" />
              </Card>
            </div>

            <Dialog
              open={needsGap}
              onClose={() => setNeedsGap(false)}
              role="alertdialog"
              title={m.schedule_needs_gap_title()}
              icon={<AlertTriangle aria-hidden className="text-warning" />}
            >
              <p className="mt-2 text-muted-foreground text-sm leading-relaxed">
                {m.schedule_needs_gap_body({ name: list.name })}
              </p>
              <div className="mt-5 flex justify-end">
                <Button
                  variant="outline"
                  size="sm"
                  data-autofocus
                  onClick={() => setNeedsGap(false)}
                >
                  {m.common_ok()}
                </Button>
              </div>
            </Dialog>
            <ErrorDialog error={save.error} onClose={() => save.reset()} />
          </>
        )}
      </QueryState>
    </Page>
  );
}

type Mode = "none" | "always" | "scheduled" | "same";

function AlwaysPanel() {
  return (
    <Card padding="lg" elevation="raised" className="edge-light">
      <div className="flex items-start gap-4">
        <span className="flex size-12 shrink-0 items-center justify-center rounded-xl bg-primary/15 text-primary ring-1 ring-primary/25 ring-inset">
          <InfinityIcon aria-hidden className="size-6" />
        </span>
        <div className="min-w-0">
          <p className="font-medium text-foreground">{m.schedule_always_heading()}</p>
          <p className="mt-1 text-muted-foreground text-sm leading-relaxed">
            {m.schedule_always_body_before()}{" "}
            <span className="text-foreground">{m.schedule_mode_scheduled()}</span>{" "}
            {m.schedule_always_body_after()}
          </p>
        </div>
      </div>

      {/* A full week at a glance, so this tab is not a single sentence on an
          otherwise blank page. */}
      <div className="mt-6 grid grid-cols-7 gap-1.5">
        {DAYS.map((day) => (
          <div
            key={day}
            className="rounded-lg bg-primary/15 px-2 py-3 text-center ring-1 ring-primary/20 ring-inset"
          >
            <p className="font-medium text-primary text-xs">{day}</p>
            <p className="mt-0.5 text-[10px] text-primary/70">24h</p>
          </div>
        ))}
      </div>
    </Card>
  );
}

function GridPanel({
  draft,
  onChange,
}: {
  draft: Set<CellKey>;
  onChange: React.Dispatch<React.SetStateAction<Set<CellKey>>>;
}) {
  return (
    <Card padding="lg" elevation="raised" className="edge-light">
      <div className="mb-4 flex flex-wrap items-center gap-1.5">
        <span className="mr-1 text-muted-foreground text-xs">{m.grid_presets()}</span>
        {PRESETS.map((p) => (
          <Button
            key={p.id}
            variant="outline"
            size="sm"
            icon={PRESET_ICONS[p.id]}
            onClick={() => onChange(p.build())}
          >
            {p.label}
          </Button>
        ))}
        <Button
          variant="ghost"
          size="sm"
          icon={<Eraser />}
          onClick={() => onChange(new Set())}
          disabled={draft.size === 0}
          className="ml-auto"
        >
          {m.common_clear()}
        </Button>
      </div>

      <ScheduleGrid selected={draft} onChange={onChange} />

      <div className="mt-4 flex flex-wrap items-center gap-x-4 gap-y-2 text-faint-foreground text-xs">
        <span className="flex items-center gap-1.5">
          <span className="size-2.5 rounded-[3px] bg-primary" />
          {m.grid_legend_blocking()}
        </span>
        <span className="flex items-center gap-1.5">
          <span className="size-2.5 rounded-[3px] bg-elevated" />
          {m.grid_legend_off()}
        </span>
        <span className="ml-auto">{m.grid_hint()}</span>
      </div>
    </Card>
  );
}

/** The week in words, plus the save action. `null` cells means "always on". */
function WeekSummary({
  cells,
  dirty,
  saving,
  onSave,
}: {
  cells: Set<CellKey> | null;
  dirty: boolean;
  saving: boolean;
  onSave: () => void;
}) {
  const total = cells ? cells.size : WEEK_HOURS;
  const share = Math.round((total / WEEK_HOURS) * 100);

  return (
    <Card padding="none" elevation="raised" className="edge-light overflow-hidden">
      <div className="flex flex-wrap items-end gap-x-8 gap-y-4 px-5 py-4">
        <div>
          <span className="flex items-center gap-1.5 text-muted-foreground text-xs">
            <CalendarRange aria-hidden className="size-3.5" />
            {m.schedule_weekly_total()}
          </span>
          <p className="mt-1.5 flex items-baseline gap-2.5">
            <span className="font-semibold text-3xl text-foreground tabular-nums">
              {formatDuration(total * 3600)}
            </span>
            <span className="text-faint-foreground text-xs">of {WEEK_HOURS} hours</span>
          </p>
        </div>

        <div className="min-w-48 flex-1">
          <div className="mb-2 flex items-center justify-between gap-2">
            <span className="text-faint-foreground text-xs">{m.schedule_coverage()}</span>
            <Badge tone={total > 0 ? "primary" : "neutral"}>{share}%</Badge>
          </div>
          <div className="h-2 overflow-hidden rounded-full bg-elevated">
            <div
              className="h-full rounded-full bg-primary transition-[width] duration-300"
              style={{ width: `${share}%` }}
            />
          </div>
        </div>

        <div className="ml-auto flex items-center gap-2">
          {dirty && (
            <Badge tone="warning" outlined>
              {m.schedule_unsaved()}
            </Badge>
          )}
          <Button onClick={onSave} disabled={!dirty || saving} icon={dirty ? undefined : <Check />}>
            {saving ? m.schedule_saving() : dirty ? m.schedule_save() : m.schedule_saved()}
          </Button>
        </div>
      </div>

      {/* One column per day, so the week reads left to right like the grid
          above it rather than as a list running the other way. */}
      <div className="grid grid-cols-2 gap-px border-border border-t bg-border sm:grid-cols-4 lg:grid-cols-7">
        {DAYS.map((day) => {
          const on = cells ? hoursOn(cells, day) : HOURS.length;
          const text = cells ? describeDay(cells, day) : m.schedule_all_day();
          return (
            <div key={day} className="bg-surface px-4 py-3">
              <div className="flex items-baseline justify-between gap-2">
                <span
                  className={cn(
                    "font-medium text-sm",
                    on > 0 ? "text-foreground" : "text-faint-foreground",
                  )}
                >
                  {day}
                </span>
                <span
                  className={cn(
                    "text-xs tabular-nums",
                    on > 0 ? "text-primary" : "text-faint-foreground",
                  )}
                >
                  {on > 0 ? `${on}h` : "off"}
                </span>
              </div>
              <p
                className={cn(
                  "mt-1 truncate text-xs",
                  on > 0 ? "text-muted-foreground" : "text-faint-foreground",
                )}
                title={text}
              >
                {text}
              </p>
            </div>
          );
        })}
      </div>
    </Card>
  );
}
