import { useEffect, useState } from 'react'
import { supabase, WEB_APP_URL } from '../lib/supabase'
import type { TrustedContact, ConnectionInvite, UserProfile } from '../types'

export default function Connections() {
  const [uid, setUid] = useState('')
  const [connections, setConnections] = useState<Array<TrustedContact & { profile?: UserProfile }>>([])
  const [pendingReceived, setPendingReceived] = useState<Array<TrustedContact & { profile?: UserProfile }>>([])
  const [pendingSent, setPendingSent] = useState<Array<TrustedContact & { profile?: UserProfile }>>([])
  const [loading, setLoading] = useState(true)
  const [inviteLink, setInviteLink] = useState('')
  const [generatingLink, setGeneratingLink] = useState(false)
  const [copied, setCopied] = useState(false)

  useEffect(() => {
    supabase.auth.getUser().then(({ data }) => {
      if (data.user) { setUid(data.user.id); load(data.user.id) }
    })
  }, [])

  async function load(userId: string) {
    setLoading(true)
    const { data } = await supabase
      .from('contact_invites')
      .select('*')
      .or(`from_user_id.eq.${userId},to_user_id.eq.${userId}`)

    if (!data) { setLoading(false); return }

    const allContacts = data as TrustedContact[]
    const allIds = [...new Set(allContacts.flatMap(c => [c.from_user_id, c.to_user_id]))].filter(id => id !== userId)
    let profileMap: Record<string, UserProfile> = {}
    if (allIds.length > 0) {
      const { data: profiles } = await supabase.from('users').select('*').in('id', allIds)
      profiles?.forEach((p: UserProfile) => { profileMap[p.id] = p })
    }

    const withProfile = (c: TrustedContact) => ({
      ...c,
      profile: profileMap[c.from_user_id === userId ? c.to_user_id : c.from_user_id]
    })

    setConnections(allContacts.filter(c => c.status === 'ACCEPTED').map(withProfile))
    setPendingReceived(allContacts.filter(c => c.status === 'PENDING' && c.to_user_id === userId).map(withProfile))
    setPendingSent(allContacts.filter(c => c.status === 'PENDING' && c.from_user_id === userId).map(withProfile))

    // Check for active invite link
    const { data: activeInvite } = await supabase
      .from('connection_invites')
      .select('*')
      .eq('created_by_user_id', userId)
      .eq('status', 'ACTIVE')
      .gt('expires_at', Date.now())
      .order('created_at', { ascending: false })
      .limit(1)
      .single()
    if (activeInvite) {
      setInviteLink(`${WEB_APP_URL}/invite/${(activeInvite as ConnectionInvite).invite_code}`)
    }

    setLoading(false)
  }

  async function generateInviteLink() {
    setGeneratingLink(true)
    const code = crypto.randomUUID()
    const now = Date.now()
    const expiresAt = now + 7 * 24 * 60 * 60 * 1000

    const { error } = await supabase.from('connection_invites').insert({
      id: crypto.randomUUID(),
      created_by_user_id: uid,
      invite_code: code,
      status: 'ACTIVE',
      max_uses: 1,
      use_count: 0,
      expires_at: expiresAt,
      created_at: now,
    })

    if (!error) {
      const link = `${WEB_APP_URL}/invite/${code}`
      setInviteLink(link)
    }
    setGeneratingLink(false)
  }

  async function copyLink() {
    await navigator.clipboard.writeText(inviteLink)
    setCopied(true)
    setTimeout(() => setCopied(false), 2000)
  }

  async function revokeLink() {
    if (!inviteLink) return
    const code = inviteLink.split('/invite/')[1]
    await supabase.from('connection_invites')
      .update({ status: 'REVOKED' })
      .eq('invite_code', code)
      .eq('created_by_user_id', uid)
    setInviteLink('')
  }

  async function acceptRequest(contactId: string) {
    const now = Date.now()
    await supabase.from('contact_invites')
      .update({ status: 'ACCEPTED', updated_at: now }).eq('id', contactId)
    load(uid)
  }

  async function rejectRequest(contactId: string) {
    await supabase.from('contact_invites')
      .update({ status: 'REJECTED', updated_at: Date.now() }).eq('id', contactId)
    load(uid)
  }

  async function removeConnection(contactId: string) {
    if (!confirm('Remove this connection?')) return
    await supabase.from('contact_invites')
      .update({ status: 'REMOVED', updated_at: Date.now() }).eq('id', contactId)
    load(uid)
  }

  if (loading) return <div className="text-gray-400 text-sm">Loading…</div>

  return (
    <div className="space-y-6">
      <h1 className="text-xl font-bold text-gray-900">Connections</h1>

      {/* Invite via link */}
      <section className="bg-white rounded-xl border border-gray-100 shadow-sm p-5 space-y-3">
        <h2 className="font-semibold text-gray-800">Invite someone</h2>
        <p className="text-sm text-gray-500">
          Share a link — when they open it and log in, a connection request is sent to you.
        </p>
        {inviteLink ? (
          <div className="space-y-2">
            <div className="flex items-center gap-2">
              <input readOnly value={inviteLink}
                className="input flex-1 text-sm font-mono truncate bg-gray-50" />
              <button onClick={copyLink} className="btn-primary text-sm whitespace-nowrap">
                {copied ? 'Copied!' : 'Copy'}
              </button>
            </div>
            <p className="text-xs text-gray-400">Expires in 7 days, single-use.</p>
            <button onClick={revokeLink} className="text-xs text-red-500 hover:underline">Revoke link</button>
          </div>
        ) : (
          <button onClick={generateInviteLink} disabled={generatingLink} className="btn-primary">
            {generatingLink ? 'Generating…' : 'Generate invite link'}
          </button>
        )}
      </section>

      {/* Pending received */}
      {pendingReceived.length > 0 && (
        <section className="space-y-2">
          <h2 className="font-semibold text-gray-800">Requests received</h2>
          {pendingReceived.map(c => (
            <div key={c.id} className="bg-white rounded-xl border border-gray-100 shadow-sm p-4 flex items-center justify-between gap-3">
              <div>
                <div className="font-medium text-gray-900">{c.profile?.display_name || c.profile?.email || 'Unknown'}</div>
                <div className="text-xs text-gray-400">{c.profile?.email}</div>
              </div>
              <div className="flex gap-2">
                <button onClick={() => acceptRequest(c.id)} className="btn-primary text-sm">Accept</button>
                <button onClick={() => rejectRequest(c.id)} className="btn-outline text-sm">Reject</button>
              </div>
            </div>
          ))}
        </section>
      )}

      {/* Accepted connections */}
      <section className="space-y-2">
        <h2 className="font-semibold text-gray-800">
          Connected <span className="text-gray-400 font-normal">({connections.length})</span>
        </h2>
        {connections.length === 0 && (
          <div className="text-sm text-gray-400 py-4 text-center">
            No connections yet. Share an invite link to get started.
          </div>
        )}
        {connections.map(c => (
          <div key={c.id} className="bg-white rounded-xl border border-gray-100 shadow-sm p-4 flex items-center justify-between gap-3">
            <div>
              <div className="font-medium text-gray-900">{c.profile?.display_name || c.profile?.email || 'Unknown'}</div>
              <div className="text-xs text-gray-400">{c.profile?.email}</div>
            </div>
            <button onClick={() => removeConnection(c.id)}
              className="text-xs text-red-400 hover:text-red-600 transition">
              Remove
            </button>
          </div>
        ))}
      </section>

      {/* Pending sent */}
      {pendingSent.length > 0 && (
        <section className="space-y-2">
          <h2 className="font-semibold text-gray-800">Requests sent</h2>
          {pendingSent.map(c => (
            <div key={c.id} className="bg-white rounded-xl border border-gray-100 shadow-sm p-4">
              <div className="font-medium text-gray-900">{c.profile?.display_name || c.profile?.email || 'Pending…'}</div>
              <div className="text-xs text-gray-400">Waiting for acceptance</div>
            </div>
          ))}
        </section>
      )}
    </div>
  )
}
