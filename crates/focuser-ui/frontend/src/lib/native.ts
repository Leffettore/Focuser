/**
 * The handful of things the desktop shell does that the command core cannot:
 * file dialogs, the updater, launching a browser.
 *
 * Each one is unavailable in the browser harness, so callers check `isTauri()`
 * and hide or disable the control rather than letting it fail at click time.
 */

import { invoke } from "@tauri-apps/api/core";
import { isTauri } from "./transport";

export { isTauri };

/** Pick an executable to block. Returns its file name, or null if cancelled. */
export function pickApplication(): Promise<string | null> {
  return invoke<string | null>("pick_app_file");
}

/** Pick a configuration file and return its contents, or null if cancelled. */
export function pickConfigurationFile(): Promise<string | null> {
  return invoke<string | null>("pick_import_file");
}

/** Save an exported configuration. Returns the path, or null if cancelled. */
export function saveConfiguration(json: string): Promise<string | null> {
  return invoke<string | null>("save_configuration", { json });
}

/** Pick an audio file for the phase chime. Returns its path, or null if cancelled. */
export function pickSound(): Promise<string | null> {
  return invoke<string | null>("pick_sound_file");
}

/** Play the phase chime once, as currently configured. */
export function previewSound(): Promise<void> {
  return invoke("preview_sound");
}

export function openInBrowser(browser: string, url: string): Promise<void> {
  return invoke("open_browser_url", { browser, url });
}

export interface UpdateCheck {
  available: boolean;
  version?: string;
  body?: string;
}

export function checkForUpdate(): Promise<UpdateCheck> {
  return invoke<UpdateCheck>("check_for_update");
}

export function installUpdate(): Promise<void> {
  return invoke("do_update");
}

/**
 * The Android app injects this into its WebView: blocked apps are per device,
 * so the phone keeps them itself and the page asks rather than the server.
 */
interface AndroidBridge {
  blockedApps(listId: string): string;
  removeApp(listId: string, id: string): void;
  pickApps(listId: string): void;
  /** `{"lat":..,"lon":..}` of the phone's last fix, or "" when it has none. */
  here(): string;
  hasUsageAccess(): boolean;
  openUsageAccess(): void;
}

export interface DeviceApp {
  id: string;
  label: string;
  /** The launcher icon as a data URL; missing once the app is uninstalled. */
  icon?: string;
}

/** Fired by the Android app after its app picker saves. */
export const DEVICE_APPS_EVENT = "focuser-apps";

function androidBridge(): AndroidBridge | undefined {
  return (window as unknown as { FocuserAndroid?: AndroidBridge }).FocuserAndroid;
}

export const isAndroidApp = () => androidBridge() !== undefined;

export const deviceApps = (listId: string): DeviceApp[] =>
  JSON.parse(androidBridge()?.blockedApps(listId) ?? "[]");
export const removeDeviceApp = (listId: string, id: string) =>
  androidBridge()?.removeApp(listId, id);
export const pickDeviceApps = (listId: string) => androidBridge()?.pickApps(listId);

/** Where the phone last was, to open the place map there. */
export const deviceHere = (): { lat: number; lon: number } | undefined => {
  const here = androidBridge()?.here();
  return here ? JSON.parse(here) : undefined;
};
export const hasUsageAccess = () => androidBridge()?.hasUsageAccess() ?? true;
export const openUsageAccess = () => androidBridge()?.openUsageAccess();
