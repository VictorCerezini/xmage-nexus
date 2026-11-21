import { render, screen, fireEvent, waitFor, cleanup } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import LlmUnreadyNote from './LlmUnreadyNote'
import type { LlmUnavailable } from '../../net/types'
import { setLanguage } from '../../i18n'

const PROGRAM = String.raw`C:\Users\player\AppData\Roaming\Claude\claude-code\2.1.330\abc\claude.exe`
const claude: LlmUnavailable = {
  id: 'claude_subscription',
  playerName: 'Claude',
  reason: 'Claude Code on this computer is signed in with an API key, and Claude matches only play on your Claude subscription.',
  signIn: `& '${PROGRAM}' auth login --claudeai`,
  signInShell: 'PowerShell',
  signInProgram: PROGRAM,
}
const openCodeGo: LlmUnavailable = {
  id: 'opencode_go',
  playerName: 'OpenCode Go',
  reason: 'No OpenCode Go key is saved for this app yet.',
  signIn: 'python -m llm.sign_in opencode-go',
  signInShell: 'PowerShell',
}

type Win = { __TAURI__?: { core: { invoke: ReturnType<typeof vi.fn> } } }

function inDesktopApp(invoke = vi.fn().mockResolvedValue(undefined)) {
  ;(window as Win).__TAURI__ = { core: { invoke } }
  return invoke
}

function note(entry: LlmUnavailable, onRefresh = vi.fn()) {
  render(<LlmUnreadyNote id="note" entry={entry} refreshing={false} onRefresh={onRefresh} />)
  return onRefresh
}

beforeEach(() => setLanguage('en'))

afterEach(() => {
  cleanup()
  delete (window as Win).__TAURI__
  setLanguage('es')
})

describe('LlmUnreadyNote', () => {
  it('LBY-33: in the desktop app, Claude has the steps and a Sign in button that opens the sign-in window', async () => {
    const invoke = inDesktopApp()
    note(claude)
    expect(screen.getByTestId('note').textContent).toContain('signed in with an API key')
    const steps = screen.getByTestId('note-steps').textContent ?? ''
    expect(steps).toContain('Press Sign in')
    expect(steps).toContain('your Claude subscription')
    expect(steps).toContain('Refresh models')
    expect(screen.getByText('Or run this in PowerShell:')).toBeTruthy()
    expect(screen.getByTestId('note-command').textContent).toBe(claude.signIn)
    expect(screen.getByTestId('note-copy')).toBeTruthy()

    fireEvent.click(screen.getByTestId('note-sign-in'))
    await waitFor(() => expect(invoke).toHaveBeenCalledWith('open_claude_sign_in', { program: PROGRAM }))
    expect(screen.queryByTestId('note-window-failed')).toBeNull()
  })

  it('LBY-33: a sign-in window that does not open says to run the command instead', async () => {
    inDesktopApp(vi.fn().mockRejectedValue('Claude Code is not at C:\\gone\\claude.exe'))
    note(claude)
    fireEvent.click(screen.getByTestId('note-sign-in'))
    expect((await screen.findByTestId('note-window-failed')).textContent).toContain('Run the command below instead')
    expect(screen.getByTestId('note-command').textContent).toBe(claude.signIn)
  })

  it('LBY-33: in a browser, Claude has the steps to run the command and no Sign in button', () => {
    note(claude)
    expect(screen.queryByTestId('note-sign-in')).toBeNull()
    const steps = screen.getByTestId('note-steps').textContent ?? ''
    expect(steps).toContain('To sign in, run this in PowerShell')
    expect(steps).toContain('your Claude subscription')
    expect(screen.getByTestId('note-command').textContent).toBe(claude.signIn)
  })

  it('LBY-33: a missing key keeps its own steps, with no Sign in button even in the desktop app', () => {
    const invoke = inDesktopApp()
    const onRefresh = note(openCodeGo)
    expect(screen.queryByTestId('note-sign-in')).toBeNull()
    expect(screen.getByTestId('note-steps').textContent).toContain('To add your key, run this in PowerShell')
    fireEvent.click(screen.getByTestId('note-refresh'))
    expect(onRefresh).toHaveBeenCalledOnce()
    expect(invoke).not.toHaveBeenCalled()
  })
})
