/**
 * A friend joins by opening, in their own browser, the page the host's friends proxy serves
 * (http://<host address>:8778). That page cannot know the proxy's WebSocket port, it was opened on
 * the http one, so it asks the proxy once before the app renders. The answer turns every login of
 * this page into "join that host": the proxy at the address the page came from, which logs in to
 * the host's own server whatever server the page names.
 */
export interface FriendsJoin {
  wsHost: string
  wsPort: number
}

/** Where the friends proxy answers (`Main.FRIENDS_PATH` in Mage.Proxy). */
export const FRIENDS_PATH = '/friends.json'

/** The server port a friend's login names; only shown, the friends proxy picks the real one. */
const FRIENDS_SERVER_PORT = 17171

let current: FriendsJoin | null = null

export function friendsJoin(): FriendsJoin | null {
  return current
}

export function setFriendsJoin(join: FriendsJoin | null): void {
  current = join
}

/**
 * Only plain http on an address that is not this machine's: the desktop app (tauri.localhost),
 * the dev server on localhost and the hosted https builds never ask.
 */
export function mightBeFriendsPage(loc: Pick<Location, 'protocol' | 'hostname'>): boolean {
  if (loc.protocol !== 'http:') return false
  const host = loc.hostname.toLowerCase()
  return !(host === 'localhost' || host === '127.0.0.1' || host === '[::1]' || host === '::1' || host.endsWith('.localhost'))
}

export async function loadFriendsJoin(
  loc: Pick<Location, 'protocol' | 'hostname'> = window.location,
  fetchImpl: typeof fetch = fetch,
): Promise<FriendsJoin | null> {
  if (!mightBeFriendsPage(loc)) return null
  try {
    const res = await fetchImpl(FRIENDS_PATH, { cache: 'no-store' })
    if (!res.ok) return null
    const body = (await res.json()) as { wsPort?: unknown }
    const port = Number(body?.wsPort)
    if (!Number.isInteger(port) || port <= 0 || port > 65535) return null
    current = { wsHost: loc.hostname, wsPort: port }
    return current
  } catch {
    // any other page answers with its index.html, or not at all: not a friends page
    return null
  }
}

/** The connection fields a friend's login uses. */
export function friendsConnection(join: FriendsJoin) {
  return { wsHost: join.wsHost, proxyPort: join.wsPort, serverHost: join.wsHost, port: FRIENDS_SERVER_PORT }
}
