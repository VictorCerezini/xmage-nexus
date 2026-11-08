import { describe, expect, it, vi, afterEach } from 'vitest'
import { render, screen, fireEvent, cleanup, waitFor } from '@testing-library/react'
import { CardPrintingsModal, parseScryfallPrints } from './CardPrintingsModal'
import { setCustomCardArtDataUrl, resetCustomCardArtCache } from '../cards/customCardArt'

describe('CardPrintingsModal', () => {
  afterEach(() => {
    cleanup()
    vi.restoreAllMocks()
    resetCustomCardArtCache()
  })

  const mockPrintsData = {
    data: [
      {
        id: 'print-1',
        set: 'dmu',
        set_name: 'Dominaria United',
        collector_number: '137',
        released_at: '2022-09-09',
        rarity: 'uncommon',
        image_uris: { normal: 'https://cards.scryfall.io/dmu-137.jpg' },
      },
      {
        id: 'print-2',
        set: 'm10',
        set_name: 'Magic 2010',
        collector_number: '146',
        released_at: '2009-07-17',
        rarity: 'common',
        image_uris: { normal: 'https://cards.scryfall.io/m10-146.jpg' },
      },
    ],
  }

  it('parses Scryfall prints response correctly', () => {
    const prints = parseScryfallPrints(mockPrintsData)
    expect(prints).toHaveLength(2)
    expect(prints[0].set).toBe('DMU')
    expect(prints[0].setName).toBe('Dominaria United')
    expect(prints[0].collectorNumber).toBe('137')
    expect(prints[1].set).toBe('M10')
  })

  it('renders printings grid and allows selecting an edition', async () => {
    globalThis.fetch = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => mockPrintsData,
    }) as any

    const onSelect = vi.fn()
    const onClose = vi.fn()

    render(
      <CardPrintingsModal
        cardName="Lightning Bolt"
        currentSet="M10"
        currentNumber="146"
        onSelectPrinting={onSelect}
        onClose={onClose}
      />
    )

    expect(screen.getByText('Seleccionar Edición & Arte')).toBeDefined()
    expect(screen.getByText('Lightning Bolt')).toBeDefined()

    await waitFor(() => {
      expect(screen.getByText('Dominaria United')).toBeDefined()
    })

    const dmuCard = screen.getByText('Dominaria United')
    fireEvent.click(dmuCard)

    expect(onSelect).toHaveBeenCalledWith('DMU', '137')
    expect(onClose).toHaveBeenCalled()
  })

  it('CRD-7: no printing is marked unavailable, and any one can be picked', async () => {
    globalThis.fetch = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => mockPrintsData,
    }) as any
    const onSelect = vi.fn()

    const { container } = render(
      <CardPrintingsModal
        cardName="Lightning Bolt"
        currentSet="M10"
        currentNumber="146"
        onSelectPrinting={onSelect}
        onClose={vi.fn()}
      />
    )

    await waitFor(() => {
      expect(screen.getByText('Dominaria United')).toBeDefined()
    })
    expect(container.querySelectorAll('.printing-card-item')).toHaveLength(2)
    expect(container.querySelector('.is-unavailable, [data-unavailable], .printing-unavailable-badge')).toBeNull()
    // the fetches made are Scryfall's only: nothing asks the server which printings it has
    for (const [url] of vi.mocked(globalThis.fetch).mock.calls) expect(String(url)).toContain('api.scryfall.com')
    fireEvent.click(screen.getByText('Magic 2010'))
    expect(onSelect).toHaveBeenCalledWith('M10', '146')
  })

  const tokenPrintsData = {
    data: [
      {
        id: 'zombie-1',
        set: 'tm21',
        set_name: 'Core Set 2021 Tokens',
        collector_number: '11',
        released_at: '2020-07-03',
        rarity: 'common',
        image_uris: { normal: 'https://cards.scryfall.io/tm21-11.jpg' },
      },
    ],
  }
  const searched = () => vi.mocked(globalThis.fetch).mock.calls.map(([url]) => String(url))

  it('CRD-10: a token is looked up among Scryfall\'s token printings, by its name without " Token"', async () => {
    globalThis.fetch = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => tokenPrintsData }) as any
    const onSelect = vi.fn()

    render(
      <CardPrintingsModal cardName="Zombie Token" currentSet="M21" currentNumber="0" onSelectPrinting={onSelect} onClose={vi.fn()} token />
    )

    await waitFor(() => {
      expect(screen.getByText('Core Set 2021 Tokens')).toBeDefined()
    })
    expect(searched()).toHaveLength(1)
    expect(searched()[0]).toContain('q=!%22Zombie%22+include:extras&')
    fireEvent.click(screen.getByText('Core Set 2021 Tokens'))
    expect(onSelect).toHaveBeenCalledWith('TM21', '11')
  })

  it('CRD-10: a card is looked up by its whole name, without token printings', async () => {
    globalThis.fetch = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => mockPrintsData }) as any

    render(
      <CardPrintingsModal cardName="Lightning Bolt" currentSet="M10" currentNumber="146" onSelectPrinting={vi.fn()} onClose={vi.fn()} />
    )

    await waitFor(() => {
      expect(screen.getByText('Dominaria United')).toBeDefined()
    })
    expect(searched()[0]).toContain('q=!%22Lightning%20Bolt%22&')
    expect(searched()[0]).not.toContain('include:extras')
  })

  it('CRD-10: when Scryfall finds no printing (it answers 404) the menu says so, it shows no error', async () => {
    globalThis.fetch = vi.fn().mockResolvedValue({ ok: false, status: 404, json: async () => ({ object: 'error', status: 404 }) }) as any

    const { container } = render(
      <CardPrintingsModal cardName="Nonexistent Token" currentSet="" currentNumber="" onSelectPrinting={vi.fn()} onClose={vi.fn()} token />
    )

    await waitFor(() => {
      expect(screen.getByText('No hay cartas que mostrar')).toBeDefined()
    })
    expect(container.querySelector('.printings-status-box.error')).toBeNull()
    expect(container.textContent).not.toContain('404')
  })

  it('CRD-10: any other failed answer still shows an error', async () => {
    globalThis.fetch = vi.fn().mockResolvedValue({ ok: false, status: 500, json: async () => ({}) }) as any

    const { container } = render(
      <CardPrintingsModal cardName="Lightning Bolt" currentSet="" currentNumber="" onSelectPrinting={vi.fn()} onClose={vi.fn()} />
    )

    await waitFor(() => {
      expect(container.querySelector('.printings-status-box.error')).not.toBeNull()
    })
    expect(container.querySelector('.printings-status-box.error')!.textContent).toContain('500')
    expect(screen.queryByText('No hay cartas que mostrar')).toBeNull()
  })

  it('shows the uploaded custom image and removes it from the custom art section', async () => {
    globalThis.fetch = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => mockPrintsData,
    }) as any

    render(
      <CardPrintingsModal
        cardName="Lightning Bolt"
        currentSet="M10"
        currentNumber="146"
        onSelectPrinting={vi.fn()}
        onClose={vi.fn()}
      />
    )

    const section = screen.getByTestId('custom-art-section')
    expect(section).toBeDefined()
    expect(screen.getByText('Subir imagen')).toBeDefined()

    setCustomCardArtDataUrl('Lightning Bolt', 'data:image/jpeg;base64,CUSTOM')
    await waitFor(() => {
      expect(section.querySelector('img.custom-art-thumb')).not.toBeNull()
    })
    expect(section.querySelector('img.custom-art-thumb')!.getAttribute('src')).toBe('data:image/jpeg;base64,CUSTOM')

    fireEvent.click(screen.getByLabelText('Quitar imagen'))
    await waitFor(() => {
      expect(section.querySelector('img.custom-art-thumb')).toBeNull()
    })
    expect(screen.getByText('Subir imagen')).toBeDefined()
  })
})
