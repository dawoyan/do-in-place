import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import { useEffect, useState } from 'react'
import type { Session } from '@supabase/supabase-js'
import { supabase } from './lib/supabase'
import Login from './pages/Login'
import InviteLanding from './pages/InviteLanding'
import TaskList from './pages/TaskList'
import CreateTask from './pages/CreateTask'
import TaskDetail from './pages/TaskDetail'
import Connections from './pages/Connections'
import Settings from './pages/Settings'
import Layout from './components/Layout'
import ProtectedRoute from './components/ProtectedRoute'

export default function App() {
  const [session, setSession] = useState<Session | null | undefined>(undefined)

  useEffect(() => {
    supabase.auth.getSession().then(({ data }) => setSession(data.session))
    const { data: { subscription } } = supabase.auth.onAuthStateChange((_, s) => setSession(s))
    return () => subscription.unsubscribe()
  }, [])

  // Loading
  if (session === undefined) {
    return (
      <div className="min-h-screen flex items-center justify-center">
        <div className="text-gray-500">Loading…</div>
      </div>
    )
  }

  return (
    <BrowserRouter>
      <Routes>
        <Route path="/login" element={session ? <Navigate to="/tasks" replace /> : <Login />} />
        <Route path="/invite/:code" element={<InviteLanding session={session} />} />

        <Route element={<ProtectedRoute session={session} />}>
          <Route element={<Layout />}>
            <Route path="/tasks" element={<TaskList />} />
            <Route path="/tasks/new" element={<CreateTask />} />
            <Route path="/tasks/:taskId" element={<TaskDetail />} />
            <Route path="/connections" element={<Connections />} />
            <Route path="/settings" element={<Settings session={session} />} />
          </Route>
        </Route>

        <Route path="*" element={<Navigate to={session ? '/tasks' : '/login'} replace />} />
      </Routes>
    </BrowserRouter>
  )
}
