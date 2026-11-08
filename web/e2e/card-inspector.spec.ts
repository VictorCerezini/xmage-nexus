import { fakeOnly } from './support/fake-mode'
import { test, expect } from './fixtures'
fakeOnly()
import { quickStartGame } from './support/start-game'
import { withFakeServer } from './support/fake-backend'
import { cardInspectorScenario } from '../fixtures/scenarios/cardInspector'
import { SIM_PLAYER_ID } from '../fixtures/humanGameConstants'
import type { PermanentView } from '../src/net/types'
import type { Locator, Page } from '@playwright/test'

const LOCALIZED = {
  name: 'The One Ring',
  printed_name: 'El Anillo Único',
  printed_type_line: 'Artefacto legendario',
  printed_text: 'Indestructible.\nCuando El Anillo Único entre al campo de batalla, obtienes protección contra todo hasta tu próximo turno.',
  image_uris: { normal: 'https://cards.scryfall.io/normal/front/t/o/ring.svg' },
}

const NO_TEXT = {
  name: 'The One Ring',
  image_uris: { normal: 'https://cards.scryfall.io/normal/front/t/o/ring.svg' },
}

const svg = (w: number, h: number, fill: string, label: string) =>
  `<svg xmlns="http://www.w3.org/2000/svg" width="${w}" height="${h}"><rect width="100%" height="100%" fill="${fill}"/>` +
  `<text x="50%" y="50%" font-size="${Math.round(h / 8)}" text-anchor="middle" fill="#000">${label}</text></svg>`

async function stubScryfall(page: Page, payload: unknown = LOCALIZED): Promise<void> {
  await page.route('**/api.scryfall.com/cards/**', (route) => route.fulfill({ json: payload }))
  await page.route('**/cards.scryfall.io/**', (route) =>
    route.fulfill({ contentType: 'image/svg+xml', body: svg(488, 680, '#9ecbff', 'CARD') }),
  )
}

