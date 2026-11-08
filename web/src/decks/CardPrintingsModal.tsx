import CloseButton from '../ui/CloseButton'
import { useState, useEffect } from 'react'
import { scryfallCardImage, type ScryfallSearchCard } from './scryfallSearch'
import Icon from '../ui/Icon'
import Button from '../ui/Button'
import DialogShell from '../ui/DialogShell'
import { useTranslation } from '../i18n'
import { scryfallFetch } from '../cards/scryfallClient'
import { CustomCardArtSection } from './CustomCardArtSection'
import './CardPrintingsModal.css'

export interface CardPrinting {
  id: string
  set: string
  setName: string
  collectorNumber: string
  releasedAt: string
  rarity: string
  imageUrl: string | null
  artCropUrl?: string
}

/** A `/cards/search?unique=prints` answer: a page of printings. */
type ScryfallPrintsJson = { data?: (ScryfallSearchCard & { set_name?: string })[] } | null | undefined

export function parseScryfallPrints(json: unknown): CardPrinting[] {
  const data = json as ScryfallPrintsJson
  if (!data || !Array.isArray(data.data)) return []
  return data.data.map((item) => ({
    id: item.id,
    set: (item.set || '').toUpperCase(),
    setName: item.set_name || item.set || '',
    collectorNumber: item.collector_number || '',
    releasedAt: item.released_at || '',
    rarity: item.rarity || 'common',
    imageUrl: scryfallCardImage(item),
    artCropUrl: item.image_uris?.art_crop || item.card_faces?.[0]?.image_uris?.art_crop,
  }))
}

export function CardPrintingsModal({
  cardName,
  currentSet,
  currentNumber,
  onSelectPrinting,
  onClose,
  restoreDefault,
  token = false,
}: {
  cardName: string
  currentSet: string
  currentNumber: string
  onSelectPrinting: (setCode: string, cardNumber: string) => void
  onClose: () => void
  /** During a match, when this card's art was changed: puts its default art back (`hint` says which). */
  restoreDefault?: { hint: string; onRestore: () => void }
  /** A token in a game: Scryfall only lists tokens with `include:extras`, and names them without the
   * " Token" the game adds ("Zombie Token" → "Zombie"). */
  token?: boolean
}) {
  const { t } = useTranslation()
  const [printings, setPrintings] = useState<CardPrinting[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setError(null)

    const name = token ? cardName.replace(/\s+Token$/i, '').trim() || cardName : cardName
    const extras = token ? '+include:extras' : ''
    const url = `https://api.scryfall.com/cards/search?q=!%22${encodeURIComponent(name)}%22${extras}&unique=prints&order=released&dir=desc`

    scryfallFetch(url, { urgent: true })
      .then((res) => {
        // Scryfall answers 404 when nothing matches: there are no printings, it is not an error
        if (res.status === 404) return { data: [] }
        if (!res.ok) throw new Error(`${t('errors', 'generic_error')} (${res.status})`)
        return res.json()
      })
      .then((json) => {
        if (cancelled) return
        const prints = parseScryfallPrints(json)
        setPrintings(prints)
      })
      .catch((err) => {
        if (cancelled) return
        setError(err.message || t('errors', 'generic_error'))
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })

    return () => {
      cancelled = true
    }
  }, [cardName, token, t])

  return (
    <DialogShell
      labelledBy="printings-title"
      titleId="printings-title"
      size="lg"
      legacyBackdropClass="printings-backdrop"
      legacyPanelClass="printings-modal"
      kickerIcon="palette"
      kickerLabel={cardName}
      title={t('dialogs', 'card_printings_title')}
      topRight={(
        <CloseButton variant="plain" size="md" className="printings-close-btn" onClick={onClose} />
      )}
      onBackdropClick={onClose}
      onEscape={onClose}
    >
        <div className="printings-body">
          <CustomCardArtSection cardName={cardName} />

          {restoreDefault && (
            <div className="printings-restore">
              <Button
                icon="undo"
                size="sm"
                onClick={() => {
                  restoreDefault.onRestore()
                  onClose()
                }}
              >
                {t('decks', 'art_restore_default')}
              </Button>
              <span className="printings-restore-hint">{restoreDefault.hint}</span>
            </div>
          )}

          {loading && (
            <div className="printings-status-box">
              <div className="printings-spinner" />
              <span>{t('common', 'loading')} {cardName}…</span>
            </div>
          )}

          {error && !loading && (
            <div className="printings-status-box error">
              <span><Icon name="alert" size={13} /> {error}</span>
            </div>
          )}

          {!loading && !error && printings.length === 0 && (
            <div className="printings-status-box">
              <span>{t('decks', 'sample_no_cards')}</span>
            </div>
          )}

          {!loading && printings.length > 0 && (
            <div className="printings-grid">
              {printings.map((p) => {
                const isSelected =
                  p.set.toLowerCase() === currentSet.toLowerCase() &&
                  p.collectorNumber === currentNumber

                return (
                  <div
                    key={p.id}
                    className={`printing-card-item ${isSelected ? 'selected' : ''}`}
                    onClick={() => {
                      // Any Scryfall printing works: the image comes from Scryfall, and other
                      // players get the choice through the game chat (cards/sharedArt.ts).
                      onSelectPrinting(p.set, p.collectorNumber)
                      onClose()
                    }}
                  >
                    <div className="printing-img-wrap">
                      <img src={p.imageUrl ?? undefined} alt={`${cardName} (${p.set})`} loading="lazy" />
                      {isSelected && <div className="printing-selected-badge">✓ {t('common', 'done')}</div>}
                    </div>

                    <div className="printing-info">
                      <span className="printing-set-name" title={p.setName}>
                        {p.setName}
                      </span>
                      <div className="printing-meta-row">
                        <span className="printing-set-code">{p.set} #{p.collectorNumber}</span>
                        <span className={`printing-rarity ${p.rarity}`}>{p.rarity}</span>
                      </div>
                    </div>
                  </div>
                )
              })}
            </div>
          )}
        </div>
    </DialogShell>
  )
}
