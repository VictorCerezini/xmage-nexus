import { cleanup, fireEvent, render, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import InviteLinkButton from './InviteLinkButton'
import { setLanguage } from '../i18n'
import { setState } from '../state/store'
import { setHostFriendsPage } from '../state/hostFriendsPage'

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('InviteLinkButton', () => {
  it('no renderiza nada sin mesa', () => {
    const { container } = render(<InviteLinkButton tableId={undefined} />)
    expect(container.innerHTML).toBe('')
  })

  it('renderiza botones de invitar a jugar y a espectar', () => {
    const { getByTestId } = render(<InviteLinkButton tableId="table-1" />)
    expect(getByTestId('invite-copy-join')).not.toBeNull()
    expect(getByTestId('invite-copy-watch')).not.toBeNull()
  })

  it('muestra error honesto si fallan ambos intentos de copia (AUDIT)', async () => {
    setLanguage('es')
    vi.stubGlobal('navigator', { clipboard: { writeText: () => Promise.reject(new Error('denied')) } })
    const execSpy = vi.fn(() => false)
    Object.defineProperty(document, 'execCommand', { value: execSpy, configurable: true, writable: true })
    const { getByTestId } = render(<InviteLinkButton tableId="table-1" />)
    fireEvent.click(getByTestId('invite-copy-join'))
    await waitFor(() => {
      expect(getByTestId('invite-copy-join').textContent).toContain('No se pudo copiar')
    })
    expect(execSpy).toHaveBeenCalledWith('copy')
  })

  it('muestra copiado solo cuando la copia tiene éxito (AUDIT)', async () => {
    setLanguage('es')
    vi.stubGlobal('navigator', { clipboard: { writeText: () => Promise.resolve() } })
    const { getByTestId } = render(<InviteLinkButton tableId="table-1" />)
    fireEvent.click(getByTestId('invite-copy-watch'))
    await waitFor(() => {
      expect(getByTestId('invite-copy-watch').textContent).toContain('¡Enlace copiado!')
    })
  })

  it('invita a la página de amigos del anfitrión, que ya es este servidor', async () => {
    setHostFriendsPage({ httpPort: 8778, addresses: [{ name: 'Radmin VPN', address: '26.10.20.30' }] })
    setState({ conn: { serverHost: 'localhost', port: 17171 } } as never)
    const writeText = vi.fn(() => Promise.resolve())
    vi.stubGlobal('navigator', { clipboard: { writeText } })
    try {
      const { getByTestId } = render(<InviteLinkButton tableId="table-1" />)
      fireEvent.click(getByTestId('invite-copy-join'))
      await waitFor(() => expect(writeText).toHaveBeenCalledWith('http://26.10.20.30:8778/#join=table-1'))
    } finally {
      setHostFriendsPage(null)
      setState({ conn: null } as never)
    }
  })
})
