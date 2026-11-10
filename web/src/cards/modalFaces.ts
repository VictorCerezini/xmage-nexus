import type { CardView } from '../net/types'

/**
 * The faces of the modal double-faced cards seen so far, learnt from Scryfall: back-face name → front-face
 * name. A card played from its back face whose front face is not a permanent (a spell // land card
 * played as the land) is not "transformed" for the server: it sends that face's name with the main
 * card's set and number, and nothing else tells the faces apart. Its name is the only sign that the
 * back face is up.
 */
const frontByBack = new Map<string, string>()
const faceKey = (name: string) => name.trim().toLowerCase()

/** Remembers the faces of a modal double-faced card (Scryfall `layout: modal_dfc`). */
export function learnModalFaces(frontName: string | undefined, backName: string | undefined): void {
  if (!frontName?.trim() || !backName?.trim()) return
  frontByBack.set(faceKey(backName), frontName.trim())
}

/** A modal double-faced card showing its back face (a land, say), not one the server marks as transformed. */
export function isModalBackFace(card: Pick<CardView, 'name' | 'transformed'>): boolean {
  return card.transformed !== true && !!card.name?.trim() && frontByBack.has(faceKey(card.name))
}

/** The front-face name of the card showing this back face, or null (see `isModalBackFace`). */
export function modalFrontFaceName(card: Pick<CardView, 'name' | 'transformed'>): string | null {
  return isModalBackFace(card) ? (frontByBack.get(faceKey(card.name)) ?? null) : null
}

/** Only for tests and for clearing the image cache. */
export function resetModalFaces(): void {
  frontByBack.clear()
}
