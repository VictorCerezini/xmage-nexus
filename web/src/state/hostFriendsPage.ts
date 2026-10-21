import { useSyncExternalStore } from 'react'

/**
 * The host's side of `friendsJoin`: the desktop app starts a friends proxy whenever its server
 * takes players from other machines, and friends join by opening that proxy's page in their own
 * browser. The host has to be told that address to pass it on, so their app asks the proxy, which
 * only answers this machine's own app with the addresses friends can reach (`Main.serveFriends`).
 */
export interface FriendsAddress {
  /** The network it is on, as Windows names it ("Famatech Radmin VPN Ethernet Adapter"). */
  name: string
  address: string
}

export interface HostFriendsPage {
  httpPort: number
  addresses: FriendsAddress[]
}

/** The friends proxy's http port is the launcher's FRIENDS_HTTP_PORT. */
export const HOST_FRIENDS_INFO_URL = 'http://127.0.0.1:8778/friends.json'

/** Asked again this often: the proxy starts with the server, a VPN may connect later. */
const REFRESH_MS = 30_000

const CHOSEN_KEY = 'friends_link_address'

/** The networks friends usually share with the host come first. */
const VPN_NAME = /radmin|hamachi|zerotier|tailscale|wireguard|vpn/i

const IPV4 = /^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/

/** The desktop app's page: http://tauri.localhost on Windows, tauri://localhost elsewhere. */
export function isDesktopApp(loc: Pick<Location, 'protocol' | 'hostname'>): boolean {
  return loc.protocol === 'tauri:' || loc.hostname.toLowerCase() === 'tauri.localhost'
}

/** The proxy only lets friends into this machine's server, so its link only invites to that one. */
export function onThisMachinesServer(serverHost: string | undefined): boolean {
  const host = (serverHost ?? '').toLowerCase()
  return host === 'localhost' || host === '127.0.0.1' || host === '::1' || host === '[::1]'
}

function isIpv4(address: string): boolean {
  const parts = IPV4.exec(address)
  return parts !== null && parts.slice(1).every((part) => Number(part) <= 255)
}

export function parseHostFriendsPage(body: unknown): HostFriendsPage | null {
  if (!body || typeof body !== 'object') return null
  const { httpPort, addresses } = body as { httpPort?: unknown; addresses?: unknown }
  const port = Number(httpPort)
  if (!Number.isInteger(port) || port <= 0 || port > 65535 || !Array.isArray(addresses)) return null
  const found: FriendsAddress[] = []
  for (const entry of addresses) {
    const { name, address } = (entry ?? {}) as { name?: unknown; address?: unknown }
    // only what the proxy sends, a plain IPv4 address: it goes into a link friends open
    if (typeof address !== 'string' || !isIpv4(address)) continue
    if (found.some((known) => known.address === address)) continue
    found.push({ name: typeof name === 'string' && name.trim() ? name.trim() : address, address })
  }
  if (found.length === 0) return null
  const vpnFirst = [...found.filter((a) => VPN_NAME.test(a.name)), ...found.filter((a) => !VPN_NAME.test(a.name))]
  return { httpPort: port, addresses: vpnFirst }
}

export async function loadHostFriendsPage(
  loc: Pick<Location, 'protocol' | 'hostname'> = window.location,
  fetchImpl: typeof fetch = fetch,
): Promise<HostFriendsPage | null> {
  if (!isDesktopApp(loc)) return null
  try {
    const res = await fetchImpl(HOST_FRIENDS_INFO_URL, { cache: 'no-store' })
    if (!res.ok) return null
    return parseHostFriendsPage(await res.json())
  } catch {
    // no friends proxy: the server only takes players from this machine
    return null
  }
}

/** The page a friend opens; an invite adds its `#join=` to it. */
export function friendsPageUrl(page: HostFriendsPage, address: string): string {
  return `http://${address}:${page.httpPort}/`
}

// ── the host app's copy, kept fresh while something shows it ──

let current: HostFriendsPage | null = null
let chosen: string | null = readChosen()
let snapshot: HostFriendsLink | null = null
let timer: ReturnType<typeof setInterval> | null = null
const listeners = new Set<() => void>()

export interface HostFriendsLink {
  page: HostFriendsPage
  /** The address the host picked, or the first one. */
  address: string
  url: string
}

function readChosen(): string | null {
  try {
    return localStorage.getItem(CHOSEN_KEY)
  } catch {
    return null
  }
}

function rebuild() {
  if (!current) {
    snapshot = null
  } else {
    const address = current.addresses.some((a) => a.address === chosen) ? chosen! : current.addresses[0].address
    snapshot = { page: current, address, url: friendsPageUrl(current, address) }
  }
  listeners.forEach((fn) => fn())
}

export function setHostFriendsPage(page: HostFriendsPage | null): void {
  current = page
  rebuild()
}

export function chooseFriendsAddress(address: string): void {
  chosen = address
  try {
    localStorage.setItem(CHOSEN_KEY, address)
  } catch {
    // only remembered for this run
  }
  rebuild()
}

function sameAs(a: HostFriendsPage | null, b: HostFriendsPage | null): boolean {
  return JSON.stringify(a) === JSON.stringify(b)
}

async function refresh() {
  const page = await loadHostFriendsPage()
  if (!sameAs(page, current)) setHostFriendsPage(page)
}

function subscribe(cb: () => void) {
  listeners.add(cb)
  if (!timer && isDesktopApp(window.location)) {
    void refresh()
    timer = setInterval(() => void refresh(), REFRESH_MS)
  }
  return () => {
    listeners.delete(cb)
    if (listeners.size === 0 && timer) {
      clearInterval(timer)
      timer = null
    }
  }
}

export function hostFriendsLink(): HostFriendsLink | null {
  return snapshot
}

/** The link friends join from, or null when there is none to give them. */
export function useHostFriendsLink(): HostFriendsLink | null {
  return useSyncExternalStore(subscribe, hostFriendsLink, hostFriendsLink)
}
