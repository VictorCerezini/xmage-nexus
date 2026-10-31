import { tableZoomOf } from './tableView'

/** Rect of an element at rest: the layout box with its computed `translate`
 *  subtracted, so an in-flight slide (draw slot, fan reflow) does not move the
 *  measured destination. */
export function restingRect(el: HTMLElement): DOMRect | null {
  const rect = el.getBoundingClientRect()
  if (rect.width <= 0 || rect.height <= 0) return null
  const [tx = 0, ty = 0] = getComputedStyle(el).translate.split(' ').map((v) => parseFloat(v) || 0)
  const zoom = tableZoomOf(el)
  const width = el.offsetWidth ? el.offsetWidth * zoom : rect.width
  const height = el.offsetHeight ? el.offsetHeight * zoom : rect.height
  const left = rect.left + rect.width / 2 - tx * zoom - width / 2
  const top = rect.top + rect.height / 2 - ty * zoom - height / 2
  return { left, top, width, height, right: left + width, bottom: top + height, x: left, y: top } as DOMRect
}
