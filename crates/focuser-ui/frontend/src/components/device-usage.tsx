import { Smartphone } from "lucide-react";
import { useMemo } from "react";
import { Card, EmptyState, Section } from "@/components/ui/card";
import { QueryState } from "@/components/ui/feedback";
import { Stat, StatGrid } from "@/components/ui/stat";
import { useDeviceUsage } from "@/lib/commands";
import type { DateRange } from "@/lib/date-range";
import { summariseDeviceUsage } from "@/lib/device-usage";
import { formatDuration } from "@/lib/duration";
import { m } from "@/paraglide/messages.js";

/** Time on each app or site, split by the device it was spent on. */
export function DeviceUsageSection({ range }: { range: DateRange }) {
  const usage = useDeviceUsage(range.from, range.to);
  const summary = useMemo(() => summariseDeviceUsage(usage.data ?? []), [usage.data]);

  return (
    <Section title={m.statistics_by_device()}>
      <QueryState
        isPending={usage.isPending}
        error={usage.error}
        onRetry={() => usage.refetch()}
        isRetrying={usage.isFetching}
      >
        {summary.rows.length === 0 ? (
          <EmptyState icon={<Smartphone />} title={m.statistics_by_device_empty()} />
        ) : (
          <>
            <StatGrid>
              {summary.devices.map((d) => (
                <Stat key={d.device} label={d.device} value={formatDuration(d.seconds)} />
              ))}
            </StatGrid>
            <Card padding="none" elevation="raised" className="mt-4 overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="text-left text-muted-foreground text-xs">
                    <th className="px-4 py-2 font-medium" />
                    {summary.devices.map((d) => (
                      <th key={d.device} className="px-4 py-2 text-right font-medium">
                        {d.device}
                      </th>
                    ))}
                    <th className="px-4 py-2 text-right font-medium">{m.statistics_total()}</th>
                  </tr>
                </thead>
                <tbody>
                  {summary.rows.slice(0, 15).map((r) => (
                    <tr key={r.key} className="border-border border-t">
                      <td className="px-4 py-2 capitalize">{r.key}</td>
                      {summary.devices.map((d) => (
                        <td key={d.device} className="px-4 py-2 text-right tabular-nums">
                          {r.byDevice[d.device] ? formatDuration(r.byDevice[d.device]) : "—"}
                        </td>
                      ))}
                      <td className="px-4 py-2 text-right font-medium tabular-nums">
                        {formatDuration(r.total)}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </Card>
          </>
        )}
      </QueryState>
    </Section>
  );
}