test.describe('Card inspector (right click)', { tag: '@game' }, () => {
  test('opens the full-size card with localized text and closes with Esc', async ({ page }) => {
    await page.addInitScript(() => {
      try {
        localStorage.setItem('nexus_card_lang', 'es')
      } catch {}
    })
    await stubScryfall(page)
    await withFakeServer(cardInspectorScenario, async () => {
      const { pageErrors } = await quickStartGame(page, {
        prefix: 'insp',
      })

      const slot = page.locator('.player-zone .card-slot[data-card-name="The One Ring"]').first()
      await expect(slot).toBeVisible({ timeout: 15_000 })
      await slot.click({ button: 'right' })

      const inspector = page.locator('.card-inspector')
      await expect(inspector).toBeVisible({ timeout: 5_000 })
      await expect(page.locator('.card-inspector-img')).toHaveAttribute('src', /\/large\//)
      await expect(inspector).toContainText('El Anillo Único')
      await expect(inspector).toContainText('Artefacto legendario')
      await expect(page.locator('.card-inspector-rules')).toContainText('Indestructible.')
      await expect(page.locator('.card-inspector-note')).toHaveCount(0)
      await page.screenshot({ path: test.info().outputPath('card-inspector.png') })

      await page.keyboard.press('Escape')
      await expect(inspector).toBeHidden()

      expect(pageErrors, `pageerrors: ${pageErrors.map(String).join(' | ')}`).toEqual([])
    })
  })

  test('falls back to the engine rules when Scryfall has no localized text', async ({ page }) => {
    await stubScryfall(page, NO_TEXT)
    await withFakeServer(cardInspectorScenario, async () => {
      const { pageErrors } = await quickStartGame(page, {
        prefix: 'insp2',
      })

      const slot = page.locator('.player-zone .card-slot[data-card-name="The One Ring"]').first()
      await expect(slot).toBeVisible({ timeout: 15_000 })
      await slot.click({ button: 'right' })

      const inspector = page.locator('.card-inspector')
      await expect(inspector).toBeVisible({ timeout: 5_000 })
      await expect(inspector).toContainText('The One Ring')
      await expect(page.locator('.card-inspector-rules')).toContainText('Indestructible.')
      await expect(page.locator('.card-inspector-note')).toHaveCount(1)
      await expect(page.locator('.card-inspector-img')).toHaveAttribute('src', /\/large\//)

      expect(pageErrors, `pageerrors: ${pageErrors.map(String).join(' | ')}`).toEqual([])
    })
  })

  async function stubEngineText(page: Page): Promise<void> {
    await page.route('**/api.scryfall.com/**', (route) => route.fulfill({ status: 404, json: { object: 'error' } }))
    await page.route('**/cards.scryfall.io/**', (route) =>
      route.fulfill({ contentType: 'image/svg+xml', body: svg(488, 680, '#9ecbff', 'CARD') }),
    )
  }

  async function inspect(page: Page, name: string) {
    const slot = page.locator(`.player-zone .card-slot[data-card-name="${name}"]`).first()
    await expect(slot).toBeVisible({ timeout: 15_000 })
    await slot.click({ button: 'right' })
    const inspector = page.locator('.card-inspector')
    await expect(inspector).toBeVisible({ timeout: 5_000 })
    return inspector
  }

  test('CRD-1: shows the battlefield state the printed card cannot, and opens attachments', async ({ page }) => {
    await stubEngineText(page)
    await withFakeServer(cardInspectorScenario, async () => {
      const { pageErrors } = await quickStartGame(page, { prefix: 'insp3' })

      const inspector = await inspect(page, 'Tarmogoyf')
      const pt = inspector.locator('[data-stat="pt"]')
      await expect(pt).toContainText('6/7')
      await expect(pt).toContainText('0/1')
      await expect(pt.locator('.card-inspector-pt-up')).toHaveCount(2)
      await expect(inspector.locator('[data-stat="damage"]')).toContainText('2')
      await expect(inspector.locator('[data-counter="+1/+1"]')).toContainText('×2')
      await expect(inspector.locator('[data-status="tapped"]')).toBeVisible()
      await expect(inspector.locator('[data-status="owner"]')).toBeVisible()

      await inspector.locator('.card-inspector-link', { hasText: 'Sword of Fire and Ice' }).click()
      await expect(inspector).toHaveAttribute('aria-label', 'Sword of Fire and Ice')
      await expect(inspector.locator('.card-inspector-link', { hasText: 'Tarmogoyf' })).toBeVisible()

      await page.keyboard.press('Escape')
      await expect(inspector).toBeHidden()
      expect(pageErrors, `pageerrors: ${pageErrors.map(String).join(' | ')}`).toEqual([])
    })
  })

  test('CRD-1, CRD-2, CRD-4: flips a double-faced card with a tap and leaves out the unchanged 1/1', async ({ page }) => {
    await stubEngineText(page)
    await withFakeServer(cardInspectorScenario, async () => {
      const { pageErrors } = await quickStartGame(page, { prefix: 'insp4' })

      const inspector = await inspect(page, 'Delver of Secrets')
      await expect(inspector.locator('[data-stat]')).toHaveCount(0)
      await expect(inspector.locator('[data-status="sick"]')).toBeVisible()

      const faces = inspector.locator('.card-inspector-face')
      await expect(faces.first()).toHaveAttribute('aria-pressed', 'true')
      await expect(faces.first().locator('.card-inspector-face-dot')).toHaveCount(1)
      await faces.nth(1).click()
      await expect(inspector.locator('.card-inspector-name')).toHaveText('Insectile Aberration')
      await expect(faces.nth(1)).toHaveAttribute('aria-pressed', 'true')
      await expect(inspector.locator('.card-inspector-state')).toHaveCount(0)
      await expect(inspector.locator('.card-inspector-keyword')).toHaveCount(1)

      await page.keyboard.press('f')
      await expect(inspector.locator('.card-inspector-name')).toHaveText('Delver of Secrets')
      expect(pageErrors, `pageerrors: ${pageErrors.map(String).join(' | ')}`).toEqual([])
    })
  })

  test('CRD-1, CRD-4: shows a planeswalker loyalty off its start and dims the abilities it cannot pay', async ({ page }) => {
    await stubEngineText(page)
    await withFakeServer(cardInspectorScenario, async () => {
      const { pageErrors } = await quickStartGame(page, { prefix: 'insp5' })

      const inspector = await inspect(page, 'Jace, the Mind Sculptor')
      await expect(inspector.locator('[data-stat="loyalty"]')).toContainText('5')
      await expect(inspector.locator('.card-inspector-cost')).toHaveText(['+2', '0', '\u22121', '\u221212'])
      await expect(inspector.locator('.card-inspector-ability.is-unaffordable')).toHaveCount(1)
      await expect(inspector.locator('.card-inspector-ability.is-unaffordable')).toContainText('Exile all cards')
      expect(pageErrors, `pageerrors: ${pageErrors.map(String).join(' | ')}`).toEqual([])
    })
  })

  /** A Scryfall printing whose image is named after it. */
  const print = (set: string, num: string, setName: string) => ({
    id: `p-${set}-${num}`, set, set_name: setName, collector_number: num, released_at: '2023-06-23', rarity: 'rare',
    image_uris: { normal: `https://cards.scryfall.io/normal/front/${set}-${num}.svg` },
  })

  /**
   * Two printings for the art menu; other Scryfall lookups have no text, and every image is a stub.
   * `search`: the printings for a search (its decoded URL), null for Scryfall's 404 when none match.
   */
  async function stubPrintings(page: Page, search?: (url: string) => ReturnType<typeof print>[] | null): Promise<void> {
    const prints = { data: [print('ltr', '451', 'Tales of Middle-earth'), print('ltc', '380', 'Middle-earth Commander')] }
    await page.route('**/api.scryfall.com/**', (route) => {
      const url = route.request().url()
      if (url.includes('/cards/search')) {
        if (!search) return route.fulfill({ json: prints })
        const found = search(decodeURIComponent(url))
        return found ? route.fulfill({ json: { data: found } }) : route.fulfill({ status: 404, json: { object: 'error', code: 'not_found' } })
      }
      // a printing lookup: its image is named after the printing, so the test can tell which one shows
      const printing = /\/cards\/([a-z0-9]+)\/([a-z0-9]+)(?:\/|$)/i.exec(new URL(url).pathname)
      if (printing && printing[1] !== 'named') {
        const [, set, num] = printing
        return route.fulfill({
          json: { name: 'The One Ring', image_uris: { normal: `https://cards.scryfall.io/normal/front/${set.toLowerCase()}-${num}.svg` } },
        })
      }
      return route.fulfill({ status: 404, json: { object: 'error' } })
    })
    await page.route('**/cards.scryfall.io/**', (route) =>
      route.fulfill({ contentType: 'image/svg+xml', body: svg(488, 680, '#9ecbff', 'CARD') }),
    )
  }

  /** The saved art pick for a card name, as the deck builder and the game read it. */
  const savedPick = (page: Page, name: string) =>
    page.evaluate((n) => JSON.parse(localStorage.getItem('mage-web-card-art-v1') ?? '{}')[n.toLowerCase()] ?? null, name)

  /** Right-clicks the card, closing the inspector first if it is open on another one. */
  async function inspectSlot(page: Page, slot: Locator) {
    const inspector = page.locator('.card-inspector')
    if (await inspector.isVisible()) {
      await page.keyboard.press('Escape')
      await expect(inspector).toBeHidden()
    }
    await expect(slot).toBeVisible({ timeout: 15_000 })
    await slot.click({ button: 'right' })
    await expect(inspector).toBeVisible()
  }

  /** Opens the inspector on the card and its art menu. */
  async function openArtMenu(page: Page, slot: Locator) {
    await inspectSlot(page, slot)
    await page.locator('.card-inspector-customize').click()
    const menu = page.locator('.printings-modal')
    await expect(menu.locator('.printing-card-item')).toHaveCount(2, { timeout: 10_000 })
    return menu
  }

  test('CRD-8, CRD-10: the art picked during the match changes only that card, and can go back to its default', async ({ page }) => {
    await stubPrintings(page)
    const ring = (id: string, controllerId?: string) => ({
      id, name: 'The One Ring', displayName: 'The One Ring', expansionSetCode: 'LTR', cardNumber: '246',
      cardTypes: ['Artifact'], superTypes: ['Legendary'], rules: ['Indestructible.'], ...(controllerId ? { controllerId } : {}),
    }) as unknown as PermanentView
    const scenario = () => cardInspectorScenario({
      myExtraBattlefield: { ring2: ring('ring2') },
      opponentBattlefield: { 'ring-sim': ring('ring-sim', SIM_PLAYER_ID), 'ring-sim2': ring('ring-sim2', SIM_PLAYER_ID) },
    })
    await withFakeServer(scenario, async () => {
      const { pageErrors } = await quickStartGame(page, { prefix: 'insp-art' })
      const slot = (id: string) => page.locator(`.card-slot[data-card-id="${id}"]`).first()
      const inspector = page.locator('.card-inspector')
      const restore = page.locator('.printings-restore')
      const inspectorImg = page.locator('.card-inspector-img')

      // my own copy: nothing changed yet, so no way back is offered
      let menu = await openArtMenu(page, slot('ring1'))
      await expect(restore).toHaveCount(0)
      await menu.locator('.printing-card-item', { hasText: 'Middle-earth Commander' }).click()
      // only the menu closes: the inspector stays on the card, now showing that printing
      await expect(menu).toBeHidden()
      await expect(inspector).toBeVisible()
      await expect(inspectorImg).toHaveAttribute('src', /ltc-380/)
      // only that copy changes: my other Ring keeps its art, and the deck's art pick is untouched
      expect(await savedPick(page, 'The One Ring')).toBeNull()
      await inspectSlot(page, slot('ring2'))
      await expect(inspectorImg).toHaveAttribute('src', /ltr-246/)

      menu = await openArtMenu(page, slot('ring1'))
      await expect(restore).toBeVisible()
      await expect(restore.locator('.printings-restore-hint')).not.toBeEmpty()
      await page.screenshot({ path: test.info().outputPath('art-restore-own.png') })
      await restore.locator('button').click()
      await expect(page.locator('.printings-modal')).toBeHidden()
      // the deck's own printing again
      await expect(inspectorImg).toHaveAttribute('src', /ltr-246/)
      menu = await openArtMenu(page, slot('ring1'))
      await expect(restore).toHaveCount(0)
      await menu.locator('.printings-close-btn').click()
      await expect(menu).toBeHidden()
      await expect(inspector).toBeVisible()

      // the opponent's copy: a change only for this screen and only for that copy
      menu = await openArtMenu(page, slot('ring-sim'))
      await expect(restore).toHaveCount(0)
      await menu.locator('.printing-card-item', { hasText: 'Tales of Middle-earth' }).click()
      await expect(menu).toBeHidden()
      await expect(inspectorImg).toHaveAttribute('src', /ltr-451/)
      expect(await savedPick(page, 'The One Ring')).toBeNull()
      await inspectSlot(page, slot('ring-sim2'))
      await expect(inspectorImg).toHaveAttribute('src', /ltr-246/)
      await inspectSlot(page, slot('ring1'))
      await expect(inspectorImg).toHaveAttribute('src', /ltr-246/)

      await openArtMenu(page, slot('ring-sim'))
      await expect(restore).toBeVisible()
      await page.screenshot({ path: test.info().outputPath('art-restore-theirs.png') })
      await restore.locator('button').click()
      await expect(page.locator('.printings-modal')).toBeHidden()
      await expect(inspectorImg).toHaveAttribute('src', /ltr-246/)
      await openArtMenu(page, slot('ring-sim'))
      await expect(restore).toHaveCount(0)

      expect(pageErrors, `pageerrors: ${pageErrors.map(String).join(' | ')}`).toEqual([])
    })
  })

  test("CRD-10: a token's art can be changed during the match, on that token only", async ({ page }) => {
    const searches: string[] = []
    // like Scryfall: a token is only listed with include:extras, under its name without " Token"
    await stubPrintings(page, (url) => {
      searches.push(url)
      return url.includes('!"Zombie"') && url.includes('include:extras')
        ? [print('tm21', '11', 'Core Set 2021 Tokens'), print('tmh3', '6', 'Modern Horizons 3 Tokens')]
        : null
    })
    const zombie = (id: string, controllerId?: string) => ({
      id, name: 'Zombie Token', displayName: 'Zombie Token', expansionSetCode: 'M21', cardNumber: '0', isToken: true,
      mageObjectType: 'TOKEN', cardTypes: ['Creature'], subTypes: ['Zombie'], power: '2', toughness: '2',
      ...(controllerId ? { controllerId } : {}),
    }) as unknown as PermanentView
    const scenario = () => cardInspectorScenario({
      myExtraBattlefield: { z1: zombie('z1'), z2: zombie('z2') },
      opponentBattlefield: { 'z-sim': zombie('z-sim', SIM_PLAYER_ID) },
    })
    await withFakeServer(scenario, async () => {
      const { pageErrors } = await quickStartGame(page, { prefix: 'insp-token' })
      const slot = (id: string) => page.locator(`.card-slot[data-card-id="${id}"]`).first()
      const inspector = page.locator('.card-inspector')
      const restore = page.locator('.printings-restore')
      const inspectorImg = page.locator('.card-inspector-img')

      // the menu lists the token printings: no error
      let menu = await openArtMenu(page, slot('z1'))
      await expect(menu.locator('.printings-status-box.error')).toHaveCount(0)
      expect(searches.at(-1)).toContain('!"Zombie"+include:extras')
      await expect(restore).toHaveCount(0)
      await menu.locator('.printing-card-item', { hasText: 'Core Set 2021 Tokens' }).click()
      await expect(menu).toBeHidden()
      await expect(inspector).toBeVisible()
      await expect(inspectorImg).toHaveAttribute('src', /tm21-11/)
      await page.screenshot({ path: test.info().outputPath('token-art-picked.png') })
      // the other Zombies keep their image
      await inspectSlot(page, slot('z2'))
      await expect(inspectorImg).not.toHaveAttribute('src', /tm21-11/)
      await inspectSlot(page, slot('z-sim'))
      await expect(inspectorImg).not.toHaveAttribute('src', /tm21-11/)

      menu = await openArtMenu(page, slot('z1'))
      await expect(restore).toBeVisible()
      await expect(restore.locator('.printings-restore-hint')).toContainText(/token|ficha/i)
      await restore.locator('button').click()
      await expect(page.locator('.printings-modal')).toBeHidden()
      await expect(inspectorImg).not.toHaveAttribute('src', /tm21-11/)
      await openArtMenu(page, slot('z1'))
      await expect(restore).toHaveCount(0)

      expect(pageErrors, `pageerrors: ${pageErrors.map(String).join(' | ')}`).toEqual([])
    })
  })

  test('CRD-9: "Upload image" in the art menu during a match keeps the menu open and the image is used', async ({ page }) => {
    await stubPrintings(page)
    await withFakeServer(cardInspectorScenario, async () => {
      const { pageErrors } = await quickStartGame(page, { prefix: 'insp-upload' })
      const ring = page.locator('.card-slot[data-card-id="ring1"]').first()
      const menu = await openArtMenu(page, ring)

      const chooser = page.waitForEvent('filechooser')
      await menu.locator('.custom-art-drop').click()
      // a 1x1 PNG
      const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==', 'base64')
      await (await chooser).setFiles({ name: 'my-ring.png', mimeType: 'image/png', buffer: png })
      await expect(menu.locator('.custom-art-drop.has-image')).toBeVisible({ timeout: 10_000 })

      // a click outside the menu closes only the menu; the inspector shows the uploaded image
      await page.locator('.printings-backdrop').click({ position: { x: 5, y: 5 } })
      await expect(menu).toBeHidden()
      await expect(page.locator('.card-inspector')).toBeVisible()
      await expect(page.locator('.card-inspector-img')).toHaveAttribute('src', /^data:image\//)
      // the inspector's own backdrop still closes it
      await page.locator('.card-inspector-backdrop').click({ position: { x: 5, y: 5 } })
      await expect(page.locator('.card-inspector')).toBeHidden()

      // Esc closes the menu first, then the inspector
      await openArtMenu(page, ring)
      await page.keyboard.press('Escape')
      await expect(menu).toBeHidden()
      await expect(page.locator('.card-inspector')).toBeVisible()
      await page.keyboard.press('Escape')
      await expect(page.locator('.card-inspector')).toBeHidden()

      expect(pageErrors, `pageerrors: ${pageErrors.map(String).join(' | ')}`).toEqual([])
    })
  })

  for (const size of [{ width: 1280, height: 720 }, { width: 390, height: 844 }]) {
    test(`CRD-3: fits a ${size.width}x${size.height} screen with nothing clipped`, async ({ page }) => {
      await page.setViewportSize(size)
      await stubEngineText(page)
      await withFakeServer(cardInspectorScenario, async () => {
        const { pageErrors } = await quickStartGame(page, { prefix: `insp${size.width}` })

        const inspector = await inspect(page, 'Tarmogoyf')
        await inspector.evaluate((el) => Promise.all(el.getAnimations().map((a) => a.finished)))
        const box = (await inspector.boundingBox())!
        expect(box.x).toBeGreaterThanOrEqual(0)
        expect(box.x + box.width).toBeLessThanOrEqual(size.width + 0.5)
        expect(box.height).toBeLessThanOrEqual(size.height * 0.7 + 1)
        if (size.width <= 760) expect(Math.abs(box.y + box.height - size.height)).toBeLessThanOrEqual(1)

        const art = (await page.locator('.card-inspector-art').boundingBox())!
        if (size.width > 760) {
          expect(art.y).toBeGreaterThanOrEqual(box.y)
          expect(art.y + art.height).toBeLessThanOrEqual(box.y + box.height)
        }

        const customize = page.locator('.card-inspector-customize')
        await expect(customize).toBeVisible()
        const clipped = await page.locator('.card-inspector button, .card-inspector .card-inspector-chip').evaluateAll((els) =>
          els.filter((el) => el.scrollWidth > el.clientWidth + 1).map((el) => el.textContent),
        )
        expect(clipped).toEqual([])
        if (size.width <= 760) expect((await customize.boundingBox())!.height).toBeGreaterThanOrEqual(44)

        const close = (await page.locator('.card-inspector-close').boundingBox())!
        const mana = await page.locator('.card-inspector-mana').boundingBox()
        if (mana) expect(mana.x + mana.width <= close.x || mana.y >= close.y + close.height).toBe(true)

        expect(pageErrors, `pageerrors: ${pageErrors.map(String).join(' | ')}`).toEqual([])
      })
    })
  }
})
