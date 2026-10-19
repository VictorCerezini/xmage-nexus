import { beforeEach } from 'vitest'
import { ensureLocaleLoaded, setLanguage } from './i18n'
import { resetScryfallClient, setScryfallPacing } from './cards/scryfallClient'

function preferJsdomStorageOverNodeGlobal() {
  const dom = (globalThis as unknown as { jsdom?: { window: Window } }).jsdom
  if (!dom) return
  for (const name of ['localStorage', 'sessionStorage'] as const) {
    if (typeof globalThis[name]?.getItem !== 'function') {
      Object.defineProperty(globalThis, name, { value: dom.window[name], configurable: true, writable: true })
    }
  }
}

preferJsdomStorageOverNodeGlobal()

// The historical suite asserts Spanish UI text. The app defaults to English
// (getInitialLanguage), so tests start in Spanish unless the spec itself
// switches language with setLanguage().
//
// Each locale is its own chunk since they load on demand, so setting the
// language is not enough: its schema has to be in memory before rendering, or
// `t()` falls back to English and the spec fails on the wrong text.
beforeEach(async () => {
  setLanguage('es')
  await ensureLocaleLoaded('es')
  resetScryfallClient()
  setScryfallPacing({ spacingMs: 0 })
})
