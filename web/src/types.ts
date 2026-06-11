export type TaskStatus = 'PENDING_ACCEPTANCE' | 'ACTIVE' | 'DONE' | 'CANCELLED' | 'REJECTED' | 'EXPIRED'
export type TaskPriority = 'HIGH' | 'NORMAL' | 'LOW'
export type PlaceMode = 'EXACT' | 'TYPE'
export type TaskType = 'SIMPLE' | 'SHOPPING_LIST'
export type ContactStatus = 'PENDING' | 'ACCEPTED' | 'BLOCKED'
export type InviteStatus = 'ACTIVE' | 'USED' | 'EXPIRED' | 'CANCELLED'

export interface UserProfile {
  id: string
  email: string
  display_name: string
  fcm_token?: string
}

export interface Task {
  id: string
  title: string
  description?: string
  created_by_user_id: string
  assigned_to_user_id: string
  place_name: string
  address?: string
  latitude: number
  longitude: number
  radius_meters: number
  status: TaskStatus
  priority: TaskPriority
  place_mode: PlaceMode
  place_type_id?: string
  place_type_name?: string
  task_type: TaskType
  active_from_date?: string
  active_start_time?: string
  arrival_share_allowed: boolean
  created_at: number
  updated_at: number
}

export interface ShoppingListItem {
  id: string
  task_id: string
  text: string
  normalized_text: string
  order_index: number
  checked: boolean
  created_at: number
  updated_at: number
}

export interface TrustedContact {
  id: string
  from_user_id: string
  to_user_id: string
  to_email: string
  status: ContactStatus
  created_at: number
  updated_at: number
}

export interface ConnectionInvite {
  id: string
  invite_code: string
  created_by_user_id: string
  status: InviteStatus
  created_at: number
  expires_at: number
  max_uses: number
  used_by_user_id?: string
  used_at?: number
}
