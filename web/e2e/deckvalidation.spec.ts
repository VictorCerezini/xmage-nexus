import { expect } from './fixtures'
import { test } from './fixtures'
import { withFakeServer } from './support/fake-backend'
import { proxyPort, FAKE_MODE } from './dual'
import { deckIssuesScenario } from '../fixtures/scenarios/deckIssues'
import { TABLE } from '../fixtures/table-names'
import { dismissSetupWizard, installCapture, type CaptureBuffers } from './support/start-game'
import { showDeckListView } from './support/deck-editor'

/**
 * @deckvalidation — Pre-validación de mazos contra la BD de cartas del proxy.
 * En fake el FixtureServer responde a `validateDeck` con un informe
 * declarativo; el flujo es idéntico al real. DCK-17: una impresión que el
 * servidor no tiene se cambia sola (sin diálogo ni aviso); solo una carta que
 * el servidor no puede jugar abre el diálogo (quitar y jugar).
 */

const DEFAULT_DECK = `1 [CY:77] Rhystic Tutor\n20 [LEA:288] Island`

async function openJoinDialogWithBadDeck(page: import('@playwright/test').Page, deckText = DEFAULT_DECK): Promise<CaptureBuffers> {
  const buffers: CaptureBuffers = { frames: [], sent: [], pageErrors: [] }
  installCapture(page, buffers)
  await page.goto(`/?proxyPort=${proxyPort()}`)
  await dismissSetupWizard(page)
  const username = `dv_${Date.now()}`.slice(0, 13)
  await page.getByPlaceholder(/Usuario|Username/i).fill(username)
  await page.getByPlaceholder(/Contraseña|Password/i).fill('pass')
  await page.getByRole('button', { name: /Conectar/i }).click()
  await expect(page.getByRole('button', { name: /Mesas/ })).toBeVisible({ timeout: 15000 })

  const row = page.locator('.table-row', { hasText: TABLE.deckIssues }).first()
  await expect(row).toBeVisible({ timeout: 10_000 })
  await row.getByRole('button', { name: /^(Unirse|Join)$/ }).click()
  await expect(page.getByTestId('join-table-dialog')).toBeVisible()

  // importar un mazo con la carta problemática (set "CY" inexistente en XMage)
  await page.locator('[data-testid="join-import-toggle-btn"]').click()
  await page.locator('.import-textarea').fill(deckText)
  await page.locator('[data-testid="import-submit-btn"]').click()
  await expect(page.locator('.join-deck-card.selected', { hasText: /Rhystic Tutor/i })).toBeVisible()
  await page.locator('.join-submit-btn').click()
  return buffers
}

async function waitJoinSent(buffers: CaptureBuffers): Promise<Record<string, unknown>> {
  let joinSent: Record<string, unknown> | null | undefined
  await expect(async () => {
    joinSent = buffers.sent.find((f) => f?.action === 'joinTable')
    expect(joinSent, 'joinTable should be sent after the check resolves').toBeTruthy()
  }).toPass({ timeout: 15_000 })
  return joinSent as unknown as Record<string, unknown>
}

async function openNewDeckBuilder(page: import('@playwright/test').Page): Promise<void> {
  await page.goto(`/?proxyPort=${proxyPort()}`)
  await dismissSetupWizard(page)
  await page.getByPlaceholder(/Usuario|Username/i).fill(`dv_${Date.now()}`.slice(0, 13))
  await page.getByPlaceholder(/Contraseña|Password/i).fill('pass')
  await page.getByRole('button', { name: /Conectar/i }).click()
  await expect(page.getByRole('button', { name: /Mesas/ })).toBeVisible({ timeout: 15000 })
  await page.getByRole('button', { name: /Mis Mazos|Mazos/i }).click()
  await page.locator('.deck-box-create').click()
  await expect(page.locator('.deck-builder')).toBeVisible({ timeout: 8000 })
  await showDeckListView(page)
}

type SentDeck = { cards: Array<{ cardName: string; setCode: string; cardNumber: string; amount: number }> }

