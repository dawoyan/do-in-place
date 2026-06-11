import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { supabase } from '../lib/supabase'
import type { Task, UserProfile } from '../types'

const STATUS_COLOR: Record<string, string> = {
  ACTIVE:             'bg-green-100 text-green-800',
  PENDING_ACCEPTANCE: 'bg-yellow-100 text-yellow-800',
  DONE:               'bg-gray-100 text-gray-600',
  CANCELLED:          'bg-red-100 text-red-700',
  REJECTED:           'bg-red-100 text-red-700',
  EXPIRED:            'bg-gray-100 text-gray-500',
}

const PRIORITY_COLOR: Record<string, string> = {
  HIGH:   'bg-red-100 text-red-800',
  NORMAL: 'bg-blue-100 text-blue-800',
  LOW:    'bg-gray-100 text-gray-600',
}

export default function TaskList() {
  const [tasks, setTasks] = useState<Task[]>([])
  const [users, setUsers] = useState<Record<string, UserProfile>>({})
  const [loading, setLoading] = useState(true)
  const [uid, setUid] = useState('')
  const [filter, setFilter] = useState<'active' | 'all'>('active')

  useEffect(() => {
    supabase.auth.getUser().then(({ data }) => {
      if (data.user) {
        setUid(data.user.id)
        loadTasks(data.user.id)
      }
    })
  }, [])

  async function loadTasks(userId: string) {
    setLoading(true)
    const { data } = await supabase
      .from('tasks')
      .select('*')
      .or(`created_by_user_id.eq.${userId},assigned_to_user_id.eq.${userId}`)
      .not('status', 'in', '(CANCELLED,REJECTED,EXPIRED)')
      .order('created_at', { ascending: false })

    if (data) {
      setTasks(data as Task[])
      // Load user profiles for display
      const ids = [...new Set(data.flatMap((t: Task) => [t.created_by_user_id, t.assigned_to_user_id]))]
      const { data: profiles } = await supabase.from('users').select('*').in('id', ids)
      if (profiles) {
        const map: Record<string, UserProfile> = {}
        profiles.forEach((p: UserProfile) => { map[p.id] = p })
        setUsers(map)
      }
    }
    setLoading(false)
  }

  const displayed = filter === 'active'
    ? tasks.filter(t => t.status === 'ACTIVE' || t.status === 'PENDING_ACCEPTANCE')
    : tasks

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h1 className="text-xl font-bold text-gray-900">Tasks</h1>
        <Link to="/tasks/new" className="btn-primary text-sm">+ New task</Link>
      </div>

      <div className="flex gap-2">
        {(['active', 'all'] as const).map(f => (
          <button
            key={f}
            onClick={() => setFilter(f)}
            className={`px-3 py-1.5 rounded-full text-sm font-medium transition ${
              filter === f ? 'bg-purple-600 text-white' : 'bg-white border text-gray-600 hover:bg-gray-50'
            }`}
          >{f === 'active' ? 'Active' : 'All'}</button>
        ))}
      </div>

      {loading && <div className="text-gray-400 text-sm">Loading…</div>}

      {!loading && displayed.length === 0 && (
        <div className="text-center py-12 text-gray-400">
          <div className="text-4xl mb-2">📍</div>
          <p>No tasks yet.</p>
          <Link to="/tasks/new" className="text-purple-600 text-sm mt-2 inline-block">Create your first task</Link>
        </div>
      )}

      <div className="space-y-3">
        {displayed.map(task => {
          const isSelf = task.created_by_user_id === uid && task.assigned_to_user_id === uid
          const assignee = users[task.assigned_to_user_id]
          const creator = users[task.created_by_user_id]
          return (
            <Link key={task.id} to={`/tasks/${task.id}`} className="block">
              <div className="bg-white rounded-xl border border-gray-100 shadow-sm p-4 hover:shadow-md transition space-y-2">
                <div className="flex items-center gap-2 flex-wrap">
                  <span className={`text-xs px-2 py-0.5 rounded-full font-medium ${STATUS_COLOR[task.status]}`}>
                    {task.status.replace('_', ' ')}
                  </span>
                  <span className={`text-xs px-2 py-0.5 rounded-full font-medium ${PRIORITY_COLOR[task.priority]}`}>
                    {task.priority}
                  </span>
                  {task.task_type === 'SHOPPING_LIST' && (
                    <span className="text-xs px-2 py-0.5 rounded-full bg-purple-100 text-purple-700 font-medium">
                      Shopping List
                    </span>
                  )}
                </div>
                <div className="font-semibold text-gray-900">{task.title}</div>
                <div className="text-sm text-gray-500 flex items-center gap-1">
                  <span>📍</span>
                  {task.place_mode === 'TYPE'
                    ? `${task.place_name} — any matching place`
                    : task.place_name}
                </div>
                {!isSelf && (
                  <div className="text-xs text-gray-400">
                    {task.created_by_user_id === uid
                      ? `→ ${assignee?.display_name || assignee?.email || 'Unknown'}`
                      : `From ${creator?.display_name || creator?.email || 'Unknown'}`}
                  </div>
                )}
              </div>
            </Link>
          )
        })}
      </div>
    </div>
  )
}
