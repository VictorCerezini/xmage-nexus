import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { HOUSE_SETTINGS, HOUSE_SETTINGS_KEY, HOUSE_SETTINGS_VERSION, applyHouseSettings } from './houseSettings'
import {
  APPEARANCE_KEY,
  AUDIO_SETTINGS_KEY,
  AUTO_ANSWERS_KEY,
  CHOICE_MEMORY_KEY,
  DECK_EDITOR_DRAWER_KEY,
  DECK_EDITOR_VIEW_KEY,
  DECK_STACKS_COPIES_KEY,
  DECK_STACKS_GROUP_KEY,
  DECK_STACKS_SCALE_KEY,
  DECK_STACKS_SEPARATE_CREATURES_KEY,
  DECK_STACKS_SORT_KEY,
  FX_SETTINGS_KEY,
  MANA_PAYMENT_KEY,
  MUSIC_SETTINGS_KEY,
  PHASE_STOPS_KEY,
  SMART_STOPS_KEY,
  SOUND_MUSIC_SPLIT_KEY,
  loadAppearanceSettings,
  loadAudioSettings,
  loadBoardRewind,
  loadFxSettings,
  loadHandRequestsAllowed,
  loadManaPayment,
  loadMusicSettings,
  loadSmartStops,
  loadTurnRecap,
} from '../state/persistence'
import { CUSTOM_DECKS_STORAGE_KEY } from '../lobby/decks'

/** What a player keeps whatever the house settings are: decks, login, art, answers, language, setup. */
const KEPT: Record<string, string> = {
  [CUSTOM_DECKS_STORAGE_KEY]: '[{"id":"d1","name":"Friend deck"}]',
  mage_decks_v2: '[{"id":"d2"}]',
  'mage-web-active-deck': '{"name":"Friend deck"}',
  'mage-web-equipped-deck-id': 'd1',
  'xmage-join-deck-by-format': '{"Commander":"d1"}',
  'mage-web-conn': '{"username":"friend"}',
  'mage-web-card-art-v1': '{"swamp":{"setCode":"LEA","cardNumber":"1"}}',
  [AUTO_ANSWERS_KEY]: '[{"pattern":"x","answer":true}]',
  [CHOICE_MEMORY_KEY]: '[{"k":"v"}]',
  nexus_lang: 'pt',
  nexus_card_lang: 'pt',
  nexus_setup_v: '1',
  mage_createTable_v1: '{"name":"Friday"}',
}

let store: Map<string, string>
let storage: Storage

function memoryStorage(failWrites = false): Storage {
  return {
    get length() { return store.size },
    key: (i: number) => [...store.keys()][i] ?? null,
    getItem: (k: string) => store.get(k) ?? null,
    setItem: (k: string, v: string) => {
      if (failWrites) throw new Error('QuotaExceededError')
      store.set(k, String(v))
    },
    removeItem: (k: string) => { store.delete(k) },
    clear: () => store.clear(),
  }
}

