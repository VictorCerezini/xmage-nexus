import { afterEach, describe, expect, it, vi } from 'vitest'
import { canReadClipboard, copyText } from './clipboard'

const setClipboard = (value: unknown) => Object.defineProperty(navigator, 'clipboard', { value, configurable: true })

describe('copyText', () => {
  afterEach(() => {
    setClipboard(undefined)
    vi.restoreAllMocks()
  })

  it('uses the clipboard where the page has one', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    setClipboard({ writeText })
    await expect(copyText('4 Lightning Bolt')).resolves.toBe(true)
    expect(writeText).toHaveBeenCalledWith('4 Lightning Bolt')
  })

  it("copies through a textarea on a friend's http page, which has none", async () => {
    setClipboard(undefined)
    let copied = ''
    const exec = vi.fn(() => {
      copied = (document.activeElement as HTMLTextAreaElement).value
      return true
    })
    Object.defineProperty(document, 'execCommand', { value: exec, configurable: true, writable: true })
    const button = document.body.appendChild(document.createElement('button'))
    button.focus()

    await expect(copyText('4 Lightning Bolt')).resolves.toBe(true)

    expect(exec).toHaveBeenCalledWith('copy')
    expect(copied).toBe('4 Lightning Bolt')
    expect(document.querySelector('textarea')).toBeNull()
    expect(document.activeElement).toBe(button)
    button.remove()
  })

  it('says so when nothing could copy', async () => {
    setClipboard({ writeText: () => Promise.reject(new Error('denied')) })
    Object.defineProperty(document, 'execCommand', { value: () => false, configurable: true, writable: true })
    await expect(copyText('x')).resolves.toBe(false)
  })
})

describe('canReadClipboard', () => {
  afterEach(() => setClipboard(undefined))

  it('is only true where the page may read the clipboard itself', () => {
    setClipboard(undefined)
    expect(canReadClipboard()).toBe(false)
    setClipboard({ writeText: vi.fn() })
    expect(canReadClipboard()).toBe(false)
    setClipboard({ readText: vi.fn(), writeText: vi.fn() })
    expect(canReadClipboard()).toBe(true)
  })
})
