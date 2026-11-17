import { t as tStatic } from '../i18n'
import { confirmDialog, hasConfirmHost } from '../ui/confirmDialog'

/** The event the desktop app sends when its window's close button is clicked (see the launcher). */
export const CLOSE_REQUESTED_EVENT = 'nexus-close-requested'

type Invoke = (command: string) => Promise<unknown>

function desktopInvoke(): Invoke | null {
  const tauri = (window as { __TAURI__?: { core?: { invoke?: Invoke } } }).__TAURI__
  return tauri?.core?.invoke ?? null
}

/**
 * The app's own dialog. With the app's screen gone (a crash) there is nothing to ask with (the
 * macOS webview has no native dialogs), and nothing left to lose: it closes, as it did before.
 */
function askToClose(): Promise<boolean> {
  if (!hasConfirmHost()) return Promise.resolve(true)
  return confirmDialog(tStatic('common', 'close_app_confirm'), {
    title: tStatic('common', 'close_app_title'),
    okLabel: tStatic('common', 'close_app_ok'),
    danger: true,
  })
}

/**
 * In the desktop app, the window's close button asks before closing: the app quits only on
 * "Close". In a browser there is no such window, and nothing changes. Returns the cleanup.
 */
export function installCloseGuard(): () => void {
  const invoke = desktopInvoke()
  if (!invoke) return () => {}
  let asking = false
  const onCloseRequested = async () => {
    // a second click while the question is up keeps that one question
    if (asking) return
    asking = true
    try {
      if (await askToClose()) await invoke('quit_app')
    } finally {
      asking = false
    }
  }
  window.addEventListener(CLOSE_REQUESTED_EVENT, onCloseRequested)
  // from now on the launcher leaves the close to this question
  invoke('ask_before_close').catch(() => undefined)
  return () => window.removeEventListener(CLOSE_REQUESTED_EVENT, onCloseRequested)
}
