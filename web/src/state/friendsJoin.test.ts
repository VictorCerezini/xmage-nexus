import { afterEach, describe, expect, it, vi } from 'vitest'
import { FRIENDS_PATH, friendsConnection, friendsJoin, loadFriendsJoin, mightBeFriendsPage, setFriendsJoin } from './friendsJoin'
import { loadConn, saveConn } from './persistence'

const friendPage = { protocol: 'http:', hostname: '26.10.20.30' }

function answer(body: unknown, ok = true) {
  return vi.fn(async () => ({ ok, json: async () => body }) as Response)
}

afterEach(() => {
  setFriendsJoin(null)
  localStorage.clear()
})

describe('friends page', () => {
  it('only asks on plain http from another address', () => {
    expect(mightBeFriendsPage(friendPage)).toBe(true)
    expect(mightBeFriendsPage({ protocol: 'http:', hostname: 'tauri.localhost' })).toBe(false)
    expect(mightBeFriendsPage({ protocol: 'http:', hostname: 'localhost' })).toBe(false)
    expect(mightBeFriendsPage({ protocol: 'http:', hostname: '127.0.0.1' })).toBe(false)
    expect(mightBeFriendsPage({ protocol: 'https:', hostname: 'play.example.com' })).toBe(false)
    expect(mightBeFriendsPage({ protocol: 'tauri:', hostname: 'localhost' })).toBe(false)
  })

  it('connects to the proxy at the address the page came from', async () => {
    const fetchImpl = answer({ wsPort: 8777 })
    await expect(loadFriendsJoin(friendPage, fetchImpl)).resolves.toEqual({ wsHost: '26.10.20.30', wsPort: 8777 })
    expect(fetchImpl).toHaveBeenCalledWith(FRIENDS_PATH, { cache: 'no-store' })
    expect(friendsJoin()).toEqual({ wsHost: '26.10.20.30', wsPort: 8777 })
  })

  it('is not a friends page when the answer is not the proxy one', async () => {
    await expect(loadFriendsJoin(friendPage, answer({}, false))).resolves.toBeNull()
    await expect(loadFriendsJoin(friendPage, answer({ wsPort: 'x' }))).resolves.toBeNull()
    const html = vi.fn(async () => ({ ok: true, json: async () => { throw new SyntaxError('<!doctype') } }) as unknown as Response)
    await expect(loadFriendsJoin(friendPage, html)).resolves.toBeNull()
    const local = answer({ wsPort: 8777 })
    await expect(loadFriendsJoin({ protocol: 'http:', hostname: 'localhost' }, local)).resolves.toBeNull()
    expect(local).not.toHaveBeenCalled()
    expect(friendsJoin()).toBeNull()
  })

  it('a saved connection keeps the player but joins the host', () => {
    saveConn({ wsHost: 'localhost', proxyPort: 8787, serverHost: 'beta.xmage.today', port: 17171, username: 'ann', password: 'pw' })
    setFriendsJoin({ wsHost: '26.10.20.30', wsPort: 8777 })
    // the account password is never stored (LBY-17), on a friend's page too
    expect(loadConn()).toMatchObject({ ...friendsConnection({ wsHost: '26.10.20.30', wsPort: 8777 }), username: 'ann', password: '' })
    setFriendsJoin(null)
    expect(loadConn()?.wsHost).toBe('localhost')
  })
})
