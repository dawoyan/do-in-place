import { useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { supabase } from '../lib/supabase'
import type { Task, ShoppingListItem, UserProfile } from '../types'

const STATUS_LABEL: Record<string, string> = {
  ACTIVE:             'Active',
  PENDING_ACCEPTANCE: 'Pending acceptance',
  DONE:               'Done',
  CANCELLED:          'Cancelled',
  REJECTED:           'Rejected',
  EXPIRED:            'Expired',
}

const STATUS_COLOR: Record<string, string> = {
  ACTIVE:             'bg-green-100 text-green-800',
  PENDING_ACCEPTANCE: 'bg-yellow-100 text-yellow-800',
  DONE:               'bg-gray-100 text-gray-600',
  CANCELLED:          'bg-red-100 text-red-700',
  REJECTED:           'bg-red-100 text-red-700',
  EXPIRED:            'bg-gray-100 text-gray-500',
}

export default function TaskDetail() {
  const { taskId } = useParams<{ taskId: string }>()
  const navigate = useNavigate()
  const [uid, setUid] = useState('')
  const [task, setTask] = useState<Task | null>(null)
  const [items, setItems] = useState<ShoppingListItem[]>([])
  const [users, setUsers] = useState<Record<string, UserProfile>>({})
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)

  useEffect(() => {
    supabase.auth.getUser().then(({ data }) => {
      if (data.user) { setUid(data.user.id); load(data.user.id) }
    })
  }, [taskId])

  async function load(userId: string) {
    setLoading(true)
    const { data: taskData } = await supabase
      .from('tasks').select('*').eq('id', taskId!).single()
    if (!taskData) { setLoading(false); return }
    setTask(taskData as Task)

    const ids = [taskData.created_by_user_id, taskData.assigned_to_user_id].filter(Boolean)
    const { data: profiles } = await supabase.from('users').select('*').in('id', ids)
    if (profiles) {
      const map: Record<string, UserProfile> = {}
      profiles.forEach((p: UserProfile) => { map[p.id] = p })
      setUsers(map)
    }

    if (taskData.task_type === 'SHOPPING_LIST') {
      const { data: itemData } = await supabase
        .from('shopping_list_items').select('*')
        .eq('task_id', taskId!).order('order_index', { ascending: true })
      if (itemData) setItems(itemData as ShoppingListItem[])
    }

    setLoading(false)
  }

  async function toggleItem(itemId: string, checked: boolean) {
    setItems(prev => prev.map(i => i.id === itemId ? { ...i, checked } : i))
    await supabase.from('shopping_list_items')
      .update({ checked, updated_at: Date.now() }).eq('id', itemId)
  }

  async function markDone() {
    if (!task) return
    setSaving(true)
    const { error } = await supabase.from('tasks')
      .update({ status: 'DONE', updated_at: Date.now() }).eq('id', task.id)
    if (!error) setTask({ ...task, status: 'DONE' })
    setSaving(false)
  }

  async function acceptTask() {
    if (!task) return
    setSaving(true)
    const { error } = await supabase.from('tasks')
      .update({ status: 'ACTIVE', updated_at: Date.now() }).eq('id', task.id)
    if (!error) setTask({ ...task, status: 'ACTIVE' })
    setSaving(false)
  }

  async function rejectTask() {
    if (!task) return
    setSaving(true)
    const { error } = await supabase.from('tasks')
      .update({ status: 'REJECTED', updated_at: Date.now() }).eq('id', task.id)
    if (!error) { navigate('/tasks') }
    setSaving(false)
  }

  async function cancelTask() {
    if (!task) return
    if (!confirm('Cancel this task?')) return
    setSaving(true)
    const { error } = await supabase.from('tasks')
      .update({ status: 'CANCELLED', updated_at: Date.now() }).eq('id', task.id)
    if (!error) { navigate('/tasks') }
    setSaving(false)
  }

  if (loading) return <div className="text-gray-400 text-sm">Loading…</div>
  if (!task) return <div className="text-red-500">Task not found.</div>

  const isCreator = task.created_by_user_id === uid
  const isAssignee = task.assigned_to_user_id === uid
  const isActive = task.status === 'ACTIVE'
  const isPending = task.status === 'PENDING_ACCEPTANCE'
  const isDone = task.status === 'DONE' || task.status === 'CANCELLED' || task.status === 'REJECTED'
  const assignee = users[task.assigned_to_user_id]
  const creator = users[task.created_by_user_id]
  const isSelf = task.created_by_user_id === task.assigned_to_user_id

  const allChecked = items.length > 0 && items.every(i => i.checked)

  return (
    <div className="space-y-5 max-w-xl">
      <div className="flex items-center gap-3">
        <button onClick={() => navigate('/tasks')} className="text-gray-500 hover:text-gray-700">←</button>
        <h1 className="text-xl font-bold text-gray-900">Task detail</h1>
      </div>

      <div className="bg-white rounded-xl border border-gray-100 shadow-sm p-5 space-y-4">
        <div className="flex items-center gap-2 flex-wrap">
          <span className={`text-xs px-2.5 py-0.5 rounded-full font-medium ${STATUS_COLOR[task.status]}`}>
            {STATUS_LABEL[task.status] || task.status}
          </span>
          {task.task_type === 'SHOPPING_LIST' && (
            <span className="text-xs px-2.5 py-0.5 rounded-full bg-purple-100 text-purple-700 font-medium">
              Shopping List
            </span>
          )}
          <span className={`text-xs px-2.5 py-0.5 rounded-full font-medium ${
            task.priority === 'HIGH' ? 'bg-red-100 text-red-800' :
            task.priority === 'LOW'  ? 'bg-gray-100 text-gray-600' :
            'bg-blue-100 text-blue-800'
          }`}>{task.priority}</span>
        </div>

        <h2 className="text-lg font-bold text-gray-900">{task.title}</h2>

        {task.description && (
          <p className="text-gray-600 text-sm whitespace-pre-wrap">{task.description}</p>
        )}

        <div className="text-sm text-gray-500 flex items-center gap-1.5">
          <span className="text-base">📍</span>
          <span>
            {task.place_mode === 'TYPE'
              ? `${task.place_name} — any matching place`
              : task.place_name}
          </span>
        </div>

        {!isSelf && (
          <div className="text-xs text-gray-400 space-y-0.5">
            <div>Created by: <strong>{creator?.display_name || creator?.email || task.created_by_user_id}</strong></div>
            <div>Assigned to: <strong>{assignee?.display_name || assignee?.email || task.assigned_to_user_id}</strong></div>
          </div>
        )}

        {(task.active_from_date || task.active_start_time) && (
          <div className="text-xs text-gray-400">
            Due: {task.active_from_date || ''} {task.active_start_time || ''}
          </div>
        )}
      </div>

      {/* Shopping list */}
      {task.task_type === 'SHOPPING_LIST' && (
        <div className="bg-white rounded-xl border border-gray-100 shadow-sm p-5 space-y-3">
          <div className="flex items-center justify-between">
            <h3 className="font-semibold text-gray-900">
              Shopping List <span className="text-gray-400 font-normal text-sm">({items.length} items)</span>
            </h3>
            {allChecked && (
              <span className="text-xs text-green-700 bg-green-50 px-2 py-0.5 rounded-full">All done ✓</span>
            )}
          </div>
          {items.length === 0 && <p className="text-sm text-gray-400">No items.</p>}
          <div className="space-y-2">
            {items.map(item => (
              <label key={item.id} className="flex items-center gap-3 cursor-pointer group">
                <input type="checkbox" checked={item.checked}
                  onChange={e => toggleItem(item.id, e.target.checked)}
                  className="w-4 h-4 accent-purple-600 rounded" />
                <span className={`text-sm ${item.checked ? 'line-through text-gray-400' : 'text-gray-800'}`}>
                  {item.text}
                </span>
              </label>
            ))}
          </div>
        </div>
      )}

      {/* Actions */}
      {!isDone && (
        <div className="space-y-3">
          {isAssignee && isPending && (
            <div className="flex gap-3">
              <button onClick={acceptTask} disabled={saving} className="btn-primary flex-1">Accept task</button>
              <button onClick={rejectTask} disabled={saving} className="btn-outline flex-1 border-red-300 text-red-600 hover:bg-red-50">Reject</button>
            </div>
          )}
          {isActive && (isAssignee || isSelf) && (
            <button onClick={markDone} disabled={saving}
              className="btn-primary w-full">
              {saving ? 'Saving…' : 'Mark as done'}
            </button>
          )}
          {isCreator && !isPending && !isDone && (
            <button onClick={cancelTask} disabled={saving}
              className="w-full px-4 py-2 rounded-lg border border-red-300 text-red-600 text-sm font-medium hover:bg-red-50 transition">
              Cancel task
            </button>
          )}
        </div>
      )}

      {isDone && (
        <div className="bg-gray-50 rounded-xl p-4 text-center text-sm text-gray-500">
          This task is <strong>{STATUS_LABEL[task.status]?.toLowerCase()}</strong>.
        </div>
      )}
    </div>
  )
}
