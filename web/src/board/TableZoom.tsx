import { createContext, useContext, useEffect, useRef, useState } from 'react'
import type { ReactNode, RefObject } from 'react'
import { createPortal } from 'react-dom'
import { useTranslation } from '../i18n'
import Button from '../ui/Button'
import { BOARD_RELAYOUT_EVENT } from './layout/relayout'
import { clampTableView, leftButtonDrags, panTableView, TABLE_VIEW_FIT, wheelScrollsContent, zoomTableView, type TableView } from './tableView'
import './TableZoom.css'

const LEFT_BUTTON = 0
const MIDDLE_BUTTON = 1
/** A left press that moves less than this is still a click. */
const DRAG_START_PX = 5

/** The layer beside the zoomed interface; null when nothing is being zoomed. */
export const TableZoomLayerContext = createContext<HTMLElement | null>(null)

/** For layers placed from on-screen coordinates (card flights, previews, effects): inside the
 *  zoomed interface they would be scaled and moved a second time. */
export function OutsideTableZoom({ children }: { children: ReactNode }) {
  const layer = useContext(TableZoomLayerContext)
  return layer ? createPortal(children, layer) : <>{children}</>
}

/** Scroll to zoom the whole interface; once zoomed, drag it with the left button (from anywhere a
 *  press means nothing else) or the middle button (from anywhere). */
export default function TableZoom({ stageRef }: { stageRef: RefObject<HTMLElement | null> }) {
  const { t } = useTranslation()
  const [scale, setScale] = useState(1)
  const viewRef = useRef<TableView>(TABLE_VIEW_FIT)

  const apply = (view: TableView) => {
    const stage = stageRef.current
    if (!stage) return
    viewRef.current = view
    stage.style.transformOrigin = '0 0'
    stage.classList.toggle('is-table-zoomed', view.scale !== 1)
    stage.style.transform = view.scale === 1 ? '' : `translate(${view.x}px, ${view.y}px) scale(${view.scale})`
    setScale(view.scale)
    window.dispatchEvent(new Event(BOARD_RELAYOUT_EVENT))
  }
  const applyRef = useRef(apply)
  applyRef.current = apply

  useEffect(() => {
    const stage = stageRef.current
    if (!stage) return
    const size = () => ({ width: stage.offsetWidth, height: stage.offsetHeight })
    let drag: { pointerId: number; x: number; y: number; moving: boolean } | null = null
    let swallowClick = false

    const onWheel = (e: WheelEvent) => {
      if (wheelScrollsContent(e.target, stage, e.deltaY)) return
      e.preventDefault()
      const { width, height } = size()
      const origin = stage.parentElement?.getBoundingClientRect()
      applyRef.current(zoomTableView(viewRef.current, e.deltaY, e.clientX - (origin?.left ?? 0), e.clientY - (origin?.top ?? 0), width, height))
    }
    // The browser's own middle-button autoscroll starts from mousedown.
    const onMouseDown = (e: MouseEvent) => {
      if (e.button === MIDDLE_BUTTON) e.preventDefault()
    }
    const onAuxClick = (e: MouseEvent) => {
      if (e.button === MIDDLE_BUTTON) e.preventDefault()
    }
    const startMoving = (pointerId: number) => {
      stage.setPointerCapture?.(pointerId)
      stage.classList.add('is-table-dragging')
    }
    const onPointerDown = (e: PointerEvent) => {
      drag = null
      swallowClick = false
      if (viewRef.current.scale === 1) return
      if (e.button === MIDDLE_BUTTON) {
        e.preventDefault()
        drag = { pointerId: e.pointerId, x: e.clientX, y: e.clientY, moving: true }
        startMoving(e.pointerId)
      } else if (e.button === LEFT_BUTTON && e.pointerType === 'mouse' && leftButtonDrags(e.target, stage)) {
        drag = { pointerId: e.pointerId, x: e.clientX, y: e.clientY, moving: false }
      }
    }
    const onPointerMove = (e: PointerEvent) => {
      if (!drag || e.pointerId !== drag.pointerId) return
      if (!drag.moving) {
        if (e.buttons === 0) {
          drag = null
          return
        }
        if (Math.hypot(e.clientX - drag.x, e.clientY - drag.y) < DRAG_START_PX) return
        drag.moving = true
        swallowClick = true
        window.getSelection()?.removeAllRanges()
        startMoving(e.pointerId)
      }
      const { width, height } = size()
      applyRef.current(panTableView(viewRef.current, e.clientX - drag.x, e.clientY - drag.y, width, height))
      drag.x = e.clientX
      drag.y = e.clientY
    }
    const endDrag = (e: PointerEvent) => {
      if (!drag || e.pointerId !== drag.pointerId) return
      drag = null
      stage.classList.remove('is-table-dragging')
    }
    // The press that ends a drag is not a click on whatever it was started from.
    const onClick = (e: MouseEvent) => {
      if (!swallowClick) return
      swallowClick = false
      e.preventDefault()
      e.stopPropagation()
    }
    const onDragStart = (e: DragEvent) => {
      if (drag) e.preventDefault()
    }
    const onResize = () => {
      const { width, height } = size()
      applyRef.current(clampTableView(viewRef.current, width, height))
    }

    stage.addEventListener('wheel', onWheel, { passive: false })
    stage.addEventListener('mousedown', onMouseDown)
    stage.addEventListener('auxclick', onAuxClick)
    stage.addEventListener('pointerdown', onPointerDown)
    stage.addEventListener('pointermove', onPointerMove)
    stage.addEventListener('pointerup', endDrag)
    stage.addEventListener('pointercancel', endDrag)
    stage.addEventListener('click', onClick, true)
    stage.addEventListener('dragstart', onDragStart)
    window.addEventListener('resize', onResize)
    return () => {
      stage.removeEventListener('wheel', onWheel)
      stage.removeEventListener('mousedown', onMouseDown)
      stage.removeEventListener('auxclick', onAuxClick)
      stage.removeEventListener('pointerdown', onPointerDown)
      stage.removeEventListener('pointermove', onPointerMove)
      stage.removeEventListener('pointerup', endDrag)
      stage.removeEventListener('pointercancel', endDrag)
      stage.removeEventListener('click', onClick, true)
      stage.removeEventListener('dragstart', onDragStart)
      window.removeEventListener('resize', onResize)
      stage.classList.remove('is-table-dragging', 'is-table-zoomed')
      stage.style.transform = ''
      stage.style.transformOrigin = ''
    }
  }, [stageRef])

  if (scale === 1) return null
  return (
    <Button size="sm" icon="x" className="table-zoom-reset" data-testid="table-zoom-reset" title={t('lobby', 'all_boards_zoom_reset')} onClick={() => apply(TABLE_VIEW_FIT)}>
      {Math.round(scale * 100)}%
    </Button>
  )
}
