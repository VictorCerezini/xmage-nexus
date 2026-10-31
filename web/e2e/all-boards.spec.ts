/**
 * The all-boards view on real layout: every player's board on one screen, the wheel zooming
 * the whole interface and the middle button dragging it. jsdom has no layout, so none of this
 * can be checked in the unit tests.
 */
import { test, expect, type Page } from '@playwright/test'

const stage = (page: Page) => page.locator('.game-stage')
const transformOf = (page: Page) => stage(page).evaluate((el) => (el as HTMLElement).style.transform)

async function openTable(page: Page, entry: string) {
  await page.addInitScript(() => localStorage.clear())
  await page.route(/^https?:\/\/(?!localhost|127\.0\.0\.1)/, (route) => route.abort())
  await page.goto(`/#/gallery/${encodeURIComponent(entry)}`)
  await expect(stage(page)).toBeVisible({ timeout: 30_000 })
  await page.waitForTimeout(500)
}

async function overBoard(page: Page) {
  const box = await page.getByTestId('all-boards-board').boundingBox()
  if (!box) throw new Error('no board')
  const point = { x: box.x + box.width / 2, y: box.y + box.height / 4 }
  await page.mouse.move(point.x, point.y)
  return point
}

/** A spot on the table where a press means nothing else. */
async function freeSpot(page: Page) {
  const spot = await page.evaluate(() => {
    const taken = 'button, a, input, [role="button"]:not(.card-slot), [role="separator"], .hand-card-slot, .playable, .targetable, .chosen'
    const stage = document.querySelector('.game-stage')!.parentElement!.getBoundingClientRect()
    for (let y = stage.top + 60; y < stage.bottom - 60; y += 23) {
      for (let x = stage.left + 60; x < stage.right - 60; x += 23) {
        const el = document.elementFromPoint(x, y)
        if (!el?.closest('.game-stage') || el.closest(taken)) continue
        let free = true
        for (let up: Element | null = el; up && !up.classList.contains('game-stage'); up = up.parentElement) {
          const style = getComputedStyle(up)
          if ((/auto|scroll/.test(style.overflowY) && up.scrollHeight > up.clientHeight) || (/auto|scroll/.test(style.overflowX) && up.scrollWidth > up.clientWidth)) free = false
        }
        if (free) return { x, y }
      }
    }
    return null
  })
  if (!spot) throw new Error('no free spot')
  return spot
}

async function zoomIn(page: Page, notches = 5) {
  await page.mouse.move(700, 350)
  for (let i = 0; i < notches; i++) await page.mouse.wheel(0, -100)
  await expect.poll(() => transformOf(page)).toContain('scale(')
}

