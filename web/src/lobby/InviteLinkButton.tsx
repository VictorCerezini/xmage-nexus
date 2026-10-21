import { useState } from 'react'
import { useStore } from '../state/store'
import { isDesktopApp, onThisMachinesServer, useHostFriendsLink } from '../state/hostFriendsPage'
import { useTranslation } from '../i18n'
import Button from '../ui/Button'
import { copyText } from '../utils/clipboard'
import { buildDeepLink } from './deepLink'

/** Botones "copiar enlace de invitación" (join + watch) para la sala de espera. */
export default function InviteLinkButton({ tableId }: { tableId: string | undefined }) {
  const { t } = useTranslation()
  const conn = useStore((s) => s.conn)
  const friends = useHostFriendsLink()
  const [copied, setCopied] = useState<'join' | 'watch' | null>(null)
  const [copyFailed, setCopyFailed] = useState<'join' | 'watch' | null>(null)
  if (!tableId) return null
  // the desktop app's own address (tauri.localhost) opens nothing on a friend's machine: the
  // friends page is the only link it can give, and that page already is this server's
  const viaFriends = friends && onThisMachinesServer(conn?.serverHost) ? friends : null
  if (!viaFriends && isDesktopApp(window.location)) return null

  const copy = async (kind: 'join' | 'watch') => {
    const url = viaFriends
      ? `${viaFriends.url}${buildDeepLink({ kind, tableId })}`
      : `${window.location.origin}${window.location.pathname}${buildDeepLink({
          kind,
          tableId,
          serverHost: conn?.serverHost,
          serverPort: conn?.port,
        })}`
    const ok = await copyText(url)
    if (ok) {
      setCopyFailed((cur) => (cur === kind ? null : cur))
      setCopied(kind)
    } else {
      setCopied((cur) => (cur === kind ? null : cur))
      setCopyFailed(kind)
    }
    window.setTimeout(() => {
      setCopied((cur) => (cur === kind ? null : cur))
      setCopyFailed((cur) => (cur === kind ? null : cur))
    }, 2500)
  }

  const render = (kind: 'join' | 'watch') => (
    <Button
      key={kind}
      size="sm"
      icon={copied === kind ? 'check' : 'copy'}
      className="tag-invite"
      data-testid={`invite-copy-${kind}`}
      title={t('lobby', kind === 'join' ? 'invite_copy_join' : 'invite_copy_watch')}
      onClick={() => void copy(kind)}
    >
      {copied === kind
        ? t('lobby', 'invite_copied')
        : copyFailed === kind
          ? t('lobby', 'invite_copy_failed')
          : t('lobby', kind === 'join' ? 'invite_copy_join' : 'invite_copy_watch')}
    </Button>
  )

  return (
    <>
      {render('join')}
      {render('watch')}
    </>
  )
}
