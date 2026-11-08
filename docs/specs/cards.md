# Looking at a card

Prefix: `CRD`

The card inspector: the read-only panel a right click on a card opens during a
game (`web/src/board/CardInspector.tsx`). It shows the card large next to what the printed card
cannot tell: its state on the battlefield and its text in the player's card language. The hover
preview is a different, lighter surface and is not covered here.

### CRD-1 · The inspector shows the battlefield state the printed card cannot
For a permanent on the battlefield, an "On the battlefield" section lists what differs from the
printed card: power/toughness when an effect changed them (in green when above the printed value,
red when below, with the printed value under it), damage marked this turn with the toughness left,
loyalty when it is not the starting loyalty, defense when it is not the starting defense, every
counter with its count, and the statuses tapped, summoning sick, phased out, token and copy. What it
is attached to and what is attached to it are buttons that open that permanent in the inspector.
Controller and owner close the text. Values the printed card already shows (an unmodified 1/1, a
planeswalker at its starting loyalty) are left out, and a card that is not on the battlefield has no
such section. The section reads the live game state, so it follows the game while the inspector is
open.

- Why: the first inspector only repeated the printed card, the one thing the player could already
  read from the large image. Leaving out unchanged values follows the board's P/T badge, and keeps
  the rules text above the fold at 1280 × 720, where the 70% cap of DLG-10 leaves about 450 px.
- Guard: `src/board/cardInspectorModel.test.ts` · `e2e/card-inspector.spec.ts`

### CRD-2 · Both faces can be shown with a pointer, a key or a tap
A card with a second face (transforming, modal double-faced, or one with an alternate face) shows a
Front / Back switch above its name. The face the permanent shows on the battlefield carries a dot.
Shift or F still flips (KEY-9). The battlefield section belongs to the face in play and is hidden
while the other face is shown.

- Why: the face could only be flipped from the keyboard, so on a phone or tablet the back face of a
  card could not be read at all, and the hint that named the keys was not even on screen there.
- Guard: `e2e/card-inspector.spec.ts`

### CRD-3 · Side by side up to 70% of the screen, a bottom sheet at phone width
On a wide screen the card and a fixed-width text column sit side by side, the panel as wide as its
content and as tall as DLG-10 allows; the text column scrolls between a fixed header and a footer,
and fades at the bottom while there is more to read. At 760 px or narrower the inspector is a sheet
rising from the bottom edge: the card on top, the text under it, the whole sheet scrolling, and the
footer action pinned to the bottom, 44 px tall. No label in it is clipped.

- Why: the panel grew to `min(1120px, 96vw)` around a card that did not fill it (about 150 px of
  empty space on each side), the rules box stretched to the bottom as an empty block, the ✕ sat on the
  mana cost, the "Custom art" button was a 24 × 24 icon button with its label cut to "magen", and at
  phone width the card overflowed the top of the panel under the name.
- Guard: `e2e/card-inspector.spec.ts`

### CRD-4 · The rules text reads as abilities
Each ability is its own paragraph. A planeswalker's loyalty abilities are rows with the cost as a
badge (light for +, grey for 0, dark for −); an ability the current loyalty cannot pay for is dimmed.
The keyword abilities the card has are explained under the rules, with the same glossary the hover
preview uses.

- Why: the text was one block with the loyalty costs as plain "+2:" prefixes and no space between
  abilities, and a new player had no way to learn what a keyword means without leaving the game.
- Guard: `src/board/cardInspectorModel.test.ts` · `e2e/card-inspector.spec.ts`

### CRD-5 · The header names the card the way a player reads it
Under the name and mana cost come the type line, the rarity (with its color) and the printing (set
code and collector number). With no translated text the type line is built from the card's
supertypes, types and subtypes ("Legendary Planeswalker — Jace"), never the engine's raw enum
("CREATURE"). The glow behind the card takes its color.

- Why: without a translated text the type line showed `CREATURE` with no subtypes, and the set and
  rarity, which the client already receives, were nowhere.
- Guard: `src/board/cardInspectorModel.test.ts`

### CRD-6 · Everyone sees the art a player picked for that player's cards
When a player picks an art for a card in the deck builder, the other players see that art on that
player's copies of the card, for any Scryfall printing (a change made during a match is for one copy
only, and they see it on that copy: CRD-10). The choice travels as a
hidden line in the game chat, and only once the card is in a public zone (battlefield, stack,
graveyard, exile, command zone), so it never tells anyone what is still in a hand or a library. The
line never shows in the chat, the log or a model seat's view. A player can still change the art of
someone else's card for themselves during a match (CRD-10); that change wins on their screen and does
not change their own deck's art. The art follows the card's owner, also while
another player controls it, and stays when a double-faced card transforms. A spectator who starts
watching mid-game asks the players to post their choices again, so it sees them too. The art goes
out from the first game screen that shows the card, also a prompt (who starts, a mulligan, a
target), so a commander shows its art from the start of the game, not only after the mulligans;
and it goes out as soon as the game chat is known, also when that is after the first game screen.

