/** Where the whole game interface sits while it is zoomed. */
export interface TableView {
  scale: number
  x: number
  y: number
}

export const TABLE_VIEW_FIT: TableView = { scale: 1, x: 0, y: 0 }
export const MAX_TABLE_ZOOM = 8
const ZOOM_STEP = 1.15

/** What a press on these already means, so it must not start dragging the view: buttons and
 *  fields, the hand (dragged to reorder), and cards the player can cast, activate or choose. */
const PRESS_HAS_A_MEANING = [
  'button', 'a', 'input', 'select', 'textarea', 'label', 'summary', '[contenteditable]', '[draggable="true"]',
  '[role="button"]:not(.card-slot)', '[role="separator"]', '[role="slider"]', '[role="tab"]', '[role="menuitem"]',
  '.hand-card-slot', '.playable', '.targetable', '.chosen',
].join(', ')

/** Keeps the interface covering the screen, so the view can never be dragged off it. */
export function clampTableView(view: TableView, width: number, height: number): TableView {
  const scale = Math.min(MAX_TABLE_ZOOM, Math.max(1, view.scale))
  const clamp = (value: number, size: number) => Math.min(0, Math.max(size - size * scale, value))
  return { scale, x: clamp(view.x, width), y: clamp(view.y, height) }
}

/** One wheel notch toward or away from the pointer: the point under it stays where it is. */
export function zoomTableView(view: TableView, deltaY: number, pointerX: number, pointerY: number, width: number, height: number): TableView {
  if (deltaY === 0) return view
  const scale = Math.min(MAX_TABLE_ZOOM, Math.max(1, deltaY < 0 ? view.scale * ZOOM_STEP : view.scale / ZOOM_STEP))
  const ratio = scale / view.scale
  return clampTableView({
    scale,
    x: pointerX - (pointerX - view.x) * ratio,
    y: pointerY - (pointerY - view.y) * ratio,
  }, width, height)
}

export function panTableView(view: TableView, dx: number, dy: number, width: number, height: number): TableView {
  return clampTableView({ ...view, x: view.x + dx, y: view.y + dy }, width, height)
}

/** How much the zoom scales this element on screen; 1 outside the zoomed interface. */
export function tableZoomOf(el: Element): number {
  const stage = el.closest<HTMLElement>('.game-stage')
  if (!stage || stage.offsetWidth <= 0) return 1
  const zoom = stage.getBoundingClientRect().width / stage.offsetWidth
  return zoom > 0 ? zoom : 1
}

/** True when pressing the left button here can start dragging the view: not on anything the
 *  press already means something for, and not on a list, whose text and scrollbar it would take. */
export function leftButtonDrags(target: EventTarget | null, stage: HTMLElement): boolean {
  let el = target instanceof Element ? target : null
  while (el && el !== stage) {
    if (el.matches(PRESS_HAS_A_MEANING)) return false
    const { overflowX, overflowY } = getComputedStyle(el)
    const scrolls = (overflow: string) => overflow === 'auto' || overflow === 'scroll'
    if ((scrolls(overflowY) && el.scrollHeight > el.clientHeight) || (scrolls(overflowX) && el.scrollWidth > el.clientWidth)) return false
    el = el.parentElement
  }
  return true
}

/** True when the wheel should scroll a list under the pointer (log, chat, stack) instead of zooming. */
export function wheelScrollsContent(target: EventTarget | null, stage: HTMLElement, deltaY: number): boolean {
  let el = target instanceof Element ? target : null
  while (el && el !== stage) {
    if (el.scrollHeight > el.clientHeight) {
      const { overflowY } = getComputedStyle(el)
      if (overflowY === 'auto' || overflowY === 'scroll') {
        const atEnd = deltaY > 0
          ? el.scrollTop + el.clientHeight >= el.scrollHeight - 1
          : el.scrollTop <= 0
        if (!atEnd) return true
      }
    }
    el = el.parentElement
  }
  return false
}