test.describe('Zoom in any game', () => {
  for (const entry of ['frame:combat', 'board:pod-4', 'board:arena-4', 'board:arena-focus', 'board:one-board-6']) {
    test(`${entry}: the wheel zooms, and zooming out restores the layout`, async ({ page }, testInfo) => {
      await openTable(page, entry)
      await expect(page.getByTestId('all-boards-board')).toHaveCount(0)
      const card = page.locator('.game-stage [data-card-id]').first()
      const before = (await card.boundingBox())!
      await zoomIn(page)
      const after = (await card.boundingBox())!
      expect(after.width / before.width).toBeCloseTo(1.15 ** 5, 1)
      await page.screenshot({ path: testInfo.outputPath(`${entry.replace(':', '-')}.png`) })
      for (let i = 0; i < 8; i++) await page.mouse.wheel(0, 100)
      await expect.poll(() => transformOf(page)).toBe('')
      const back = (await card.boundingBox())!
      expect(back.width).toBeCloseTo(before.width, 0)
      expect(back.x).toBeCloseTo(before.x, 0)
    })
  }

  test('zooms up to 800%', async ({ page }) => {
    await openTable(page, 'board:arena-4')
    await page.mouse.move(700, 350)
    for (let i = 0; i < 25; i++) await page.mouse.wheel(0, -100)
    await expect(page.getByTestId('table-zoom-reset')).toContainText('800%')
  })

  test('the left button does nothing to the view before zooming in', async ({ page }) => {
    await openTable(page, 'board:arena-4')
    const { x, y } = await freeSpot(page)
    await page.mouse.move(x, y)
    await page.mouse.down()
    await page.mouse.move(x - 100, y - 50, { steps: 4 })
    await page.mouse.up()
    expect(await transformOf(page)).toBe('')
  })

  test('the left button drags the zoomed view from a free spot, in a two-player game too', async ({ page }) => {
    await openTable(page, 'frame:combat')
    await zoomIn(page)
    const rect = () => stage(page).evaluate((el) => el.getBoundingClientRect().toJSON())
    const { x, y } = await freeSpot(page)
    const start = await rect()
    await page.mouse.move(x, y)
    await page.mouse.down()
    await page.mouse.move(x + 40, y + 20, { steps: 4 })
    await page.mouse.up()
    const end = await rect()
    expect(Math.abs(end.left - start.left) + Math.abs(end.top - start.top)).toBeGreaterThan(10)
    await expect(stage(page)).not.toHaveClass(/is-table-dragging/)
  })

  test('a small slip of the left button is still a click and does not move the view', async ({ page }) => {
    await openTable(page, 'board:arena-4')
    await zoomIn(page)
    const { x, y } = await freeSpot(page)
    const before = await transformOf(page)
    await page.mouse.move(x, y)
    await page.mouse.down()
    await page.mouse.move(x + 2, y + 1)
    await page.mouse.up()
    expect(await transformOf(page)).toBe(before)
  })

  test('the left button on a hand card or a button does not move the view', async ({ page }) => {
    await openTable(page, 'frame:combat')
    await zoomIn(page, 2)
    const before = await transformOf(page)
    for (const target of [page.locator('.hand-card-slot').first(), page.getByTestId('game-menu-btn')]) {
      const box = (await target.boundingBox())!
      const x = box.x + box.width / 2
      const y = box.y + Math.min(box.height / 2, 20)
      await page.mouse.move(x, y)
      await page.mouse.down()
      await page.mouse.move(x + 40, y + 10, { steps: 4 })
      await page.mouse.up()
      expect(await transformOf(page)).toBe(before)
      await page.keyboard.press('Escape')
    }
  })

  test('dragging from a card with nothing to do is not a click on it, but a plain press is', async ({ page }) => {
    await openTable(page, 'board:all-boards-6')
    await zoomIn(page, 3)
    const spot = await page.evaluate(() => {
      for (const card of document.querySelectorAll<HTMLElement>('.game-stage .card-slot:not(.playable):not(.targetable):not(.chosen):not(.hand-card)')) {
        const r = card.getBoundingClientRect()
        const x = r.left + r.width / 2
        const y = r.top + r.height / 2
        if (x > 100 && y > 100 && x < innerWidth - 100 && y < innerHeight - 100 && card.contains(document.elementFromPoint(x, y)) && !card.closest('.hand-card-slot')) {
          ;(window as unknown as { clicks: number }).clicks = 0
          card.addEventListener('click', () => { (window as unknown as { clicks: number }).clicks++ })
          return { x, y }
        }
      }
      return null
    })
    expect(spot).not.toBeNull()
    const clicks = () => page.evaluate(() => (window as unknown as { clicks: number }).clicks)
    await page.mouse.move(spot!.x, spot!.y)
    await page.mouse.down()
    await page.mouse.up()
    expect(await clicks()).toBe(1)
    const before = await transformOf(page)
    await page.mouse.down()
    await page.mouse.move(spot!.x + 30, spot!.y + 30, { steps: 4 })
    await page.mouse.up()
    expect(await transformOf(page)).not.toBe(before)
    expect(await clicks()).toBe(1)
  })
})

