/**
 * SET-14: every player starts with the host's settings of 2026-11-21, and a browser that already had
 * its own gets them once. Only settings: decks, logins, card art picks, remembered answers and the
 * language are never touched. "Animation effects" is the one exception and stays off: on a large
 * board, with it on the browser never stops redrawing (about 250% CPU when idle, 4% with it off).
 *
 * Made by reading the host's saved settings, so the values are stored exactly as the app writes
 * them. A new copy needs a new version, so every browser gets it once again.
 */
export const HOUSE_SETTINGS_VERSION = '1'
export const HOUSE_SETTINGS_KEY = 'nexus_house_settings_v'

/** Saved value per key; null: not saved, so the app's default applies (the host never changed it). */
export const HOUSE_SETTINGS: Readonly<Record<string, string | null>> = {
  'mage-web-settings': '{"effects":false,"animationSpeed":1}',
  'mage-web-appearance': '{"appearance":"system","sleeveId":"classic","boardLayout":"pod","boardLayoutManual":true,"uiScale":1.15,"cjkBoost":true,"transparentDialogs":false,"playmatId":"classic","cardStyle":"classic","tapStyle":"sideways","showHandCost":true,"showManaSource":false,"ptBadgeMode":"always","sicknessStyle":"badge","keywordIcons":"coins","keywordAura":"subtle","cardRender":"image","handRail":false,"handRailWidth":260,"handRow":false,"boardFill":true,"boardSpotlight":false,"allBoardsView":true,"boardSpotlightPanel":22,"stackCaption":true,"gameUi":"v1","cardPreviewScale":1}',
  'mage-web-audio': '{"soundEnabled":true,"masterVolume":0.2,"sfxVolume":0.2,"uiVolume":0.2,"prioritySound":"every-prompt"}',
  'mage-web-music': '{"musicEnabled":false,"musicVolume":0.35}',
  'mage-web-sound-music-split': 'true',
  'mage-web-mana-payment': '{"auto":true,"restricted":false,"useFirstAbility":false,"confirmEmptyPool":false,"smart":false}',
  'mage-web-phase-stops': '{"yourTurn":{"upkeep":false,"draw":false,"main1":false,"beginCombat":false,"endCombat":false,"main2":false,"endStep":false},"opponentTurn":{"upkeep":false,"draw":false,"main1":false,"beginCombat":false,"endCombat":false,"main2":false,"endStep":false}}',
  'mage-web-smart-stops': 'true',
  'nexus_decks_density': 'comfortable',
  'tables_density': 'compact',
  'xmage.import.prefs.v1': '{"strategy":"keep","setCode":""}',
  'mage-web-gameplay-preset': null,
  'mage-web-turn-recap': null,
  'mage-web-board-rewind': null,
  'mage-web-browser-notifications': null,
  'mage-web-game-log': null,
  'mage-web-draft-helper': null,
  'mage-web-hand-requests': null,
  'mage-web-deck-editor-view': null,
  'mage-web-deck-editor-drawer': null,
  'mage-web-deck-stacks-scale': null,
  'mage-web-deck-stacks-group': null,
  'mage-web-deck-stacks-sort': null,
  'mage-web-deck-stacks-copies': null,
  'mage-web-deck-stacks-separate-creatures': null,
}

/**
 * Puts the house settings in this browser unless it already got this version. Nothing else is
 * touched. A write that fails leaves the version unmarked, so it's tried again on the next start.
 *
 * @returns true if they were put in now
 */
export function applyHouseSettings(storage: Storage): boolean {
  try {
    if (storage.getItem(HOUSE_SETTINGS_KEY) === HOUSE_SETTINGS_VERSION) return false
    for (const [key, value] of Object.entries(HOUSE_SETTINGS)) {
      if (value === null) storage.removeItem(key)
      else storage.setItem(key, value)
    }
    storage.setItem(HOUSE_SETTINGS_KEY, HOUSE_SETTINGS_VERSION)
    return true
  } catch {
    return false
  }
}
