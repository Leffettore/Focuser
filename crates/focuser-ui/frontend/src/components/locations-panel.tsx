import L from "leaflet";
import "leaflet/dist/leaflet.css";
import { MapPin, Trash2 } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import type { Location } from "@/bindings";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { Dialog, ErrorDialog } from "@/components/ui/dialog";
import { Tabs } from "@/components/ui/tabs";
import { useSetListLocations } from "@/lib/commands";
import { deviceHere } from "@/lib/native";
import { m } from "@/paraglide/messages.js";

type Point = { lat: number; lon: number };

// The generated type allows null for a number that is not finite; one that is saved never is.
const pointOf = (l?: Location): Point | undefined =>
  l?.lat != null && l.lon != null ? { lat: l.lat, lon: l.lon } : undefined;

/** Where the list blocks or never blocks. The phone follows these; no place means everywhere. */
export function LocationsPanel({
  listId,
  locations,
  others,
  disabled,
}: {
  listId: string;
  locations: Location[];
  /** Places on the other lists, to reuse without drawing them again. */
  others: Location[];
  disabled: boolean;
}) {
  const save = useSetListLocations();
  const [adding, setAdding] = useState(false);
  const set = (next: Location[]) => save.mutate({ listId, locations: next });
  const reusable = others.filter(
    (o, i) =>
      !locations.some((l) => l.name === o.name) && others.findIndex((x) => x.name === o.name) === i,
  );

  return (
    <Card padding="lg" elevation="raised" className="edge-light">
      <p className="font-medium text-foreground">{m.schedule_locations_title()}</p>
      <p className="mt-1 text-muted-foreground text-sm">
        {locations.length === 0 ? m.schedule_locations_everywhere() : m.schedule_locations_hint()}
      </p>
      <ul className="mt-3 space-y-1.5">
        {locations.map((l) => (
          <li key={l.name} className="flex items-center gap-2 text-sm">
            <MapPin aria-hidden className="size-4 shrink-0 text-muted-foreground" />
            <span className="min-w-0 flex-1 truncate">{l.name}</span>
            <Badge>
              {l.mode === "Block" ? m.schedule_location_block() : m.schedule_location_skip()}
            </Badge>
            <span className="text-muted-foreground tabular-nums">{l.radius_m} m</span>
            <Button
              size="sm"
              variant="ghost"
              aria-label={m.schedule_location_remove({ name: l.name })}
              disabled={disabled || save.isPending}
              onClick={() => set(locations.filter((x) => x.name !== l.name))}
            >
              <Trash2 aria-hidden className="size-4" />
            </Button>
          </li>
        ))}
      </ul>
      <div className="mt-3 flex flex-wrap items-center gap-1.5">
        <Button size="sm" variant="outline" disabled={disabled} onClick={() => setAdding(true)}>
          {m.schedule_locations_add()}
        </Button>
        {reusable.map((o) => (
          <Button
            key={o.name}
            size="sm"
            variant="outline"
            disabled={disabled || save.isPending}
            onClick={() => set([...locations, o])}
          >
            {m.schedule_location_reuse()}: {o.name}
          </Button>
        ))}
      </div>
      <AddLocation
        open={adding}
        centre={pointOf(locations[0] ?? others[0])}
        taken={locations.map((l) => l.name)}
        onClose={() => setAdding(false)}
        onSave={(l) => {
          set([...locations, l]);
          setAdding(false);
        }}
      />
      <ErrorDialog error={save.error} onClose={() => save.reset()} />
    </Card>
  );
}

