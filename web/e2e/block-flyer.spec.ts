import { fakeOnly } from './support/fake-mode'
import * as fs from 'node:fs'
import * as path from 'node:path'
import { fileURLToPath } from 'node:url'
import { test, expect } from './fixtures'
import type { Page } from '@playwright/test'
import { blockFlyerScenario, ANJE_ID, QUEEN_ID } from '../fixtures/scenarios/blockFlyer'
import { withFakeServer } from './support/fake-backend'
import { quickStartGame } from './support/start-game'
import { sceneClick } from './support/scene'
fakeOnly()

const SHOTS_DIR = process.env.BLOCK_FLYER_SHOTS ?? path.join(path.dirname(fileURLToPath(import.meta.url)), 'shots')

interface SlotLook {
  zone: string
  classes: string
  opacity: number
  visibility: string
  rect: { x: number; y: number; w: number; h: number }
  inViewport: boolean
  topAtCenter: string
}

/** Every rendered slot of the card: where it is, its effective opacity (ancestors included),
 *  and what element a click at its center would hit. */
async function lookAt(page: Page, id: string): Promise<SlotLook[]> {
  return page.evaluate((cardId) => {
    const out: SlotLook[] = []
    for (const el of document.querySelectorAll<HTMLElement>(`.card-slot[data-card-id="${cardId}"]`)) {
      let opacity = 1
      let visibility = 'visible'
      for (let node: HTMLElement | null = el; node; node = node.parentElement) {
        const cs = getComputedStyle(node)
        opacity *= Number(cs.opacity)
        if (cs.visibility === 'hidden') visibility = 'hidden'
        if (cs.display === 'none') visibility = 'display-none'
      }
      const r = el.getBoundingClientRect()
      const cx = r.x + r.width / 2
      const cy = r.y + r.height / 2
      const top = document.elementFromPoint(cx, cy) as HTMLElement | null
      const topSlot = top?.closest('.card-slot') as HTMLElement | null
      const zoneEl = el.closest('.opponent-zone, .player-zone, .stack-zone, .hand-zone, .command-zone, .feedback-dialog')
      out.push({
        zone: ['opponent-zone', 'player-zone', 'stack-zone', 'hand-zone', 'command-zone', 'feedback-dialog'].find((c) => zoneEl?.classList.contains(c)) ?? '?',
        classes: el.className,
        opacity: Math.round(opacity * 100) / 100,
        visibility,
        rect: { x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height) },
        inViewport: r.width > 0 && r.height > 0 && r.right > 0 && r.bottom > 0 && r.left < innerWidth && r.top < innerHeight,
        topAtCenter: top === el || el.contains(top) ? 'itself' : `${top?.tagName.toLowerCase()}.${String(top?.className ?? '').split(' ').slice(0, 3).join('.')}${topSlot ? ` (card ${topSlot.dataset.cardId})` : ''}`,
      })
    }
    return out
  }, id)
}

async function snap(page: Page, layout: string, label: string, log: string[]): Promise<SlotLook[]> {
  await page.waitForTimeout(900)
  const looks = await lookAt(page, QUEEN_ID)
  log.push(`[${layout}] ${label}: ${JSON.stringify(looks)}`)
  fs.writeFileSync(path.join(SHOTS_DIR, `block-flyer-${layout}-${label}.png`), await page.screenshot())
  return looks
}