- Why: the art picked in the deck builder was stored only on the player's own computer, and the
  XMage server swaps any printing it does not know for one it does, so the other players saw the
  default image unless each of them changed it by hand. The first version only posted on full game
  updates, which the deciding player does not get during the "who starts" choice and the
  mulligans, so in a real Commander test the other player saw the default commander art for the
  whole mulligan phase.
- Guard: `src/cards/sharedArt.test.ts` · `src/state/sharedArtSync.test.ts` · `src/state/gateway-rejoin.test.ts`

### CRD-7 · The art picker never says a printing is unavailable
Every Scryfall printing in the art picker can be picked, and none is marked "not on server".

- Why: the badge compared against the server's own printing list, but the image always comes from
  Scryfall and the pick reaches the other players through CRD-6, so for the player the warning was
  false (e.g. most Trostani, Voz dos Selesnya printings).
- Guard: `src/decks/CardPrintingsModal.test.tsx`

### CRD-8 · A card's art changed during a match can go back to its default
After a player changes a card's art during a match (CRD-10), the same art menu offers "Restore
default art" for that card. For another player's card it drops the viewer's change, so the card
shows the art its owner chose again (or what it showed before the change, when the owner chose
none). For the player's own card it shows the deck's art again (the art pick from the deck builder,
else the deck's own printing), and the other players see that again too: the change is taken back
with a `[name, cardId]` entry. The option only shows when that card's art was changed. Like picking a
printing, it closes the menu and leaves the inspector open on the card, now with that art.

- Why: once a player changed a card's art during a match there was no way back to the default
  short of finding that printing again by hand.
- Guard: `src/cards/sharedArt.test.ts` · `src/board/CardInspector.artRestore.test.tsx` · `e2e/card-inspector.spec.ts`

### CRD-9 · The art menu over the card inspector only closes itself
A click inside the art menu opened from the card inspector (a printing, "Upload image", "Restore
default art") never closes the inspector. Picking closes only the menu; a click outside the menu
closes only the menu; Esc closes the menu first and then the inspector; a click on the inspector's
own backdrop closes the inspector.

- Why: the menu is drawn inside the inspector's backdrop, so every click in it also closed the
  inspector, and the menu with it. "Upload image" opened the file picker but the menu was gone
  before the chosen file could arrive, so a custom image could not be set during a match.
- Guard: `src/board/CardInspector.artRestore.test.tsx` · `e2e/card-inspector.spec.ts`

### CRD-10 · An art change during a match changes only that card
Picking a printing for a card during a match (in the card inspector's art menu) changes that one
card only: the other cards with the same name, such as the player's other Swamps, keep their art,
and the deck builder's art for the card is not touched. The change stays with the card while it is
the same card in the game (in hand, on the battlefield, in the graveyard, in exile; a spell on the
stack is a new object for the engine and shows the card's usual art there) and lasts for that game,
also across a page reload in the same tab. On the player's own card the other players see the
change too, on that card only, once it is in a public zone; it goes out as a `[name, setCode,
cardNumber, cardId]` entry, which earlier versions skip (they keep showing the player's art for the
card). On another player's card it shows on this screen only. Picking the art the card shows anyway
leaves no change. Changing the art from an ability changes the card the ability comes from.

A token works the same way: the art menu lists Scryfall's token printings, found by the token's
name without the " Token" the game adds ("Zombie Token" is looked up as "Zombie"), and the pick
changes that one token, on every screen for the player's own token. "Restore default art" brings
back the game's usual image for it. An art picked for a name (in the deck builder, or shared by its
owner) never goes on a token. When Scryfall has no printing for a name, the menu says there are no
cards to show instead of an error.

- Why: the change was kept by card name, so changing one Swamp changed every Swamp of that player,
  on every screen for the player's own cards, and it also replaced the deck builder's art for the
  card. For a token, the menu searched Scryfall for "Zombie Token" among normal cards, where no
  token is listed, so Scryfall answered 404 and the menu only showed an error; and a picked
  printing would not have shown on a token anyway.
- Guard: `src/cards/sharedArt.test.ts` · `src/board/CardInspector.artRestore.test.tsx` · `src/decks/CardPrintingsModal.test.tsx` · `e2e/card-inspector.spec.ts`
