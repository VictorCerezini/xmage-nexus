import { useSyncExternalStore } from 'react'
import type { Deck } from './decks'
import { fetchDeckIssues, fixPrintingsQuietly, withoutPrintingOnlyIssues } from '../decks/deckIssues'
import { rememberDeckArt } from '../decks/deckArt'
import type { DeckMismatchCard, DeckMissingCard, DeckValidationResult } from '../net/types'
import DialogShell from '../ui/DialogShell'
import { useTranslation } from '../i18n'
import './DeckIssuesDialog.css'
import Button from '../ui/Button'

/**
 * Pre-join check. A card the server only lacks in this printing is moved to a printing it has,
 * without asking: the deck's printing stays the card's art in the game (`decks/deckArt.ts`). The
 * dialog only opens for cards the server cannot play at all (rejected, or loaded as another card
 * with no printing of the right one), and offers to remove them and play.
 *
 * Promise API: `requestDeckValidation(deck)` resolves with the deck to send, or null if the player
 * cancels.
 */

interface ActiveRequest {
  deck: Deck
  report: DeckValidationResult
  resolve: (deck: Deck | null) => void
}

let active: ActiveRequest | null = null
let listener: (() => void) | null = null

function notify() {
  listener?.()
}

function subscribe(fn: () => void) {
  listener = fn
  return () => {
    listener = null
  }
}

/**
 * Valida el mazo y, si el servidor lo rechazaría, muestra el diálogo.
 * Resuelve con el mazo (corregido si procede) o null si el usuario cancela.
 */
export function requestDeckValidation(deck: Deck): Promise<Deck | null> {
  return (async () => {
    const report = await fetchDeckIssues(deck)
    if (!report) {
      void rememberDeckArt([])
      return deck
    }
    const quiet = fixPrintingsQuietly(deck, report)
    // the art is only kept once the deck is really sent: a cancelled join leaves the last one as it was
    const send = (d: Deck | null): Deck | null => {
      if (d) void rememberDeckArt(quiet.arts)
      return d
    }
    let left = withoutPrintingOnlyIssues(report)
    if (left.missing.length === 0 && left.mismatches.length === 0) return send(quiet.deck)
    // the dialog's "remove and play" deck must come from the deck as it will be sent
    if (quiet.fixed > 0) {
      const again = await fetchDeckIssues(quiet.deck)
      // without a fresh report the proxy's "deck without the rejected cards" would also drop the
      // cards just fixed: offer no removal then, only cancel
      left = again ? withoutPrintingOnlyIssues(again) : { ...left, fixedDeck: undefined }
      if (left.missing.length === 0 && left.mismatches.length === 0) return send(quiet.deck)
    }
    const shown = left
    return new Promise<Deck | null>((resolve) => {
      active = { deck: quiet.deck, report: shown, resolve: (d) => resolve(send(d)) }
      notify()
    })
  })()
}

function closeRequest(result: Deck | null) {
  active?.resolve(result)
  active = null
  notify()
}

export default function DeckIssuesDialog() {
  const req = useSyncExternalStore(subscribe, () => active, () => active)
  const { t } = useTranslation()
  if (!req) {
    return null
  }
  const { deck, report } = req
  const missing: DeckMissingCard[] = report.missing
  const mismatches: DeckMismatchCard[] = report.mismatches
  const removable = missing.reduce((acc, c) => acc + c.amount, 0)

  return (
    <DialogShell
      labelledBy="deck-issues-title"
      titleId="deck-issues-title"
      testId="deck-issues-dialog"
      legacyBackdropClass="modal-backdrop"
      legacyPanelClass="deck-issues-modal"
      kickerIcon="alert"
      kickerLabel={deck.name}
      title={t('decks', 'issues_title')}
      message={t('decks', 'issues_intro')}
    >
        <div className="deck-issues-body">
          {missing.length > 0 && (
            <section className="deck-issues-section">
              <h3>{t('decks', 'issues_missing_section')}</h3>
              <ul data-testid="deck-issues-missing-list">
                {missing.map((c) => (
                  <li key={`${c.cardName}|${c.setCode}|${c.cardNumber}`} className="deck-issue-row" data-testid="deck-issue-row">
                    <span className="deck-issue-card">
                      {c.amount}× <strong>{c.cardName}</strong>{' '}
                      <span className="deck-issue-printing">({c.setCode} #{c.cardNumber})</span>
                    </span>
                    <span className="deck-issue-reason">
                      {c.reason === 'OUTDATED_PRINTING'
                        ? t('decks', 'issues_reason_outdated')
                        : t('decks', 'issues_reason_unimplemented')}
                    </span>
                  </li>
                ))}
              </ul>
            </section>
          )}

          {mismatches.length > 0 && (
            <section className="deck-issues-section">
              <h3>{t('decks', 'issues_mismatch_section')}</h3>
              <ul data-testid="deck-issues-mismatch-list">
                {mismatches.map((c) => (
                  <li key={`${c.cardName}|${c.setCode}|${c.cardNumber}`} className="deck-issue-row">
                    <span className="deck-issue-card">
                      {c.amount}× <strong>{c.cardName}</strong>{' '}
                      <span className="deck-issue-printing">({c.setCode} #{c.cardNumber})</span>
                    </span>
                    <span className="deck-issue-reason">{t('decks', 'issues_mismatch_resolved', { resolved: c.resolvedName })}</span>
                  </li>
                ))}
              </ul>
            </section>
          )}
        </div>

        <div className="deck-issues-footer">
          <Button variant="subtle" type="button" data-testid="deck-issues-cancel" onClick={() => closeRequest(null)}>
            {t('decks', 'issues_cancel')}
          </Button>
          {missing.length === 0 && mismatches.length > 0 && (
            <Button variant="primary"
              type="button"
              className="deck-issues-play-btn"
              data-testid="deck-issues-accept-and-play"
              onClick={() => closeRequest(deck)}
            >
              {t('decks', 'issues_accept_and_play')}
            </Button>
          )}
          {missing.length > 0 && report.fixedDeck && (
            <Button variant="primary"
              type="button"
              className="deck-issues-play-btn"
              data-testid="deck-issues-remove-and-play"
              onClick={() => closeRequest({ name: deck.name, cards: report.fixedDeck!.cards, sideboard: report.fixedDeck!.sideboard })}
            >
              {t('decks', 'issues_remove_and_play', { count: removable })}
            </Button>
          )}
        </div>
    </DialogShell>
  )
}
