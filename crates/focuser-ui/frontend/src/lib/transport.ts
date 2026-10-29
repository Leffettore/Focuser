/**
 * Command transport.
 *
 * The same UI runs in two places, and both drive the *same* Rust `execute()`:
 *
 *   - Inside the Tauri webview  → `invoke()` over IPC
 *   - In an ordinary browser    → HTTP POST to `focuser-devserver`
 *
 * The browser path exists so the app can be exercised by normal web tooling —
 * devtools, browser automation — instead of only by hand-clicking a desktop
 * window. Because both paths terminate in the same command core, what you test
 * in the browser is the real backend, not a mock that drifts.
 */

import { invoke } from "@tauri-apps/api/core";
import type { CommandErrorPayload } from "@/bindings";

const DEVSERVER_URL = import.meta.env.VITE_DEVSERVER_URL ?? "http://localhost:17550";

export type { CommandErrorPayload };

/** A failed command. `code` is stable and safe to branch on; `message` is prose. */
export class CommandError extends Error {
  readonly code: string;

  constructor({ code, message }: CommandErrorPayload) {
    super(message);
    this.name = "CommandError";
    this.code = code;
  }
}

/**
 * True when running inside the Tauri webview.
 *
 * Tauri injects `__TAURI_INTERNALS__` before any app code runs, so this is
 * settled by first paint — no async probing, no race.
 */
export function isTauri(): boolean {
  return typeof window !== "undefined" && "__TAURI_INTERNALS__" in window;
}

/** Which backend this session is talking to. Surfaced in the UI while developing. */
export const transportKind: "tauri" | "devserver" = isTauri() ? "tauri" : "devserver";

/**
 * Send one command and return its result.
 *
 * @throws {CommandError} when the backend rejects the command.
 */
export async function send<TResult>(command: unknown): Promise<TResult> {
  if (isTauri()) return sendViaTauri<TResult>(command);
  return typeof androidBridge()?.command === "function" ? sendViaAndroid<TResult>(command) : sendViaHttp<TResult>(command);
}

interface AndroidCommandBridge {
  command(id: number, json: string): void;
}

const androidBridge = () =>
  (window as unknown as { FocuserAndroid?: AndroidCommandBridge }).FocuserAndroid;

const pending = new Map<number, (status: number, body: string) => void>();
let nextId = 0;

/** The Android app answers here once its request to the server (or its saved copy) is ready. */
(window as unknown as { __focuserCommand: typeof answer }).__focuserCommand = answer;
function answer(id: number, status: number, body: string) {
  pending.get(id)?.(status, body);
  pending.delete(id);
}

/** The phone makes the request itself, so it can fall back to its last saved answer when offline. */
function sendViaAndroid<TResult>(command: unknown): Promise<TResult> {
  return new Promise((resolve, reject) => {
    const id = nextId++;
    pending.set(id, (status, body) => {
      if (status === 0) {
        reject(
          new CommandError({
            code: "offline",
            message: "The server can't be reached. Turn Tailscale on to make changes.",
          }),
        );
      } else if (status >= 400) {
        reject(toCommandError(JSON.parse(body)));
      } else {
        resolve(JSON.parse(body) as TResult);
      }
    });
    androidBridge()?.command(id, JSON.stringify(command));
  });
}

async function sendViaTauri<TResult>(command: unknown): Promise<TResult> {
  try {
    return (await invoke("run_command", { command })) as TResult;
  } catch (raw) {
    throw toCommandError(raw);
  }
}

async function sendViaHttp<TResult>(command: unknown): Promise<TResult> {
  let response: Response;
  try {
    response = await fetch(`${DEVSERVER_URL}/command`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(command),
    });
  } catch {
    // Overwhelmingly the cause is "the dev server isn't running", so say that
    // rather than surfacing a bare `TypeError: Failed to fetch`.
    throw new CommandError({
      code: "transport",
      message: `Cannot reach focuser-devserver at ${DEVSERVER_URL}. Start it with: cargo run -p focuser-devserver`,
    });
  }

  const body = await response.json().catch(() => null);

  if (!response.ok) {
    throw toCommandError(body ?? { code: "transport", message: `HTTP ${response.status}` });
  }

  return body as TResult;
}

function toCommandError(raw: unknown): CommandError {
  if (raw instanceof CommandError) return raw;

  if (typeof raw === "object" && raw !== null && "code" in raw && "message" in raw) {
    const { code, message } = raw as CommandErrorPayload;
    return new CommandError({ code: String(code), message: String(message) });
  }

  return new CommandError({ code: "unknown", message: String(raw) });
}
