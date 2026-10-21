import { act, cleanup, fireEvent, render, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import FriendsLinkBar from './FriendsLinkBar'
import { setLanguage } from '../i18n'
import { setState } from '../state/store'
import { chooseFriendsAddress, setHostFriendsPage } from '../state/hostFriendsPage'

const PAGE = {
  httpPort: 8778,
  addresses: [
    { name: 'Radmin VPN', address: '26.10.20.30' },
    { name: 'Ethernet', address: '192.168.0.12' },
  ],
}

afterEach(() => {
  cleanup()
  setHostFriendsPage(null)
  setState({ conn: null } as never)
  vi.unstubAllGlobals()
})

describe('FriendsLinkBar', () => {
  it('shows nothing while there is no friends proxy', () => {
    setState({ conn: { serverHost: 'localhost', port: 17171 } } as never)
    const { container } = render(<FriendsLinkBar />)
    expect(container.innerHTML).toBe('')
  })

  it('shows nothing on another server: friends would land on this machine instead', () => {
    setHostFriendsPage(PAGE)
    setState({ conn: { serverHost: 'beta.xmage.today', port: 17171 } } as never)
    const { container } = render(<FriendsLinkBar />)
    expect(container.innerHTML).toBe('')
  })

  it('tells the host the address friends open, on the network they pick', async () => {
    setLanguage('en')
    setHostFriendsPage(PAGE)
    chooseFriendsAddress('26.10.20.30')
    setState({ conn: { serverHost: 'localhost', port: 17171 } } as never)
    const writeText = vi.fn(() => Promise.resolve())
    vi.stubGlobal('navigator', { clipboard: { writeText } })
    const { getByTestId } = render(<FriendsLinkBar />)

    expect(getByTestId('friends-link-bar').textContent).toContain('Friends can join from their browser:')
    expect(getByTestId('friends-link-url').textContent).toBe('http://26.10.20.30:8778/')

    act(() => {
      fireEvent.change(getByTestId('friends-link-network'), { target: { value: '192.168.0.12' } })
    })
    expect(getByTestId('friends-link-url').textContent).toBe('http://192.168.0.12:8778/')

    fireEvent.click(getByTestId('friends-link-copy'))
    await waitFor(() => expect(getByTestId('friends-link-copy').textContent).toContain('Link copied!'))
    expect(writeText).toHaveBeenCalledWith('http://192.168.0.12:8778/')
    chooseFriendsAddress('26.10.20.30')
  })

  it('has no network picker with a single network', () => {
    setHostFriendsPage({ ...PAGE, addresses: [PAGE.addresses[0]] })
    setState({ conn: { serverHost: '127.0.0.1', port: 17171 } } as never)
    const { queryByTestId } = render(<FriendsLinkBar />)
    expect(queryByTestId('friends-link-url')?.textContent).toBe('http://26.10.20.30:8778/')
    expect(queryByTestId('friends-link-network')).toBeNull()
  })
})
