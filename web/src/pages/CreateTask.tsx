import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { supabase } from '../lib/supabase'
import type { TrustedContact, UserProfile } from '../types'

const PLACE_TYPES = [
  { id: 'supermarket', label: 'Supermarket' },
  { id: 'grocery',     label: 'Grocery store' },
  { id: 'pharmacy',    label: 'Pharmacy' },
  { id: 'mall',        label: 'Mall / shopping center' },
  { id: 'bakery',      label: 'Bakery' },
  { id: 'school',      label: 'School / kindergarten' },
  { id: 'hospital',    label: 'Hospital / clinic' },
  { id: 'bank',        label: 'Bank / ATM' },
  { id: 'post',        label: 'Post office / delivery' },
  { id: 'gas_station', label: 'Gas station' },
  { id: 'office',      label: 'Office / workplace' },
  { id: 'home',        label: 'Home' },
  { id: 'hardware',    label: 'Hardware / electronics' },
  { id: 'restaurant',  label: 'Restaurant / cafe' },
]

export default function CreateTask() {
  const navigate = useNavigate()
  const [uid, setUid] = useState('')
  const [contacts, setContacts] = useState<Array<TrustedContact & { profile?: UserProfile }>>([])
  const [form, setForm] = useState({
    title: '',
    description: '',
    taskType: 'SIMPLE',
    shoppingItems: '',
    assigneeId: 'self',
    placeMode: 'EXACT',
    placeName: '',
    placeTypeId: '',
    placeTypeName: '',
    priority: 'NORMAL',
    dueDate: '',
    dueTime: '',
  })
  const [saving, setSaving] = useState(false)

  useEffect(() => {
    supabase.auth.getUser().then(({ data }) => {
      if (!data.user) return
      setUid(data.user.id)
      setForm(f => ({ ...f, assigneeId: data.user!.id }))
      loadContacts(data.user.id)
    })
  }, [])

  async function loadContacts(userId: string) {
    const { data } = await supabase
      .from('contact_invites')
      .select('*')
      .or(`from_user_id.eq.${userId},to_user_id.eq.${userId}`)
      .eq('status', 'ACCEPTED')
    if (!data) return
    const contacts = data as TrustedContact[]
    const otherIds = contacts.map(c =>
      c.from_user_id === userId ? c.to_user_id : c.from_user_id
    )
    if (otherIds.length === 0) return
    const { data: profiles } = await supabase.from('users').select('*').in('id', otherIds)
    const profileMap: Record<string, UserProfile> = {}
    profiles?.forEach((p: UserProfile) => { profileMap[p.id] = p })
    setContacts(contacts.map(c => ({
      ...c,
      profile: profileMap[c.from_user_id === userId ? c.to_user_id : c.from_user_id]
    })))
  }

  const set = (k: string, v: string) => setForm(f => ({ ...f, [k]: v }))

  async function handleSave() {
    if (!form.title.trim()) return
    if (form.placeMode === 'EXACT' && !form.placeName.trim()) return
    if (form.placeMode === 'TYPE' && !form.placeTypeId) return
    if (form.taskType === 'SHOPPING_LIST' && !form.shoppingItems.trim()) return

    setSaving(true)
    const id = crypto.randomUUID()
    const now = Date.now()
    const assigneeId = form.assigneeId === 'self' ? uid : form.assigneeId
    const isSelf = assigneeId === uid

    const taskData = {
      id,
      title: form.title.trim(),
      description: form.description.trim() || null,
      created_by_user_id: uid,
      assigned_to_user_id: assigneeId,
      place_name: form.placeMode === 'TYPE' ? form.placeTypeName : form.placeName,
      address: null,
      latitude: 0,
      longitude: 0,
      radius_meters: 300,
      status: isSelf ? 'ACTIVE' : 'PENDING_ACCEPTANCE',
      priority: form.priority,
      place_mode: form.placeMode,
      place_type_id: form.placeMode === 'TYPE' ? form.placeTypeId : null,
      place_type_name: form.placeMode === 'TYPE' ? form.placeTypeName : null,
      task_type: form.taskType,
      arrival_share_allowed: false,
      active_from_date: form.dueDate || null,
      active_start_time: form.dueTime || null,
      created_at: now,
      updated_at: now,
    }

    const { error } = await supabase.from('tasks').insert(taskData)
    if (error) { alert('Save failed: ' + error.message); setSaving(false); return }

    // Save shopping list items
    if (form.taskType === 'SHOPPING_LIST') {
      const lines = form.shoppingItems.split('\n').filter(l => l.trim())
      const items = lines.map((line, i) => ({
        id: crypto.randomUUID(),
        task_id: id,
        text: line.trim(),
        normalized_text: line.trim().toLowerCase().replace(/[^\w\s]/g, '').replace(/\s+/g, ' ').trim(),
        order_index: i,
        checked: false,
        created_at: now,
        updated_at: now,
      }))
      await supabase.from('shopping_list_items').insert(items)
    }

    navigate('/tasks')
  }

  const isValid = form.title.trim() &&
    (form.placeMode === 'EXACT' ? !!form.placeName.trim() : !!form.placeTypeId) &&
    (form.taskType === 'SIMPLE' || !!form.shoppingItems.trim())

  return (
    <div className="space-y-5 max-w-xl">
      <div className="flex items-center gap-3">
        <button onClick={() => navigate(-1)} className="text-gray-500 hover:text-gray-700">←</button>
        <h1 className="text-xl font-bold text-gray-900">New Task</h1>
      </div>

      <Field label="Task title *">
        <input className="input" value={form.title} onChange={e => set('title', e.target.value)}
          placeholder="e.g. Buy medicine" />
      </Field>

      <Field label="Task type">
        <div className="flex gap-2">
          {(['SIMPLE', 'SHOPPING_LIST'] as const).map(t => (
            <button key={t} onClick={() => set('taskType', t)}
              className={`px-4 py-2 rounded-lg text-sm font-medium border transition ${
                form.taskType === t ? 'bg-purple-600 text-white border-purple-600' : 'bg-white border-gray-300 text-gray-700'
              }`}
            >{t === 'SIMPLE' ? 'Simple' : 'Shopping List'}</button>
          ))}
        </div>
      </Field>

      {form.taskType === 'SHOPPING_LIST' ? (
        <Field label="Shopping items *">
          <textarea className="input min-h-[120px]" value={form.shoppingItems}
            onChange={e => set('shoppingItems', e.target.value)}
            placeholder={'milk\nbread\neggs\nwater'} />
          <p className="text-xs text-gray-400 mt-1">One item per line</p>
        </Field>
      ) : (
        <Field label="Description (optional)">
          <textarea className="input min-h-[80px]" value={form.description}
            onChange={e => set('description', e.target.value)}
            placeholder="Additional notes…" />
        </Field>
      )}

      <Field label="Priority">
        <div className="flex gap-2">
          {(['HIGH', 'NORMAL', 'LOW'] as const).map(p => (
            <button key={p} onClick={() => set('priority', p)}
              className={`px-3 py-1.5 rounded-lg text-sm font-medium border transition ${
                form.priority === p ? 'bg-purple-600 text-white border-purple-600' : 'bg-white border-gray-300 text-gray-700'
              }`}
            >{p}</button>
          ))}
        </div>
      </Field>

      {contacts.length > 0 && (
        <Field label="Assign to">
          <select className="input" value={form.assigneeId} onChange={e => set('assigneeId', e.target.value)}>
            <option value={uid}>Me</option>
            {contacts.map(c => {
              const otherId = c.from_user_id === uid ? c.to_user_id : c.from_user_id
              return (
                <option key={otherId} value={otherId}>
                  {c.profile?.display_name || c.profile?.email || otherId}
                </option>
              )
            })}
          </select>
        </Field>
      )}

      <Field label="Place *">
        <div className="flex gap-2 mb-2">
          {(['EXACT', 'TYPE'] as const).map(m => (
            <button key={m} onClick={() => set('placeMode', m)}
              className={`px-3 py-1.5 rounded-lg text-sm font-medium border transition ${
                form.placeMode === m ? 'bg-purple-600 text-white border-purple-600' : 'bg-white border-gray-300 text-gray-700'
              }`}
            >{m === 'EXACT' ? 'Exact place' : 'Place type'}</button>
          ))}
        </div>
        {form.placeMode === 'EXACT' ? (
          <input className="input" value={form.placeName} onChange={e => set('placeName', e.target.value)}
            placeholder="Place name (e.g. Yerevan City Komitas)" />
        ) : (
          <select className="input" value={form.placeTypeId}
            onChange={e => {
              const sel = PLACE_TYPES.find(p => p.id === e.target.value)
              set('placeTypeId', e.target.value)
              set('placeTypeName', sel?.label ?? '')
            }}>
            <option value="">Select place type…</option>
            {PLACE_TYPES.map(pt => (
              <option key={pt.id} value={pt.id}>{pt.label}</option>
            ))}
          </select>
        )}
        {form.placeMode === 'TYPE' && (
          <p className="text-xs text-gray-400 mt-1">Reminder activates near any matching place (Android app required)</p>
        )}
      </Field>

      <Field label="Due date (optional)">
        <div className="flex gap-2">
          <input type="date" className="input flex-1" value={form.dueDate} onChange={e => set('dueDate', e.target.value)} />
          <input type="time" className="input w-32" value={form.dueTime} onChange={e => set('dueTime', e.target.value)} />
        </div>
      </Field>

      <div className="flex gap-3 pt-2">
        <button onClick={handleSave} disabled={!isValid || saving}
          className="btn-primary flex-1 disabled:opacity-50">
          {saving ? 'Saving…' : 'Create task'}
        </button>
        <button onClick={() => navigate(-1)} className="btn-outline flex-1">Cancel</button>
      </div>

      <p className="text-xs text-gray-400">
        <strong>Keyboard shortcut:</strong> Press Ctrl+Enter / ⌘+Enter to save.
      </p>
    </div>
  )
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="space-y-1">
      <label className="text-sm font-medium text-gray-700">{label}</label>
      {children}
    </div>
  )
}
