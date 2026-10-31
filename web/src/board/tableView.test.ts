import { describe, expect, it } from 'vitest'
import { clampTableView, leftButtonDrags, MAX_TABLE_ZOOM, panTableView, TABLE_VIEW_FIT, wheelScrollsContent, zoomTableView } from './tableView'

const W = 1000
const H = 500

describe('table zoom', () => {
  it('zooms in on the point under the pointer', () => {
    const view = zoomTableView(TABLE_VIEW_FIT, -100, 200, 100, W, H)
    expect(view.scale).toBeCloseTo(1.15)
    expect((200 - view.x) / view.scale).toBeCloseTo(200)
    expect((100 - view.y) / view.scale).toBeCloseTo(100)
  })

  it('allows at least 800%', () => {
    expect(MAX_TABLE_ZOOM).toBeGreaterThanOrEqual(8)
  })

  it('never goes below the whole table or above the limit', () => {
    expect(zoomTableView(TABLE_VIEW_FIT, 100, 200, 100, W, H)).toEqual(TABLE_VIEW_FIT)
    let view = TABLE_VIEW_FIT
    for (let i = 0; i < 40; i++) view = zoomTableView(view, -100, 500, 250, W, H)
    expect(view.scale).toBe(MAX_TABLE_ZOOM)
  })

  it('zooming back out to the whole table leaves no offset', () => {
    let view = TABLE_VIEW_FIT
    for (let i = 0; i < 5; i++) view = zoomTableView(view, -100, 900, 450, W, H)
    expect(view.x).toBeLessThan(0)
    for (let i = 0; i < 20; i++) view = zoomTableView(view, 100, 10, 10, W, H)
    expect(view).toEqual(TABLE_VIEW_FIT)
  })

  it('a wheel event with no vertical movement changes nothing', () => {
    expect(zoomTableView(TABLE_VIEW_FIT, 0, 10, 10, W, H)).toBe(TABLE_VIEW_FIT)
  })

  it('cannot be dragged at the whole table', () => {
    expect(panTableView(TABLE_VIEW_FIT, 300, -300, W, H)).toEqual(TABLE_VIEW_FIT)
  })

  it('drags only as far as the edges of the interface when zoomed', () => {
    const zoomed = { scale: 2, x: -100, y: -100 }
    expect(panTableView(zoomed, -50, 40, W, H)).toEqual({ scale: 2, x: -150, y: -60 })
    expect(panTableView(zoomed, 5000, 5000, W, H)).toEqual({ scale: 2, x: 0, y: 0 })
    expect(panTableView(zoomed, -5000, -5000, W, H)).toEqual({ scale: 2, x: -W, y: -H })
  })

  it('a smaller window pulls a zoomed view back inside', () => {
    expect(clampTableView({ scale: 2, x: -1000, y: -500 }, 400, 200)).toEqual({ scale: 2, x: -400, y: -200 })
  })
})

describe('leftButtonDrags', () => {
  function pressOn(html: string) {
    const stage = document.createElement('div')
    stage.innerHTML = html
    return leftButtonDrags(stage.querySelector('#press'), stage)
  }

  it('drags from empty table and from a card with nothing to do', () => {
    expect(pressOn('<div><span id="press"></span></div>')).toBe(true)
    expect(pressOn('<div class="card-slot clickable" role="button"><img id="press"></div>')).toBe(true)
  })

  it('leaves castable, targetable and chosen cards alone', () => {
    for (const state of ['playable', 'targetable', 'chosen']) {
      expect(pressOn(`<div class="card-slot ${state}" role="button"><img id="press"></div>`)).toBe(false)
    }
  })

  it('leaves buttons, the hand and resize handles alone', () => {
    expect(pressOn('<button><span id="press"></span></button>')).toBe(false)
    expect(pressOn('<div role="button"><span id="press"></span></div>')).toBe(false)
    expect(pressOn('<div class="hand-card-slot"><div class="card-slot"><img id="press"></div></div>')).toBe(false)
    expect(pressOn('<div role="separator" id="press"></div>')).toBe(false)
  })

  it('leaves a list that scrolls alone', () => {
    const stage = document.createElement('div')
    stage.innerHTML = '<div id="list" style="overflow-y: auto"><p id="press"></p></div>'
    const list = stage.querySelector('#list')!
    Object.defineProperty(list, 'scrollHeight', { value: 500 })
    Object.defineProperty(list, 'clientHeight', { value: 100 })
    document.body.append(stage)
    expect(leftButtonDrags(stage.querySelector('#press'), stage)).toBe(false)
    stage.remove()
  })
})

describe('wheelScrollsContent', () => {
  function list(scrollTop: number) {
    const stage = document.createElement('div')
    const box = document.createElement('div')
    const row = document.createElement('span')
    box.style.overflowY = 'auto'
    Object.defineProperties(box, {
      scrollHeight: { value: 300 },
      clientHeight: { value: 100 },
      scrollTop: { value: scrollTop, writable: true },
    })
    box.append(row)
    stage.append(box)
    return { stage, row }
  }

  it('lets a list that can still move take the wheel', () => {
    const { stage, row } = list(50)
    expect(wheelScrollsContent(row, stage, 100)).toBe(true)
    expect(wheelScrollsContent(row, stage, -100)).toBe(true)
  })

  it('zooms once the list has reached its end, and over anything else', () => {
    const top = list(0)
    expect(wheelScrollsContent(top.row, top.stage, -100)).toBe(false)
    const bottom = list(200)
    expect(wheelScrollsContent(bottom.row, bottom.stage, 100)).toBe(false)
    expect(wheelScrollsContent(top.stage, top.stage, 100)).toBe(false)
  })
})
