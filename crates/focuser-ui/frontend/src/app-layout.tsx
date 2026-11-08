import {
  AppWindow,
  BarChart3,
  CalendarClock,
  Globe,
  Hourglass,
  LayoutDashboard,
  ListChecks,
  PanelLeftClose,
  PanelLeftOpen,
  Settings,
} from "lucide-react";
import { useState } from "react";
import { NavLink, Outlet } from "react-router-dom";
import { BlockingHealthBanner } from "@/components/blocking-health-banner";
import { TitleBar } from "@/components/title-bar";
import { AppIcon } from "@/components/ui/app-icon";
import { LiveBadge } from "@/components/ui/badge";
import { UpdatePill } from "@/components/update-pill";
import { usePomodoroStatus } from "@/lib/commands";
import { formatCountdown } from "@/lib/duration";
import { useApplySavedLanguage } from "@/lib/language";
import { cn } from "@/lib/utils";
import { m } from "@/paraglide/messages.js";

// `label` is a function, not a string: a message read at module scope would
// freeze the locale that was active when this file was imported.
const NAV = [
  { to: "/", label: m.nav_dashboard, icon: LayoutDashboard, end: true },
  { to: "/block-lists", label: m.nav_block_lists, icon: ListChecks },
  { to: "/websites", label: m.nav_websites, icon: Globe },
  { to: "/apps", label: m.nav_applications, icon: AppWindow },
  { to: "/schedule", label: m.nav_schedule, icon: CalendarClock },
  { to: "/allowances", label: m.nav_allowances, icon: Hourglass },
  { to: "/statistics", label: m.nav_statistics, icon: BarChart3 },
  { to: "/settings", label: m.nav_settings, icon: Settings },
] as const;

const NAV_HIDDEN_KEY = "focuser.navHidden";

/** Whether the narrow-screen menu is tucked away; remembered per device. */
function useNavHidden() {
  const [hidden, setHidden] = useState(() => {
    try {
      return localStorage.getItem(NAV_HIDDEN_KEY) === "1";
    } catch {
      return false;
    }
  });
  const set = (value: boolean) => {
    setHidden(value);
    try {
      localStorage.setItem(NAV_HIDDEN_KEY, value ? "1" : "0");
    } catch {}
  };
  return [hidden, set] as const;
}

export function AppLayout() {
  // Wraps every route, so this is the one place the saved language reaches the
  // whole app rather than only the page that happens to read it.
  useApplySavedLanguage();
  const [hidden, setHidden] = useNavHidden();

  return (
    <div className="flex h-screen flex-col overflow-hidden bg-deep">
      <TitleBar />

      <div className="flex min-h-0 flex-1 flex-row">
        <Sidebar hidden={hidden} onHide={() => setHidden(true)} />
        {!hidden && (
          // Tapping the page beside the open menu closes it.
          <button
            type="button"
            tabIndex={-1}
            aria-hidden
            onClick={() => setHidden(true)}
            className="fixed inset-0 z-30 bg-black/40 md:hidden"
          />
        )}
        {hidden && (
          <button
            type="button"
            onClick={() => setHidden(false)}
            aria-label={m.nav_show()}
            title={m.nav_show()}
            className="glass fixed top-2 right-2 z-30 rounded-lg border border-border/60 p-2 text-muted-foreground md:hidden"
          >
            <PanelLeftOpen aria-hidden className="size-6" />
          </button>
        )}
        <main className="app-canvas min-w-0 flex-1 overflow-y-auto bg-background">
          <BlockingHealthBanner />
          <Outlet />
        </main>
      </div>
    </div>
  );
}

function Sidebar({ hidden, onHide }: { hidden: boolean; onHide: () => void }) {
  return (
    // Narrow screens get an icon rail down the left edge that can be tucked away.
    <nav
      aria-label={m.nav_landmark()}
      className={cn(
        // Over the page on narrow screens, so opening it does not squeeze the content.
        "glass fixed inset-y-0 left-0 z-40 flex flex-col overflow-y-auto border-border/60 border-r bg-surface p-1.5 shadow-xl",
        "md:static md:z-auto md:w-56 md:shrink-0 md:bg-transparent md:p-3 md:shadow-none",
        hidden && "hidden md:flex",
      )}
    >
      <Brand />

      <button
        type="button"
        onClick={onHide}
        aria-label={m.nav_hide()}
        title={m.nav_hide()}
        className="mb-1 flex items-center justify-center rounded-lg p-3 text-muted-foreground hover:bg-foreground/[0.04] md:hidden"
      >
        <PanelLeftClose aria-hidden className="size-6" />
      </button>

      <div className="flex flex-1 flex-col gap-0.5 md:flex-none">
        {NAV.map(({ to, label, icon: Icon, ...rest }) => (
          <NavLink
            key={to}
            to={to}
            end={"end" in rest ? rest.end : undefined}
            className={({ isActive }) =>
              cn(
                "group relative flex items-center justify-center gap-3 rounded-lg p-3 font-medium text-sm md:justify-start md:px-3 md:py-2",
                "transition-colors duration-150",
                isActive
                  ? "bg-primary/12 text-foreground"
                  : // A hairline tint on hover. The previous fill was a solid
                    // block the width of the sidebar, which read as a selection.
                    "text-muted-foreground hover:bg-foreground/[0.04] hover:text-foreground",
              )
            }
          >
            {({ isActive }) => (
              <>
                {/* A bar rather than a whole-row fill, so the current page is
                    findable at a glance without a second colour block. */}
                <span
                  aria-hidden
                  className={cn(
                    "-translate-y-1/2 absolute top-1/2 left-0 hidden w-[3px] rounded-r-full md:block bg-primary",
                    "transition-all duration-200",
                    isActive ? "h-5 opacity-100" : "h-0 opacity-0",
                  )}
                />
                <Icon
                  aria-hidden
                  className={cn(
                    "size-6 shrink-0 transition-colors md:size-4",
                    isActive ? "text-primary" : "text-faint-foreground group-hover:text-foreground",
                  )}
                />
                <span className="sr-only md:not-sr-only">{label()}</span>
              </>
            )}
          </NavLink>
        ))}
      </div>

      {/* `mt-auto` on the group, not each child, or only the first moves. */}
      <div className="mt-auto hidden flex-col gap-2 pt-3 md:flex">
        <UpdatePill />
        <SessionPill />
      </div>
    </nav>
  );
}

function Brand() {
  return (
    <div className="mb-5 hidden items-center gap-2.5 px-2 py-3 md:flex">
      <AppIcon className="size-8 rounded-lg" />
      <span className="font-semibold text-base text-foreground tracking-tight">Focuser</span>
    </div>
  );
}

/**
 * A running focus session, pinned to the bottom of the sidebar.
 *
 * The countdown is the one thing worth seeing from every page — otherwise you
 * have to keep returning to the Dashboard to check it.
 */
function SessionPill() {
  const status = usePomodoroStatus();
  if (!status.data) return null;

  const phase = status.data.current_phase === "work" ? m.session_focus() : m.session_break();

  return (
    <div className="glass-strong rounded-lg border border-primary/25 p-3">
      <div className="flex items-center justify-between gap-2">
        <LiveBadge tone={status.data.current_phase === "work" ? "primary" : "success"}>
          {status.data.paused ? m.session_paused() : phase}
        </LiveBadge>
        <span className="font-semibold text-foreground text-sm tabular-nums">
          {formatCountdown(status.data.remaining_secs)}
        </span>
      </div>
      <p className="mt-1.5 truncate text-faint-foreground text-xs">{status.data.block_list_name}</p>
    </div>
  );
}
