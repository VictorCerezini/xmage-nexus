// SET-14: main.tsx imports this first. The settings are read while the app's modules load, so the
// house settings have to be in place before any of them is.
import { applyHouseSettings } from './houseSettings'

try {
  applyHouseSettings(window.localStorage)
} catch {}
