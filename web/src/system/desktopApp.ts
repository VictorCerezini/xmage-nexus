/** A command of the desktop app (the launcher's `#[tauri::command]`s), with its named arguments. */
export type DesktopInvoke = (command: string, args?: Record<string, unknown>) => Promise<unknown>

/** The desktop app's command bridge, or null in a browser (a friend's included), where there is none. */
export function desktopInvoke(): DesktopInvoke | null {
  const tauri = (window as { __TAURI__?: { core?: { invoke?: DesktopInvoke } } }).__TAURI__
  return tauri?.core?.invoke ?? null
}