function AddLocation({
  open,
  centre,
  taken,
  onClose,
  onSave,
}: {
  open: boolean;
  centre?: Point;
  taken: string[];
  onClose: () => void;
  onSave: (location: Location) => void;
}) {
  const [point, setPoint] = useState<Point>();
  const [name, setName] = useState("");
  const [radius, setRadius] = useState(150);
  const [mode, setMode] = useState<Location["mode"]>("Block");
  const [query, setQuery] = useState("");
  const [found, setFound] = useState<Point>();
  const [missing, setMissing] = useState(false);
  const [here, setHere] = useState<Point>();

  // The phone grants location access after this opens and may need a moment for a fix, so ask a few times.
  useEffect(() => {
    if (!open) return;
    let tries = 0;
    const ask = () => {
      const at = deviceHere();
      if (at) setHere(at);
      return at != null || ++tries >= 10;
    };
    if (ask()) return;
    const timer = setInterval(() => ask() && clearInterval(timer), 1500);
    return () => clearInterval(timer);
  }, [open]);

  useEffect(() => {
    if (open) {
      setPoint(undefined);
      setName("");
      setFound(undefined);
      setMissing(false);
    }
  }, [open]);

  // ponytail: Nominatim asks for light, user-typed use only; swap for another geocoder if this grows.
  async function search() {
    setMissing(false);
    try {
      const res = await fetch(
        `https://nominatim.openstreetmap.org/search?format=json&limit=1&q=${encodeURIComponent(query)}`,
      );
      const [hit] = (await res.json()) as { lat: string; lon: string }[];
      if (hit) setFound({ lat: Number(hit.lat), lon: Number(hit.lon) });
      else setMissing(true);
    } catch {
      setMissing(true);
    }
  }

  const clean = name.trim();
  const ready = point && clean && !taken.includes(clean) && radius >= 20 && radius <= 50_000;

  return (
    <Dialog open={open} onClose={onClose} title={m.schedule_locations_add()} className="max-w-2xl">
      <form
        className="mt-3 space-y-3"
        onSubmit={(e) => {
          e.preventDefault();
          if (point && ready) {
            onSave({ name: clean, lat: point.lat, lon: point.lon, radius_m: radius, mode });
          }
        }}
      >
        <div className="flex gap-1.5">
          <input
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter") {
                e.preventDefault();
                void search();
              }
            }}
            placeholder={m.schedule_location_search()}
            aria-label={m.schedule_location_search()}
            className="h-9 min-w-0 flex-1 rounded-md border border-border bg-transparent px-2 text-sm"
          />
          <Button type="button" variant="outline" size="sm" onClick={() => void search()}>
            {m.schedule_location_search_go()}
          </Button>
        </div>
        {missing && (
          <p className="text-muted-foreground text-sm">{m.schedule_location_not_found()}</p>
        )}
        <PlaceMap
          centre={found ?? point ?? here ?? centre}
          here={here}
          point={point}
          radius={radius}
          onPick={setPoint}
        />
        <div className="flex items-center gap-2">
          <p className="min-w-0 flex-1 text-muted-foreground text-sm">
            {m.schedule_location_pick()}
          </p>
          <Button
            type="button"
            variant="outline"
            size="sm"
            disabled={!here}
            onClick={() => here && setPoint(here)}
          >
            {m.schedule_location_use_here()}
          </Button>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <input
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder={m.schedule_location_name()}
            aria-label={m.schedule_location_name()}
            className="h-9 min-w-0 flex-1 rounded-md border border-border bg-transparent px-2 text-sm"
          />
          <input
            type="number"
            min={20}
            max={50000}
            value={radius}
            onChange={(e) => setRadius(Number(e.target.value))}
            aria-label={m.schedule_location_radius()}
            className="h-9 w-24 rounded-md border border-border bg-transparent px-2 text-sm"
          />
          <span className="text-muted-foreground text-sm">{m.schedule_location_radius()}</span>
        </div>
        <Tabs
          value={mode}
          onChange={setMode}
          items={[
            { id: "Block" as const, label: m.schedule_location_block() },
            { id: "Skip" as const, label: m.schedule_location_skip() },
          ]}
        />
        <div className="flex justify-end">
          <Button type="submit" size="sm" disabled={!ready}>
            {m.schedule_location_save()}
          </Button>
        </div>
      </form>
    </Dialog>
  );
}

/** OpenStreetMap with one pin and its radius. ponytail: the tiles are the public server's; use a tile provider if the app ever ships widely. */
function PlaceMap({
  centre,
  here,
  point,
  radius,
  onPick,
}: {
  centre?: Point;
  here?: Point;
  point?: Point;
  radius: number;
  onPick: (p: Point) => void;
}) {
  const box = useRef<HTMLDivElement>(null);
  const map = useRef<L.Map | undefined>(undefined);
  const mark = useRef<{ pin: L.CircleMarker; area: L.Circle } | undefined>(undefined);
  const pick = useRef(onPick);
  pick.current = onPick;
  const dot = useRef<L.CircleMarker | undefined>(undefined);
  const first = useRef(centre);

  useEffect(() => {
    if (!box.current) return;
    const start = first.current;
    const m = L.map(box.current).setView(start ? [start.lat, start.lon] : [45, 10], start ? 15 : 4);
    L.tileLayer("https://tile.openstreetmap.org/{z}/{x}/{y}.png", {
      maxZoom: 19,
      attribution: "© OpenStreetMap",
    }).addTo(m);
    m.on("click", (e) => pick.current({ lat: e.latlng.lat, lon: e.latlng.lng }));
    map.current = m;
    // The dialog is shown after this effect runs, so the box has no size yet.
    const resize = requestAnimationFrame(() => m.invalidateSize());
    return () => {
      cancelAnimationFrame(resize);
      m.remove();
      map.current = undefined;
      mark.current = undefined;
      dot.current = undefined;
    };
    // Built once per dialog; later centre changes are handled below.
  }, []);

  // Compared by value: the parent builds a new object on every render, and following it would undo each pan.
  const [lat, lon] = [centre?.lat, centre?.lon];
  useEffect(() => {
    if (lat != null && lon != null) {
      map.current?.setView([lat, lon], Math.max(map.current.getZoom(), 15));
    }
  }, [lat, lon]);

  // Where the device is now: a blue dot, apart from the picked pin.
  useEffect(() => {
    const m = map.current;
    if (!m || !here) return;
    const at: L.LatLngTuple = [here.lat, here.lon];
    dot.current ??= L.circleMarker(at, {
      radius: 8,
      color: "#fff",
      weight: 2,
      fillColor: "#2563eb",
      fillOpacity: 1,
    }).addTo(m);
    dot.current.setLatLng(at);
  }, [here]);

  useEffect(() => {
    const m = map.current;
    if (!m || !point) return;
    const at: L.LatLngTuple = [point.lat, point.lon];
    if (!mark.current) {
      mark.current = {
        pin: L.circleMarker(at, { radius: 6 }).addTo(m),
        area: L.circle(at, { radius }).addTo(m),
      };
    }
    mark.current.pin.setLatLng(at);
    mark.current.area.setLatLng(at).setRadius(radius);
  }, [point, radius]);

  return <div ref={box} className="h-72 w-full rounded-md border border-border" />;
}
