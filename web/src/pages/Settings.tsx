import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import type { Session } from '@supabase/supabase-js'
import { supabase } from '../lib/supabase'
import type { UserProfile } from '../types'

interface Props { session: Session | null }

export default function Settings({ session }: Props) {
  const navigate = useNavigate()
  const [profile, setProfile] = useState<UserProfile | null>(null)
  const [displayName, setDisplayName] = useState('')
  const [saving, setSaving] = useState(false)
  const [saved, setSaved] = useState(false)

  useEffect(() => {
    if (!session) return
    supabase.from('users').select('*').eq('id', session.user.id).single()
      .then(({ data }) => {
        if (data) {
          setProfile(data as UserProfile)
          setDisplayName((data as UserProfile).display_name || '')
        }
      })
  }, [session])

  async function saveDisplayName() {
    if (!session) return
    setSaving(true)
    const { error } = await supabase.from('users').upsert({
      id: session.user.id,
      email: session.user.email ?? '',
      display_name: displayName.trim(),
    })
    if (!error) { setSaved(true); setTimeout(() => setSaved(false), 2000) }
    setSaving(false)
  }

  async function signOut() {
    await supabase.auth.signOut()
    navigate('/login', { replace: true })
  }

  if (!session) return null

  return (
    <div className="space-y-6 max-w-md">
      <h1 className="text-xl font-bold text-gray-900">Settings</h1>

      <section className="bg-white rounded-xl border border-gray-100 shadow-sm p-5 space-y-4">
        <h2 className="font-semibold text-gray-800">Profile</h2>

        <div className="space-y-1">
          <label className="text-sm font-medium text-gray-700">Email</label>
          <div className="text-sm text-gray-500 bg-gray-50 rounded-lg px-3 py-2">
            {session.user.email}
          </div>
        </div>

        <div className="space-y-1">
          <label className="text-sm font-medium text-gray-700">Display name</label>
          <input
            className="input"
            value={displayName}
            onChange={e => setDisplayName(e.target.value)}
            placeholder="How others will see you"
          />
        </div>

        <button onClick={saveDisplayName} disabled={saving || !displayName.trim()} className="btn-primary">
          {saving ? 'Saving…' : saved ? 'Saved ✓' : 'Save'}
        </button>
      </section>

      <section className="bg-white rounded-xl border border-gray-100 shadow-sm p-5 space-y-3">
        <h2 className="font-semibold text-gray-800">About</h2>
        <div className="text-sm text-gray-500 space-y-1">
          <p>Do In Place — web companion.</p>
          <p>Place-based reminders run in the Android app.</p>
          <p className="text-xs text-gray-400">User ID: {session.user.id}</p>
        </div>
      </section>

      <button onClick={signOut}
        className="w-full px-4 py-2 rounded-lg border border-red-300 text-red-600 text-sm font-medium hover:bg-red-50 transition">
        Sign out
      </button>
    </div>
  )
}
