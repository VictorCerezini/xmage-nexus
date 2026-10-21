import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  HOST_FRIENDS_INFO_URL,
  chooseFriendsAddress,
  friendsPageUrl,
  hostFriendsLink,
  isDesktopApp,
  loadHostFriendsPage,
  onThisMachinesServer,
  parseHostFriendsPage,
  setHostFriendsPage,
} from './hostFriendsPage'

const DESKTOP = { protocol: 'http:', hostname: 'tauri.localhost' }

afterEach(() => {
  setHostFriendsPage(null)
})

describe('hostFriendsPage', () => {
  it('only the desktop app asks, on Windows and elsewhere', () => {
    expect(isDesktopApp(DESKTOP)).toBe(true)
    expect(isDesktopApp({ protocol: 'tauri:', hostname: 'localhost' })).toBe(true)
    expect(isDesktopApp({ protocol: 'http:', hostname: 'localhost' })).toBe(false)
    expect(isDesktopApp({ protocol: 'http:', hostname: '26.10.20.30' })).toBe(false)
    expect(isDesktopApp({ protocol: 'https:', hostname: 'web.xmage-nexus.com' })).toBe(false)
  })

  it('only invites to the server on this machine, the one the friends proxy lets friends into', () => {
    expect(onThisMachinesServer('localhost')).toBe(true)
    expect(onThisMachinesServer('127.0.0.1')).toBe(true)
    expect(onThisMachinesServer('beta.xmage.today')).toBe(false)
    expect(onThisMachinesServer(undefined)).toBe(false)
  })

  it('puts the VPN friends usually share first', () => {
    const page = parseHostFriendsPage({
      wsPort: 8777,
      httpPort: 8778,
      addresses: [
        { name: 'Realtek PCIe 2.5GbE Family Controller', address: '192.168.0.12' },
        { name: 'Famatech Radmin VPN Ethernet Adapter', address: '26.10.20.30' },
      ],
    })
    expect(page).toEqual({
      httpPort: 8778,
      addresses: [
        { name: 'Famatech Radmin VPN Ethernet Adapter', address: '26.10.20.30' },
        { name: 'Realtek PCIe 2.5GbE Family Controller', address: '192.168.0.12' },
      ],
    })
    expect(friendsPageUrl(page!, '26.10.20.30')).toBe('http://26.10.20.30:8778/')
  })

  it('only keeps plain IPv4 addresses, the link friends open is built from them', () => {
    const page = parseHostFriendsPage({
      httpPort: 8778,
      addresses: [
        { name: 'a', address: 'evil.example/x' },
        { name: 'b', address: '1.2.3.4@evil.example' },
        { name: 'c', address: '300.1.1.1' },
        { name: 'd', address: 26 },
        { address: '26.10.20.30' },
        { name: 'again', address: '26.10.20.30' },
      ],
    })
    expect(page?.addresses).toEqual([{ name: '26.10.20.30', address: '26.10.20.30' }])
    expect(parseHostFriendsPage({ httpPort: 8778, addresses: [] })).toBeNull()
    expect(parseHostFriendsPage({ httpPort: 0, addresses: [{ name: 'a', address: '26.10.20.30' }] })).toBeNull()
    expect(parseHostFriendsPage({ wsPort: 8777 })).toBeNull()
    expect(parseHostFriendsPage(null)).toBeNull()
  })

  it('asks the friends proxy on this machine, and nothing outside the desktop app', async () => {
    const fetchImpl = vi.fn(async () => new Response(JSON.stringify({ httpPort: 8778, addresses: [{ name: 'a', address: '26.10.20.30' }] })))
    expect(await loadHostFriendsPage({ protocol: 'http:', hostname: 'localhost' }, fetchImpl)).toBeNull()
    expect(fetchImpl).not.toHaveBeenCalled()
    expect((await loadHostFriendsPage(DESKTOP, fetchImpl))?.addresses[0].address).toBe('26.10.20.30')
    expect(fetchImpl).toHaveBeenCalledWith(HOST_FRIENDS_INFO_URL, { cache: 'no-store' })
  })

  it('has no link when the proxy is not running or answers as friends get it', async () => {
    const refused = vi.fn(async () => {
      throw new TypeError('Failed to fetch')
    })
    expect(await loadHostFriendsPage(DESKTOP, refused)).toBeNull()
    const asAFriend = vi.fn(async () => new Response(JSON.stringify({ wsPort: 8777 })))
    expect(await loadHostFriendsPage(DESKTOP, asAFriend)).toBeNull()
    const failing = vi.fn(async () => new Response('', { status: 500 }))
    expect(await loadHostFriendsPage(DESKTOP, failing)).toBeNull()
  })

  it('keeps the network the host picked while it is still there', () => {
    const page = parseHostFriendsPage({
      httpPort: 8778,
      addresses: [
        { name: 'Radmin VPN', address: '26.10.20.30' },
        { name: 'Ethernet', address: '192.168.0.12' },
      ],
    })!
    setHostFriendsPage(page)
    chooseFriendsAddress('192.168.0.12')
    expect(hostFriendsLink()?.url).toBe('http://192.168.0.12:8778/')
    setHostFriendsPage({ ...page, addresses: [page.addresses[0]] })
    expect(hostFriendsLink()?.url).toBe('http://26.10.20.30:8778/')
    setHostFriendsPage(null)
    expect(hostFriendsLink()).toBeNull()
  })
})
