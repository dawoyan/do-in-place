import type { Session } from '@supabase/supabase-js'
import { Navigate, Outlet } from 'react-router-dom'

export default function ProtectedRoute({ session }: { session: Session | null }) {
  if (!session) return <Navigate to="/login" replace />
  return <Outlet />
}
