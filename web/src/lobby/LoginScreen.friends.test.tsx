import { afterEach, describe, expect, it } from 'vitest'
import { cleanup, render, screen } from '@testing-library/react'
import LoginScreen from './LoginScreen'
import { setFriendsJoin } from '../state/friendsJoin'

afterEach(() => {
  cleanup()
  setFriendsJoin(null)
})

describe("LoginScreen on a friend's page", () => {
  it('joins the host instead of offering servers', () => {
    setFriendsJoin({ wsHost: '26.10.20.30', wsPort: 8777 })
    const { container } = render(<LoginScreen />)
    expect(screen.getByTestId('login-friends-banner').textContent).toContain('26.10.20.30')
    expect(container.querySelector('.login-presets-container')).toBeNull()
    expect(container.querySelector('.login-network-box')).toBeNull()
  })

  it('keeps the server picker everywhere else', () => {
    const { container } = render(<LoginScreen />)
    expect(screen.queryByTestId('login-friends-banner')).toBeNull()
    expect(container.querySelector('.login-presets-container')).not.toBeNull()
    expect(container.querySelector('.login-network-box')).not.toBeNull()
  })
})
