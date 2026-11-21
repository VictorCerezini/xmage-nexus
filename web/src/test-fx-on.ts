// Runs before src/test-setup.ts and before any module reads the settings. The effect tests were
// written with animation effects on, the default until SET-14 turned it off, so every test file
// starts as a browser that has them on. A test about the default clears or stubs the storage first.
const dom = (globalThis as unknown as { jsdom?: { window: Window } }).jsdom
try {
  dom?.window.localStorage.setItem('mage-web-settings', JSON.stringify({ effects: true, animationSpeed: 1 }))
} catch {}
