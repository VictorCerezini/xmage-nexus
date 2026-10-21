/**
 * Copies text, also where the browser keeps navigator.clipboard away: it only gives it to secure
 * pages, and a friend's page at the host's http address is not one. The old copy of a selected
 * textarea still works there. Call it from the click itself, the only time a browser lets a page copy.
 */
export async function copyText(text: string): Promise<boolean> {
  try {
    if (typeof navigator !== 'undefined' && navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text)
      return true
    }
  } catch {
    // denied or unfocused: the textarea below may still copy
  }
  return copyThroughTextarea(text)
}

function copyThroughTextarea(text: string): boolean {
  if (typeof document === 'undefined') return false
  const focused = document.activeElement as HTMLElement | null
  const area = document.createElement('textarea')
  area.value = text
  area.setAttribute('readonly', '')
  area.style.position = 'fixed'
  area.style.top = '0'
  area.style.left = '-9999px'
  document.body.appendChild(area)
  try {
    area.focus()
    area.select()
    return document.execCommand('copy')
  } catch {
    return false
  } finally {
    area.remove()
    focused?.focus?.()
  }
}

/**
 * Whether a page may read the clipboard itself (a Paste button). Only a secure page may: on a
 * friend's page the player pastes into the box with Ctrl+V, which every browser allows.
 */
export function canReadClipboard(): boolean {
  return typeof navigator !== 'undefined' && typeof navigator.clipboard?.readText === 'function'
}