test.describe('Deck validation pre-join @deckvalidation @real', () => {
  test('DCK-17: a card the server has in another printing joins without any dialog', async ({ page }) => {
    // Solo fake: en real la mesa la tendría que crear otro humano (el dueño no
    // puede abandonarla sin eliminarla — TableController.leaveTable borra la
    // mesa sin empezar del creador).
    test.skip(!FAKE_MODE, 'requiere una mesa ajena con asiento libre (solo el escenario fake la provee)')
    await withFakeServer(deckIssuesScenario, async () => {
      const buffers = await openJoinDialogWithBadDeck(page)
      // joinTable only goes out once the check resolves: with a dialog it would wait for a click
      const joinSent = await waitJoinSent(buffers)
      await expect(page.getByTestId('deck-issues-dialog')).toHaveCount(0)
      const deck = (joinSent.args as { deck?: SentDeck }).deck!
      expect(deck.cards.some((c) => c.setCode === 'CY')).toBe(false)
      expect(deck.cards.some((c) => c.setCode === 'PCY' && c.cardName === 'Rhystic Tutor')).toBe(true)
    })
  })

  test('DCK-17: only a card the server cannot play opens the dialog; remove-and-play keeps the swap', async ({ page }) => {
    test.skip(!FAKE_MODE, 'requiere una mesa ajena con asiento libre (solo el escenario fake la provee)')
    await withFakeServer(deckIssuesScenario, async () => {
      const buffers = await openJoinDialogWithBadDeck(page, `1 [CY:77] Rhystic Tutor\n1 [ZZZ:1] Nonexistent Card\n20 [LEA:288] Island`)

      await expect(page.getByTestId('deck-issues-dialog')).toBeVisible({ timeout: 10_000 })
      const rows = page.getByTestId('deck-issue-row')
      await expect(rows).toHaveCount(1)
      await expect(rows.first()).toContainText('Nonexistent Card')
      await expect(page.getByTestId('deck-issues-dialog')).not.toContainText('Rhystic Tutor')
      await expect(page.getByTestId('deck-issues-auto-resolve')).toHaveCount(0)
      await expect(page.getByTestId('deck-issue-suggestion')).toHaveCount(0)

      await page.getByTestId('deck-issues-remove-and-play').click()
      await expect(page.getByTestId('deck-issues-dialog')).toBeHidden({ timeout: 10_000 })

      const joinSent = await waitJoinSent(buffers)
      const deck = (joinSent.args as { deck?: SentDeck }).deck!
      expect(deck.cards.some((c) => c.cardName === 'Nonexistent Card')).toBe(false)
      expect(deck.cards.some((c) => c.setCode === 'PCY' && c.cardName === 'Rhystic Tutor')).toBe(true)
      expect(deck.cards.find((c) => c.cardName === 'Island')?.amount).toBe(20)
    })
  })

  test('DCK-17: the deck builder warns only about cards the server cannot play', async ({ page }) => {
    await withFakeServer(deckIssuesScenario, async () => {
      await openNewDeckBuilder(page)

      // a printing the server lacks, a promo the proxy normalizes, and a card it cannot play
      await page.getByRole('button', { name: /Importar Mazo/i }).click()
      await page.locator('.deck-import-textarea').fill(
        `1 [CY:77] Rhystic Tutor\n1 [PWOE:242s] Agatha's Soul Cauldron\n1 [ZZZ:1] Nonexistent Card\n20 [LEA:288] Island`,
      )
      await page.locator('[data-testid="import-submit-btn"]').click()
      // ojo: el strip muestra el nombre LOCALIZADO; el original va en el atributo title
      await expect(page.locator('.arena-card-strip[title*="Rhystic Tutor"]')).toBeVisible({ timeout: 5000 })

      // re-validación en vivo (debounce 1.2s tras la edición)
      const banner = page.getByTestId('builder-server-issues')
      await expect(banner).toBeVisible({ timeout: 8000 })
      await expect(banner).toContainText(/Nonexistent Card/)
      await expect(banner).not.toContainText(/Rhystic Tutor/)
      await expect(banner).not.toContainText(/Agatha/)
      await expect(banner.getByTestId('builder-issue-auto-resolve')).toHaveCount(0)
      await expect(banner.getByTestId('builder-issue-repair')).toHaveCount(0)
      await expect(page.locator('.arena-card-strip.has-issue')).toHaveCount(1)
      await expect(page.locator('.arena-card-strip.has-issue[title*="Nonexistent Card"]')).toHaveCount(1)
    })
  })

  test('deck builder: a "loads as another card" warning can be accepted', async ({ page }) => {
    test.skip(!FAKE_MODE, 'the mismatch card is declared by the fake scenario')
    await withFakeServer(deckIssuesScenario, async () => {
      await openNewDeckBuilder(page)
      await page.getByRole('button', { name: /Importar Mazo/i }).click()
      await page.locator('.deck-import-textarea').fill(`1 [C20:77] Banisher Priest\n20 [LEA:288] Island`)
      await page.locator('[data-testid="import-submit-btn"]').click()

      const banner = page.getByTestId('builder-server-issues')
      await expect(banner).toBeVisible({ timeout: 8000 })
      await expect(banner).toContainText(/Fiend Hunter/)
      await banner.getByTestId('builder-issue-accept').click()
      await expect(banner).not.toBeVisible()
    })
  })
})
