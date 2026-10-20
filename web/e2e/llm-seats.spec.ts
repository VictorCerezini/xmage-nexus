import { test, expect, type Page } from '@playwright/test'
import { fakeOnly } from './support/fake-mode'
import { withFakeServer } from './support/fake-backend'
import { installCapture, login, type CaptureBuffers } from './support/start-game'
import { makeBaseScenario } from '../fixtures/fake'
import { LLM_FAIL_REASON, llmSeatsScenario } from '../fixtures/scenarios/llmSeats'
import { TABLE } from '../fixtures/table-names'

fakeOnly()

const NEXT = /Siguiente|Next/
const CREATE = /Crear Mesa|Create Table/

async function openSeatsStep(page: Page, tableName: string) {
  await page.getByRole('button', { name: /Nueva|New/i }).first().click()
  await expect(page.getByRole('heading', { name: /Nueva mesa|Crear Mesa|Create Table/i })).toBeVisible()
  await page.getByPlaceholder(/Modern Casual Bo3/).fill(tableName)
  for (let i = 0; i < 3; i++) await page.getByRole('button', { name: NEXT }).click()
  await page.getByTestId('seat-node-1').click()
  await expect(page.getByTestId('seat-type-0')).toBeVisible()
}

const pickSeatType = (page: Page, value: string) =>
  page.getByTestId('seat-type-0').locator(`[data-seat-type="${value}"]`).click()

async function createTable(page: Page) {
  await page.getByRole('button', { name: NEXT }).click()
  await page.getByRole('button', { name: CREATE }).click()
  await expect(page.getByTestId('staging-player-actions')).toBeVisible({ timeout: 15_000 })
}

const llmOptionValues = (page: Page) =>
  page.getByTestId('seat-type-0').locator('[data-seat-type^="llm:"]').evaluateAll((chips) =>
    chips.map((c) => `${c.getAttribute('data-seat-type')} ${c.textContent}`))

const newBuffers = (): CaptureBuffers => ({ frames: [], sent: [], pageErrors: [] })

test.describe('Model seats', () => {
  test('a ChatGPT seat is created with its model and reasoning, then joins the table', async ({ page }) => {
    await withFakeServer(() => llmSeatsScenario(), async () => {
      const buffers = newBuffers()
      installCapture(page, buffers)
      await login(page, 'e2e')
      await openSeatsStep(page, TABLE.llmSeats)

      await expect.poll(() => llmOptionValues(page)).toEqual(['llm:chatgpt_subscription ChatGPT', 'llm:openrouter_free OpenRouter'])
      await expect(page.getByTestId('create-llm-unavailable')).toContainText('claude_subscription (Claude Code is not signed in)')

      await pickSeatType(page, 'llm:openrouter_free')
      await expect(page.getByTestId('seat-node-1')).toContainText('OpenRouter · Free Model A')
      await expect(page.getByText(/42/).first()).toBeVisible()
      await expect(page.getByTestId('seat-llm-effort-0')).toBeDisabled()

      await pickSeatType(page, 'llm:chatgpt_subscription')
      await expect(page.getByTestId('seat-llm-model-0')).toHaveValue('gpt-6-luna')
      await expect(page.getByTestId('seat-llm-effort-0')).toHaveValue('medium')
      await page.getByTestId('seat-llm-model-0').selectOption('gpt-6-astra')
      await expect(page.getByTestId('seat-llm-effort-0')).toHaveValue('high')
      await page.getByTestId('seat-llm-effort-0').selectOption('ultra')
      await expect(page.getByTestId('seat-node-1')).toContainText('ChatGPT · GPT-6 Astra')
      await expect(page.getByTestId('seat-type-0').locator('[data-seat-type="llm:chatgpt_subscription"]')).toHaveAttribute('aria-checked', 'true')

      await createTable(page)
      const create = buffers.sent.find((f) => f?.action === 'createTable') as { args?: { playerTypes?: string[]; llmSeats?: Array<Record<string, unknown>> } } | undefined
      expect(create?.args?.playerTypes).toEqual(['HUMAN', 'HUMAN'])
      expect(create?.args?.llmSeats).toHaveLength(1)
      expect(create?.args?.llmSeats?.[0]).toMatchObject({ seat: 2, provider: 'chatgpt_subscription', model: 'gpt-6-astra', effort: 'ultra' })
      expect((create?.args?.llmSeats?.[0]?.deck as { cards?: unknown[] } | undefined)?.cards?.length).toBeGreaterThan(0)

      const badge = page.getByTestId('staging-llm-1')
      await expect(badge).toHaveAttribute('data-status', 'joined', { timeout: 10_000 })
      await expect(badge).toHaveText('GPT-6 Astra · ultra')
      await expect(page.getByText('ChatGPT 2').first()).toBeVisible()
      const statuses = buffers.frames.filter((f) => f?.type === 'llmSeat').map((f) => f?.status)
      expect(statuses).toEqual(['starting', 'joined'])
      await expect(page.getByTestId('staging-error')).toHaveCount(0)
      expect(buffers.pageErrors).toEqual([])
    })
  })

  test('a model seat that fails shows the reason on its seat', async ({ page }) => {
    await withFakeServer(() => llmSeatsScenario({ fail: true }), async () => {
      const buffers = newBuffers()
      installCapture(page, buffers)
      await login(page, 'e2e')
      await openSeatsStep(page, TABLE.llmSeats)
      await expect.poll(() => llmOptionValues(page)).toContain('llm:chatgpt_subscription ChatGPT')
      await pickSeatType(page, 'llm:chatgpt_subscription')
      await createTable(page)

      const badge = page.getByTestId('staging-llm-1')
      await expect(badge).toHaveAttribute('data-status', 'failed', { timeout: 10_000 })
      await expect(badge).toContainText('ChatGPT 2')
      await expect(badge).toHaveAttribute('title', new RegExp(LLM_FAIL_REASON))
      expect(buffers.pageErrors).toEqual([])
    })
  })

  test('a proxy without model seats offers none and shows no error', async ({ page }) => {
    const olderProxy = () => makeBaseScenario({
      tableId: 'table-no-llm-1',
      tableName: 'no-llm-e2e',
      gameId: 'game-no-llm-1',
      onRequest(conn, action, _args, requestId) {
        if (action !== 'getLlmProviders' && action !== 'getLlmSeats') return false
        conn.fail(requestId, action, `Unknown action: ${action}`, 'UNKNOWN_ACTION')
        return true
      },
    })
    await withFakeServer(olderProxy, async () => {
      const buffers = newBuffers()
      installCapture(page, buffers)
      await login(page, 'e2e')
      await openSeatsStep(page, 'no-llm-e2e')
      await expect.poll(() => buffers.sent.some((f) => f?.action === 'getLlmProviders')).toBe(true)
      expect(await llmOptionValues(page)).toEqual([])
      await expect(page.getByTestId('create-llm-refresh')).toHaveCount(0)
      await page.getByTestId('seat-type-0').getByRole('radio', { name: /Mad/ }).click()
      await createTable(page)
      const create = buffers.sent.find((f) => f?.action === 'createTable') as { args?: { llmSeats?: unknown } } | undefined
      expect(create?.args?.llmSeats).toBeUndefined()
      await expect(page.getByTestId('staging-error')).toHaveCount(0)
      expect(buffers.pageErrors).toEqual([])
    })
  })
})
