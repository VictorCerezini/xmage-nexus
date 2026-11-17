import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { getConfirmQueue, resolveConfirm, subscribeConfirm } from '../ui/confirmDialog'
import { CLOSE_REQUESTED_EVENT, installCloseGuard } from './closeGuard'

type Win = { __TAURI__?: unknown }

describe('installCloseGuard', () => {
  let invoke: ReturnType<typeof vi.fn>
  let cleanup: () => void = () => {}
  let unsubscribeHost: () => void = () => {}

  beforeEach(() => {
    invoke = vi.fn(() => Promise.resolve())
    ;(window as Win).__TAURI__ = { core: { invoke } }
  })

  afterEach(() => {
    cleanup()
    unsubscribeHost()
    for (const r of getConfirmQueue()) resolveConfirm(r.id, false)
    delete (window as Win).__TAURI__
    vi.restoreAllMocks()
  })

  const askToClose = async () => {
    window.dispatchEvent(new Event(CLOSE_REQUESTED_EVENT))
    await Promise.resolve()
  }

  it('in a browser, changes nothing', () => {
    delete (window as Win).__TAURI__
    cleanup = installCloseGuard()
    expect(invoke).not.toHaveBeenCalled()
  })

  it('tells the desktop app to ask before its window closes', () => {
    cleanup = installCloseGuard()
    expect(invoke).toHaveBeenCalledWith('ask_before_close')
  })

  it('quits only on Close, and a second click keeps the one question', async () => {
    unsubscribeHost = subscribeConfirm(() => {})
    cleanup = installCloseGuard()
    await askToClose()
    await askToClose()
    expect(getConfirmQueue()).toHaveLength(1)
    resolveConfirm(getConfirmQueue()[0].id, false)
    await vi.waitFor(() => expect(getConfirmQueue()).toHaveLength(0))
    expect(invoke).not.toHaveBeenCalledWith('quit_app')

    await askToClose()
    resolveConfirm(getConfirmQueue()[0].id, true)
    await vi.waitFor(() => expect(invoke).toHaveBeenCalledWith('quit_app'))
  })

  it("closes without asking when the app's screen is gone", async () => {
    cleanup = installCloseGuard()
    await askToClose()
    expect(getConfirmQueue()).toHaveLength(0)
    await vi.waitFor(() => expect(invoke).toHaveBeenCalledWith('quit_app'))
  })
})
