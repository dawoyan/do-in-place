import { useEffect, useState } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import type { Session } from '@supabase/supabase-js'
import { supabase, WEB_APP_URL } from '../lib/supabase'
import type { ConnectionInvite, UserProfile } from '../types'

interface Props { session: Session | null }

export default function InviteLanding({ session }: Props) {
  const { code } = useParams<{ code: string }>()
  const navigate = useNavigate()

  const [invite, setInvite] = useState<ConnectionInvite | null>(null)
  const [inviter, setInviter] = useState<UserProfile | null>(null)
  const [state, setState] = useState<'loading' | 'error' | 'ready' | 'self' | 'sent' | 'already'>('loading')
  const [errorMsg, setErrorMsg] = useState('')

  useEffect(() => {
    if (!code) { setState('error'); setErrorMsg('Invalid link.'); return }
    loadInvite()
  }, [code, session])

  async function loadInvite() {
    setState('loading')
    const { data, error } = await supabase
      .from('connection_invites')
      .select('*')
      .eq('invite_code', code)
      .single()

    if (error || !data) {
      setState('error')
      setErrorMsg('This invitation link is invalid or expired.')
      return
    }

    const inv = data as ConnectionInvite
    const now = Date.now()
    if (inv.status !== 'ACTIVE' || inv.expires_at < now) {
      setState('error')
      setErrorMsg('This invitation link is invalid or expired.')
      return
    }

    // Self-invite check
    if (session && session.user.id === inv.created_by_user_id) {
      setState('self')
      return
    }

    // Already connected?
    if (session) {
      const { data: existing } = await supabase
        .from('contact_invites')
        .select('id')
        .or(`from_user_id.eq.${session.user.id},to_user_id.eq.${session.user.id}`)
        .or(`from_user_id.eq.${inv.created_by_user_id},to_user_id.eq.${inv.created_by_user_id}`)
        .limit(1)
      if (existing && existing.length > 0) {
        setState('already')
        return
      }
    }

    setInvite(inv)

    // Load inviter profile
    const { data: prof } = await supabase
      .from('users')
      .select('*')
      .eq('id', inv.created_by_user_id)
      .single()
    if (prof) setInviter(prof as UserProfile)

    setState('ready')
  }

  async function loginWithGoogle() {
    const redirectTo = `${WEB_APP_URL}/invite/${code}`
    await supabase.auth.signInWithOAuth({ provider: 'google', options: { redirectTo } })
  }

  async function sendRequest() {
    if (!session || !invite) return
    const uid = session.user.id
    const now = Date.now()

    // Create contact invite entry (from inviter to me)
    const { error } = await supabase.from('contact_invites').upsert({
      id: crypto.randomUUID(),
      from_user_id: invite.created_by_user_id,
      to_user_id: uid,
      to_email: session.user.email ?? '',
      status: 'PENDING',
      created_at: now,
      updated_at: now,
    })

    if (error) { alert('Failed to send request: ' + error.message); return }

    // Mark invite as used
    await supabase
      .from('connection_invites')
      .update({ status: 'USED', used_by_user_id: uid, used_at: now })
      .eq('id', invite.id)

    // Upsert user profile
    await supabase.from('users').upsert({
      id: uid,
      email: session.user.email ?? '',
      display_name: session.user.user_metadata?.full_name ?? session.user.email ?? ''
    })

    setState('sent')
  }

  if (state === 'loading') return <Page><div className="text-gray-500">Loading invitation…</div></Page>

  if (state === 'self') return (
    <Page>
      <Alert type="warn" msg="You cannot use your own invitation link." />
      <button onClick={() => navigate('/tasks')} className="btn-primary mt-4">Go to tasks</button>
    </Page>
  )

  if (state === 'already') return (
    <Page>
      <Alert type="info" msg="You are already connected or have a pending request." />
      <button onClick={() => navigate('/connections')} className="btn-primary mt-4">View connections</button>
    </Page>
  )

  if (state === 'error') return (
    <Page>
      <Alert type="error" msg={errorMsg} />
      <button onClick={() => navigate('/tasks')} className="btn-primary mt-4">Go to tasks</button>
    </Page>
  )

  if (state === 'sent') return (
    <Page>
      <div className="text-4xl mb-3">✅</div>
      <h2 className="text-xl font-bold text-gray-900">Request sent!</h2>
      <p className="text-gray-600 mt-2 text-center">
        Waiting for <strong>{inviter?.display_name || 'them'}</strong> to confirm.
        You'll see the connection once they accept.
      </p>
      <button onClick={() => navigate('/connections')} className="btn-primary mt-6">View connections</button>
    </Page>
  )

  // ready state
  return (
    <Page>
      <div className="text-4xl mb-3">📍</div>
      {session ? (
        <>
          <h2 className="text-xl font-bold text-gray-900 text-center">
            <strong>{inviter?.display_name || 'Someone'}</strong> invited you to connect in Do In Place.
          </h2>
          <p className="text-gray-500 text-sm mt-2 text-center">
            Accepting lets them assign you place-based reminders.
          </p>
          <div className="flex gap-3 mt-6 w-full">
            <button onClick={sendRequest} className="btn-primary flex-1">Send connection request</button>
            <button onClick={() => navigate('/tasks')} className="btn-outline flex-1">Cancel</button>
          </div>
        </>
      ) : (
        <>
          <h2 className="text-xl font-bold text-gray-900 text-center">
            You were invited to connect in Do In Place.
          </h2>
          <p className="text-gray-500 text-sm mt-2 text-center">
            Log in with Google to continue.
          </p>
          <button onClick={loginWithGoogle} className="btn-primary mt-6 w-full">Log in with Google</button>
        </>
      )}
    </Page>
  )
}

function Page({ children }: { children: React.ReactNode }) {
  return (
    <div className="min-h-screen bg-gray-50 flex items-center justify-center px-4">
      <div className="bg-white rounded-2xl shadow-md p-8 w-full max-w-sm flex flex-col items-center gap-3">
        {children}
      </div>
    </div>
  )
}

function Alert({ type, msg }: { type: 'error' | 'warn' | 'info'; msg: string }) {
  const colors = {
    error: 'bg-red-50 text-red-700 border-red-200',
    warn:  'bg-amber-50 text-amber-700 border-amber-200',
    info:  'bg-blue-50 text-blue-700 border-blue-200',
  }
  return (
    <div className={`border rounded-lg px-4 py-3 text-sm w-full ${colors[type]}`}>{msg}</div>
  )
}
