import { Link, Outlet, useLocation, useNavigate } from 'react-router-dom'
import { supabase } from '../lib/supabase'

const NAV = [
  { to: '/tasks',       label: 'Tasks',       icon: '✓' },
  { to: '/connections', label: 'Connections', icon: '👥' },
  { to: '/settings',    label: 'Settings',    icon: '⚙' },
]

export default function Layout() {
  const { pathname } = useLocation()
  const navigate = useNavigate()

  async function handleLogout() {
    await supabase.auth.signOut()
    navigate('/login', { replace: true })
  }

  return (
    <div className="min-h-screen bg-gray-50 flex flex-col">
      {/* Top bar */}
      <header className="bg-purple-700 text-white px-4 py-3 flex items-center justify-between shadow">
        <Link to="/tasks" className="font-bold text-lg tracking-tight">Do In Place</Link>
        <button onClick={handleLogout} className="text-sm opacity-80 hover:opacity-100">Sign out</button>
      </header>

      {/* Note */}
      <div className="bg-amber-50 border-b border-amber-200 px-4 py-2 text-xs text-amber-800">
        For reliable place reminders when the app is closed, use the Android app. The web version is for managing tasks, connections, and invitations.
      </div>

      {/* Main content */}
      <main className="flex-1 max-w-3xl mx-auto w-full px-4 py-6">
        <Outlet />
      </main>

      {/* Bottom navigation (mobile) */}
      <nav className="sticky bottom-0 bg-white border-t flex md:hidden">
        {NAV.map(({ to, label, icon }) => (
          <Link
            key={to}
            to={to}
            className={`flex-1 flex flex-col items-center py-2 text-xs gap-0.5 transition-colors
              ${pathname.startsWith(to) ? 'text-purple-700 font-semibold' : 'text-gray-500'}`}
          >
            <span className="text-lg">{icon}</span>
            {label}
          </Link>
        ))}
      </nav>

      {/* Sidebar navigation (desktop) */}
      <nav className="hidden md:flex fixed top-0 left-0 h-full w-48 bg-white border-r flex-col pt-20 gap-1 px-2">
        {NAV.map(({ to, label, icon }) => (
          <Link
            key={to}
            to={to}
            className={`flex items-center gap-3 px-3 py-2 rounded-lg transition-colors
              ${pathname.startsWith(to) ? 'bg-purple-50 text-purple-700 font-medium' : 'text-gray-600 hover:bg-gray-50'}`}
          >
            <span>{icon}</span>{label}
          </Link>
        ))}
      </nav>
    </div>
  )
}
