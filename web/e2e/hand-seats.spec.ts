/**
 * HND-2: the hand stays reachable whichever seats are out. A seat going out widens its neighbours,
 * and the hand once followed them past the window edge: a discard could not be answered and the
 * game hung. jsdom has no layout, so none of this can be checked in the unit tests.
 */
import { test, expect, type Page } from '@playwright/test'

const GALLERY_SIDEBAR_W = 272
const HAND_SIZE = 8

type Store = {
  getState: () => Record<string, any>
  setState: (patch: unknown) => void
  setSetting: (key: string, value: unknown) => void
}

async function openTable(page: Page, entry: string, width: number, height: number) {
  await page.setViewportSize({ width: width + GALLERY_SIDEBAR_W, height })
  await page.addInitScript(() => localStorage.clear())
  await page.route(/^https?:\/\/(?!localhost|127\.0\.0\.1)/, (route) => route.abort())
  await page.goto(`/#/gallery/${encodeURIComponent(entry)}`)
  await expect(page.locator('.game-stage')).toBeVisible({ timeout: 30_000 })
}

const useClassicInterface = (page: Page) => page.evaluate(() => {
  (globalThis as unknown as { __mageStore: Store }).__mageStore.setSetting('gameUi', 'v1')
})

const opponentCount = (page: Page) => page.evaluate(() => {
  const players = (globalThis as unknown as { __mageStore: Store }).__mageStore.getState().game.players
  return players.filter((p: { controlled?: boolean }) => !p.controlled).length as number
})

/** Puts out the opponents whose bit is set in `mask`, and deals the player a full hand. */
const setTable = (page: Page, mask: number) => page.evaluate(({ mask, handSize }) => {
  const store = (globalThis as unknown as { __mageStore: Store }).__mageStore
  const game = store.getState().game
  let opponent = -1
  const players = game.players.map((p: { controlled?: boolean; life: number }) => {
    if (p.controlled) return { ...p, handCount: handSize }
    opponent += 1
    const out = (mask & (1 << opponent)) !== 0
    return { ...p, hasLeft: out, life: out ? 0 : p.life || 20 }
  })
  const me = players.find((p: { controlled?: boolean }) => p.controlled)
  const template = Object.values(game.myHand ?? {})[0] ?? Object.values(me?.battlefield ?? {})[0]
  const id = (i: number) => `aaaaaaaa-0000-4000-8000-00000000000${i}`
  const myHand = Object.fromEntries(Array.from({ length: handSize }, (_, i) => [id(i), { ...(template as object), id: id(i) }]))
  store.setState({ game: { ...game, players, myHand } })
}, { mask, handSize: HAND_SIZE })

/** How many cards are drawn, and what keeps the player from reaching each one that cannot be reached. */
const unreachableCards = (page: Page) => page.evaluate(() => {
  const stage = document.querySelector('.game-stage')!.getBoundingClientRect()
  const cards = [...document.querySelectorAll<HTMLElement>('[data-testid="hand-bar"] .hand-card')].map((c) => c.getBoundingClientRect())
  const problems = cards.flatMap((r, i) => {
    if (r.left < stage.left || r.right > stage.right || r.top >= innerHeight) return [`card ${i} is off the table at ${Math.round(r.left)}..${Math.round(r.right)}`]
    // Several spots, because a fanned card leans away from the corners of its box.
    const spots = [0.2, 0.35, 0.5].map((k) => document.elementFromPoint(r.left + r.width * k, Math.min(r.top + 30, innerHeight - 4)))
    return spots.some((at) => at?.closest('[data-testid="hand-bar"]')) ? [] : [`card ${i} is covered by ${spots[1]?.className}`]
  })
  return { count: cards.length, problems }
})

const expectHandReachable = async (page: Page, label: string) => {
  const { count, problems } = await unreachableCards(page)
  expect(count, `${label}: the hand is drawn`).toBe(HAND_SIZE)
  expect(problems, label).toEqual([])
}

test.describe('the hand stays reachable as seats go out @hand-bar', () => {
  for (const [width, height] of [[1877, 916], [1366, 650]]) {
    test(`HND-2: every set of seats out on the all-boards table at ${width}x${height}`, async ({ page }) => {
      test.setTimeout(180_000)
      await openTable(page, 'board:all-boards-8', width, height)
      const all = 2 ** (await opponentCount(page))
      for (let mask = 0; mask < all - 1; mask++) {
        await setTable(page, mask)
        await page.waitForTimeout(150)
        await expectHandReachable(page, `seats out ${mask.toString(2)}`)
      }
    })

    test(`HND-2: the bottom-right seat going out of a pod at ${width}x${height}`, async ({ page }) => {
      await openTable(page, 'board:pod-4', width, height)
      await useClassicInterface(page)
      await expect(page.locator('.pod-board')).toBeVisible()
      for (const mask of [0b000, 0b100, 0b110, 0b101, 0b100, 0b000]) {
        await setTable(page, mask)
        await page.waitForTimeout(450)
        await expectHandReachable(page, `seats out ${mask.toString(2)}`)
      }
      await setTable(page, 0b100)
      await page.waitForTimeout(450)
      await expect(page.locator('.pod-board--br-out')).toBeVisible()
    })
  }

  test('HND-2: the screenshot table, down to a refresh and a rollback that brings the seats back', async ({ page }) => {
    const table = 'board:all-boards-8'
    await openTable(page, table, 1877, 916)
    const opponents = await page.evaluate(() => {
      const store = (globalThis as unknown as { __mageStore: Store }).__mageStore
      const me = store.getState().game.players.findIndex((p: { controlled?: boolean }) => p.controlled)
      const players = store.getState().game.players.length as number
      // The seat after me sits top-left and the last three bottom-right of me, as allBoardsRows has it.
      return { me, players }
    })
    const seatBit = (after: number) => {
      const seat = (opponents.me + 1 + after) % opponents.players
      return 1 << (seat > opponents.me ? seat - 1 : seat)
    }
    const screenshotMask = seatBit(0) | seatBit(4) | seatBit(5) | seatBit(6)

    let mask = 0
    for (const after of [0, 6, 4, 5]) {
      mask |= seatBit(after)
      await setTable(page, mask)
      await page.waitForTimeout(450)
      await expectHandReachable(page, `seat ${after} after me went out`)
    }
    for (const refresh of [1, 2, 3]) {
      await page.reload()
      await expect(page.locator('.game-stage')).toBeVisible({ timeout: 30_000 })
      await setTable(page, screenshotMask)
      await page.waitForTimeout(800)
      await expectHandReachable(page, `refresh ${refresh}`)
    }
    await setTable(page, 0)
    await page.waitForTimeout(800)
    await expectHandReachable(page, 'the seats came back')
  })
})