test('a double striker that hits twice in one update is visible and clickable afterwards', { tag: '@combat' }, async ({ page }) => {
  fs.mkdirSync(SHOTS_DIR, { recursive: true })
  const log: string[] = []
  const scenario = blockFlyerScenario()
  await withFakeServer(() => scenario, async () => {
    const { helper, pageErrors } = await quickStartGame(page, { prefix: 'bfds', skipCombat: true, skipAsks: true })
    helper.paused = true
    await expect.poll(() => scenario.stage(), { timeout: 15_000 }).toBe('trigger')
    expect(await helper.passPriority(), 'pass with the trigger on the stack').toBeTruthy()
    await expect.poll(() => scenario.stage(), { timeout: 10_000 }).toBe('blockers')
    await snap(page, 'double-strike', '1-before-damage', log)

    // No block: the next view is already past both damage steps, as when the defender
    // does not stop in the first-strike step (Queen Marchesa's two hits came in one update).
    expect(await helper.passPriority(), 'confirm no blockers').toBeTruthy()
    await expect.poll(() => scenario.stage(), { timeout: 10_000 }).toBe('damage')
    await page.waitForTimeout(2500)
    const after = await snap(page, 'double-strike', '2-after-both-strikes', log)
    const inline = await page.evaluate((id) => {
      const el = [...document.querySelectorAll<HTMLElement>(`.card-slot[data-card-id="${id}"]`)].find((e) => e.closest('.opponent-zone'))
      return el?.style.visibility ?? null
    }, QUEEN_ID)
    log.push(`[double-strike] inline style.visibility after the strikes: ${JSON.stringify(inline)}`)
    fs.writeFileSync(path.join(SHOTS_DIR, 'block-flyer-double-strike.log'), log.join('\n') + '\n')

    const board = after.filter((l) => l.zone === 'opponent-zone')
    expect(board.length, 'Queen Marchesa on the opponent\'s board').toBe(1)
    expect(board[0].visibility, 'visibility after the strikes').toBe('visible')
    expect(board[0].topAtCenter, 'a click at its center reaches it').toBe('itself')
    expect(pageErrors, `pageerrors: ${pageErrors.map(String).join(' | ')}`).toEqual([])
  })
})

for (const layout of ['standard', 'pod'] as const) {
  test(`a flying commander attacking stays visible while the human tries to block it (${layout})`, { tag: '@combat' }, async ({ page }) => {
    fs.mkdirSync(SHOTS_DIR, { recursive: true })
    const log: string[] = []
    const scenario = blockFlyerScenario()
    await withFakeServer(() => scenario, async () => {
      const { helper, pageErrors } = await quickStartGame(page, { prefix: `bf${layout.slice(0, 2)}`, skipCombat: true, skipAsks: true })
      helper.paused = true
      await page.evaluate((value) => {
        const store = (globalThis as unknown as { __mageStore?: { setSetting?: (k: string, v: unknown) => void } }).__mageStore
        store?.setSetting?.('boardLayout', value)
        store?.setSetting?.('boardLayoutManual', true)
      }, layout)

      await expect.poll(() => scenario.stage(), { timeout: 15_000 }).toBe('trigger')
      const atTrigger = await snap(page, layout, '1-attacked-trigger-on-stack', log)

      expect(await helper.passPriority(), 'pass with the trigger on the stack').toBeTruthy()
      await expect.poll(() => scenario.stage(), { timeout: 10_000 }).toBe('blockers')
      const atBlockers = await snap(page, layout, '2-select-blockers', log)

      expect(await sceneClick(page, ANJE_ID), 'pick Anje as a blocker').toBeTruthy()
      await expect.poll(() => scenario.stage(), { timeout: 10_000 }).toBe('pick')
      const atPick = await snap(page, layout, '3-empty-attacker-pick', log)

      fs.writeFileSync(path.join(SHOTS_DIR, `block-flyer-${layout}.log`), log.join('\n') + '\n')
      for (const [label, looks] of [['trigger', atTrigger], ['blockers', atBlockers], ['pick', atPick]] as const) {
        const board = looks.filter((l) => l.zone === 'opponent-zone')
        expect(board.length, `${label}: Queen Marchesa on the opponent's board`).toBe(1)
        expect(board[0].opacity, `${label}: opacity`).toBeGreaterThan(0.5)
        expect(board[0].visibility, `${label}: visibility`).toBe('visible')
        expect(board[0].inViewport, `${label}: on screen`).toBe(true)
      }
      expect(pageErrors, `pageerrors: ${pageErrors.map(String).join(' | ')}`).toEqual([])
    })
  })
}
