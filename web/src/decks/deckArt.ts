import { fetchCardJson } from '../cards/scryfallCards'
import { forgetOwnArtDefaults, setDeckArt, type DeckArtEntry } from '../cards/sharedArt'
import { foldCardName, type DeckArtSwap } from './deckIssues'

let generation = 0

function namesOf(json: { name?: string; card_faces?: { name?: string }[] }): string[] {
  const names = [json.name ?? '', ...(json.name ?? '').split(' // '), ...(json.card_faces ?? []).map((f) => f.name ?? '')]
  return names.map((n) => n.trim()).filter(Boolean)
}

/**
 * Keeps the printings a deck had for the cards the server only has in another printing, so this
 * player and the others still see that art in the game (`cards/sharedArt.ts`). Each printing is
 * checked on Scryfall first: one that does not exist there, or is another card, is left out (the
 * card then shows the printing the server sent). The art is kept under every face name of the card
 * (a transformed card, an adventure on the stack, one half of a split card). Runs in the background
 * and never delays a join. An empty list forgets the last deck's art. A new deck starts a new match,
 * so the art changes made during the last one can no longer be undone then.
 */
export function rememberDeckArt(swaps: DeckArtSwap[]): Promise<void> {
  const mine = ++generation
  forgetOwnArtDefaults()
  setDeckArt([])
  if (swaps.length === 0) return Promise.resolve()
  return Promise.all(
    swaps.map(async ({ art, sent, only }): Promise<DeckArtEntry | null> => {
      try {
        const json = await fetchCardJson(art, { fallbackToName: false })
        if (!json) return null
        const faces = namesOf(json)
        if (!faces.map(foldCardName).includes(foldCardName(art.cardName))) return null
        return {
          names: [...new Set([art.cardName, sent.cardName, ...faces].map((n) => n.trim()).filter(Boolean))],
          on: { setCode: sent.setCode, cardNumber: sent.cardNumber },
          art: { setCode: art.setCode, cardNumber: art.cardNumber },
          only,
        }
      } catch {
        return null
      }
    }),
  ).then((checked) => {
    if (mine !== generation) return
    setDeckArt(checked.filter((a): a is DeckArtEntry => !!a))
  })
}
