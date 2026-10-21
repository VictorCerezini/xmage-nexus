import { useState } from 'react'
import { useStore } from '../state/store'
import { chooseFriendsAddress, onThisMachinesServer, useHostFriendsLink } from '../state/hostFriendsPage'
import { useTranslation } from '../i18n'
import Button from '../ui/Button'
import Icon from '../ui/Icon'
import { copyText } from '../utils/clipboard'
import './FriendsLinkBar.css'

/** The address the host gives friends: the page they open in their browser to join this server. */
export default function FriendsLinkBar() {
  const { t } = useTranslation()
  const conn = useStore((s) => s.conn)
  const link = useHostFriendsLink()
  const [copied, setCopied] = useState<'ok' | 'failed' | null>(null)
  if (!link || !onThisMachinesServer(conn?.serverHost)) return null

  const copy = async () => {
    setCopied((await copyText(link.url)) ? 'ok' : 'failed')
    window.setTimeout(() => setCopied(null), 2500)
  }

  return (
    <div className="friends-link-bar" data-testid="friends-link-bar">
      <Icon name="users" size={15} className="friends-link-icon" />
      <div className="friends-link-text">
        <span className="friends-link-label">{t('lobby', 'friends_link_label')}</span>
        <span className="friends-link-hint">{t('lobby', 'friends_link_hint')}</span>
      </div>
      <code className="friends-link-url" data-testid="friends-link-url">{link.url}</code>
      {link.page.addresses.length > 1 && (
        <select
          className="friends-link-network"
          aria-label={t('lobby', 'friends_link_network')}
          data-testid="friends-link-network"
          value={link.address}
          onChange={(e) => chooseFriendsAddress(e.target.value)}
        >
          {link.page.addresses.map((a) => (
            <option key={a.address} value={a.address}>
              {a.name} ({a.address})
            </option>
          ))}
        </select>
      )}
      <Button size="sm" icon={copied === 'ok' ? 'check' : 'copy'} data-testid="friends-link-copy" onClick={() => void copy()}>
        {copied === 'ok' ? t('lobby', 'invite_copied') : copied === 'failed' ? t('lobby', 'invite_copy_failed') : t('common', 'copy')}
      </Button>
    </div>
  )
}
