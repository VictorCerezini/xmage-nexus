import { afterEach, describe, expect, it } from 'vitest'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import DeckServerIssues from './DeckServerIssues'
import type { ServerIssueItem } from './useDeckValidation'

describe('DeckServerIssues', () => {
  afterEach(() => {
    cleanup()
  })

  const unimplemented: ServerIssueItem = {
    kind: 'missing',
    name: 'Nope',
    set: 'ZZZ',
    num: '1',
    amount: 1,
    message: 'No implementada en el servidor',
  }
  const loadsAnother: ServerIssueItem = {
    kind: 'mismatch',
    name: 'Banisher Priest',
    set: 'C20',
    num: '77',
    amount: 2,
    message: 'El servidor cargará "Fiend Hunter"',
  }

  it('DCK-17: lists the cards the server cannot play, with no repair or auto-resolve button', () => {
    render(<DeckServerIssues issues={[unimplemented, loadsAnother]} />)
    expect(screen.getByText(/Nope/)).toBeDefined()
    expect(screen.getByText(/Banisher Priest/)).toBeDefined()
    expect(screen.queryByTestId('builder-issue-auto-resolve')).toBeNull()
    expect(screen.queryByTestId('builder-issue-repair')).toBeNull()
  })

  it('a "loads as another card" warning can be accepted and hidden', () => {
    render(<DeckServerIssues issues={[unimplemented, loadsAnother]} />)
    fireEvent.click(screen.getByTestId('builder-issue-accept'))
    expect(screen.queryByText(/Banisher Priest/)).toBeNull()
    expect(screen.getByText(/Nope/)).toBeDefined()
  })

  it('shows nothing when there is nothing the player has to deal with', () => {
    render(<DeckServerIssues issues={[]} />)
    expect(screen.queryByTestId('builder-server-issues')).toBeNull()
  })
})