beforeEach(() => {
  store = new Map()
  storage = memoryStorage()
  vi.stubGlobal('localStorage', storage)
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('SET-14 house settings', () => {
  it('SET-14: a new player starts with the house settings', () => {
    expect(applyHouseSettings(storage)).toBe(true)
    for (const [key, value] of Object.entries(HOUSE_SETTINGS)) {
      expect(storage.getItem(key), key).toBe(value)
    }
    expect(storage.getItem(HOUSE_SETTINGS_KEY)).toBe(HOUSE_SETTINGS_VERSION)
    // the setup wizard still opens for them, with these settings in it
    expect(storage.getItem('nexus_setup_v')).toBeNull()
  })

  it('SET-14: a player with settings of their own gets the house ones once; decks and the rest stay', () => {
    for (const [k, v] of Object.entries(KEPT)) store.set(k, v)
    store.set(APPEARANCE_KEY, '{"appearance":"dark","uiScale":0.9,"boardLayout":"stack"}')
    store.set(FX_SETTINGS_KEY, '{"effects":true,"animationSpeed":1.5}')
    store.set('mage-web-turn-recap', 'true')
    store.set(DECK_EDITOR_VIEW_KEY, '"stacks"')
    store.set('mage-web-gameplay-preset', 'fast')

    expect(applyHouseSettings(storage)).toBe(true)

    expect(storage.getItem(APPEARANCE_KEY)).toBe(HOUSE_SETTINGS[APPEARANCE_KEY])
    expect(storage.getItem(FX_SETTINGS_KEY)).toBe('{"effects":false,"animationSpeed":1}')
    // what the host never changed goes back to the app's default
    expect(storage.getItem('mage-web-turn-recap')).toBeNull()
    expect(storage.getItem(DECK_EDITOR_VIEW_KEY)).toBeNull()
    expect(storage.getItem('mage-web-gameplay-preset')).toBeNull()
    for (const [k, v] of Object.entries(KEPT)) {
      expect(storage.getItem(k), k).toBe(v)
    }
  })

  it('SET-14: only once: what a player changes afterwards stays', () => {
    applyHouseSettings(storage)
    store.set(APPEARANCE_KEY, '{"appearance":"light"}')
    store.set(FX_SETTINGS_KEY, '{"effects":true,"animationSpeed":1}')
    expect(applyHouseSettings(storage)).toBe(false)
    expect(storage.getItem(APPEARANCE_KEY)).toBe('{"appearance":"light"}')
    expect(storage.getItem(FX_SETTINGS_KEY)).toBe('{"effects":true,"animationSpeed":1}')
  })

  it('SET-14: a browser that cannot save starts anyway, and tries again next time', () => {
    const failing = memoryStorage(true)
    expect(() => applyHouseSettings(failing)).not.toThrow()
    expect(applyHouseSettings(failing)).toBe(false)
    expect(failing.getItem(HOUSE_SETTINGS_KEY)).toBeNull()
  })

  it('SET-14: the app reads them as the host has them, with animation effects off', () => {
    applyHouseSettings(storage)
    expect(loadFxSettings()).toEqual({ effects: false, animationSpeed: 1 })
    const look = loadAppearanceSettings()
    expect(look.uiScale).toBe(1.15)
    expect(look.boardLayout).toBe('pod')
    expect(loadAudioSettings().masterVolume).toBe(0.2)
    expect(loadMusicSettings().musicEnabled).toBe(false)
    expect(loadSmartStops()).toBe(true)
    expect(loadManaPayment().auto).toBe(true)
    expect(loadTurnRecap()).toBe(false)
    expect(loadBoardRewind()).toBe(false)
    expect(loadHandRequestsAllowed()).toBe(true)
  })

  it('SET-14: animation effects are off by default even where nothing could be saved', () => {
    expect(loadFxSettings().effects).toBe(false)
  })

  it('SET-14: the keys are the ones the app saves its settings under, and none it keeps for the player', () => {
    for (const key of [
      FX_SETTINGS_KEY, APPEARANCE_KEY, AUDIO_SETTINGS_KEY, MUSIC_SETTINGS_KEY, SOUND_MUSIC_SPLIT_KEY,
      MANA_PAYMENT_KEY, PHASE_STOPS_KEY, SMART_STOPS_KEY, DECK_STACKS_SCALE_KEY, DECK_EDITOR_VIEW_KEY,
      DECK_STACKS_GROUP_KEY, DECK_STACKS_SORT_KEY, DECK_STACKS_COPIES_KEY, DECK_EDITOR_DRAWER_KEY,
      DECK_STACKS_SEPARATE_CREATURES_KEY,
    ]) {
      expect(Object.hasOwn(HOUSE_SETTINGS, key), key).toBe(true)
    }
    for (const key of Object.keys(KEPT)) {
      expect(Object.hasOwn(HOUSE_SETTINGS, key), key).toBe(false)
    }
  })

  it('SET-14: the page puts them in before anything else loads', () => {
    // the tests run from web/ (the jsdom environment has no file: URL for this module)
    const main = readFileSync(join(process.cwd(), 'src', 'main.tsx'), 'utf8')
    const firstImport = main.split(/\r?\n/).find((line) => line.startsWith('import '))
    expect(firstImport).toBe("import './setup/applyHouseSettings'")
  })
})