test.describe('All boards view', () => {
  for (const [entry, players] of [['5', 5], ['6', 6], ['8', 8], ['10', 10], ['crowded-6', 6], ['classic-6', 6]] as const) {
    test(`${entry}: shows all ${players} boards inside the screen`, async ({ page }, testInfo) => {
      await openTable(page, `board:all-boards-${entry}`)
      const cells = page.getByTestId('all-boards-cell')
      await expect(cells).toHaveCount(players)
      const wrap = await page.locator('.board-wrap').boundingBox()
      for (const box of await cells.evaluateAll((els) => els.map((el) => el.getBoundingClientRect().toJSON()))) {
        expect(box.width).toBeGreaterThan(50)
        expect(box.height).toBeGreaterThan(50)
        expect(box.right).toBeLessThanOrEqual(wrap!.x + wrap!.width + 1)
        expect(box.bottom).toBeLessThanOrEqual(wrap!.y + wrap!.height + 1)
      }
      // No seat's life total may hide under the hand or the phase dial.
      const hidden = await page.evaluate(() => [...document.querySelectorAll('.all-boards-board .player-info-bar')].filter((bar) => {
        const r = bar.getBoundingClientRect()
        const top = document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2)
        return !top || !bar.contains(top)
      }).length)
      expect(hidden).toBe(0)
      await page.screenshot({ path: testInfo.outputPath(`all-boards-${entry}.png`) })
    })
  }

  test('with the setting off, the same table stays on one board at a time', async ({ page }) => {
    await openTable(page, 'board:one-board-6')
    await expect(page.getByTestId('game-board')).toBeVisible()
    await expect(page.getByTestId('all-boards-board')).toHaveCount(0)
  })

  test('the wheel zooms the whole interface around the pointer, and back', async ({ page }, testInfo) => {
    await openTable(page, 'board:all-boards-6')
    const { x, y } = await overBoard(page)
    expect(await transformOf(page)).toBe('')
    const card = page.locator('[data-card-id]').first()
    const before = (await card.boundingBox())!

    for (let i = 0; i < 5; i++) await page.mouse.wheel(0, -100)
    await expect.poll(() => transformOf(page)).toContain('scale(')
    const after = (await card.boundingBox())!
    expect(after.width / before.width).toBeCloseTo(1.15 ** 5, 1)
    await expect(page.getByTestId('table-zoom-reset')).toContainText('201%')
    await page.screenshot({ path: testInfo.outputPath('zoomed.png') })

    const top = await stage(page).evaluate((el) => el.getBoundingClientRect().top)
    expect(top).toBeLessThan(y)
    expect(x).toBeGreaterThan(0)

    for (let i = 0; i < 8; i++) await page.mouse.wheel(0, 100)
    await expect.poll(() => transformOf(page)).toBe('')
    await expect(page.getByTestId('table-zoom-reset')).toHaveCount(0)
  })

  test('the middle button drags from anywhere only when zoomed, and the view stays on the interface', async ({ page }) => {
    await openTable(page, 'board:all-boards-6')
    const { x, y } = await overBoard(page)
    const rect = () => stage(page).evaluate((el) => el.getBoundingClientRect().toJSON())
    const fitted = await rect()

    await page.mouse.down({ button: 'middle' })
    await page.mouse.move(x - 200, y - 100)
    await page.mouse.up({ button: 'middle' })
    expect((await rect()).left).toBe(fitted.left)

    await page.mouse.move(x, y)
    for (let i = 0; i < 6; i++) await page.mouse.wheel(0, -100)
    await expect.poll(() => transformOf(page)).toContain('scale(')
    const zoomed = await rect()

    await page.mouse.down({ button: 'middle' })
    await page.mouse.move(x - 80, y - 40, { steps: 4 })
    await page.mouse.up({ button: 'middle' })
    const dragged = await rect()
    expect(dragged.left).toBeCloseTo(zoomed.left - 80, 0)
    expect(dragged.top).toBeCloseTo(zoomed.top - 40, 0)

    await page.mouse.down({ button: 'middle' })
    await page.mouse.move(x + 5000, y + 5000, { steps: 4 })
    await page.mouse.up({ button: 'middle' })
    const parent = await stage(page).evaluate((el) => el.parentElement!.getBoundingClientRect().toJSON())
    const corner = await rect()
    expect(corner.left).toBeCloseTo(parent.left, 0)
    expect(corner.top).toBeCloseTo(parent.top, 0)
  })

  test('the zoom badge goes back to the whole table', async ({ page }) => {
    await openTable(page, 'board:all-boards-5')
    await overBoard(page)
    await page.mouse.wheel(0, -300)
    await page.getByTestId('table-zoom-reset').click()
    await expect.poll(() => transformOf(page)).toBe('')
  })

  test('a card stays clickable where it appears when zoomed', async ({ page }) => {
    await openTable(page, 'board:all-boards-6')
    await overBoard(page)
    for (let i = 0; i < 4; i++) await page.mouse.wheel(0, -100)
    await expect.poll(() => transformOf(page)).toContain('scale(')
    const target = await page.evaluate(() => {
      const card = [...document.querySelectorAll('.all-boards-board [data-card-id]')].find((el) => {
        const r = el.getBoundingClientRect()
        const x = r.left + r.width / 2
        const y = r.top + r.height / 2
        return x > 0 && y > 0 && x < innerWidth && y < innerHeight && el.contains(document.elementFromPoint(x, y))
      })
      return card?.getAttribute('data-card-id') ?? null
    })
    expect(target).not.toBeNull()
  })
})
