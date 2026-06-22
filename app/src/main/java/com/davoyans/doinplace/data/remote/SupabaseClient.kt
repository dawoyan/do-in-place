package com.davoyans.doinplace.data.remote

import android.content.Context
import android.util.Log
import com.davoyans.doinplace.BuildConfig
import com.davoyans.doinplace.util.DiagLog
import com.davoyans.doinplace.data.model.*
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Supabase REST (PostgREST) client.
 *
 * All authenticated requests go through [withAuthRetry] which:
 *  1. Proactively refreshes the token if it is expiring within 60 s.
 *  2. On 401 JWT-expired, refreshes and retries the request once.
 *  3. If refresh fails, throws [SupabaseAuthClient.SessionExpiredException] for the UI to handle.
 */
class SupabaseClient(private val context: Context) {

    private val baseUrl get() = BuildConfig.SUPABASE_URL.trimEnd('/')
    private val anonKey get() = BuildConfig.SUPABASE_ANON_KEY
    private val authClient by lazy { SupabaseAuthClient(context) }

    private class JwtExpiredException : Exception("JWT expired — needs refresh")

    private fun String.isJwtExpiredError(): Boolean =
        contains("JWT expired", ignoreCase = true) ||
        contains("PGRST303", ignoreCase = true) ||
        contains("invalid JWT", ignoreCase = true)

    // ── Auth-retry wrapper ────────────────────────────────────────────────

    /**
     * Executes [block] with a valid access token.
     * On JwtExpiredException, refreshes once and retries.
     * Propagates [SupabaseAuthClient.SessionExpiredException] to callers.
     */
    private fun withAuthRetry(path: String, block: (token: String) -> String): String {
        val token = authClient.getValidAccessToken()
        return try {
            block(token)
        } catch (e: JwtExpiredException) {
            val shortPath = path.substringBefore("?")
            DiagLog.d("SUPABASE", "401 JWT expired path=$shortPath")
            Log.d(TAG, "JWT expired on $path — refreshing and retrying")
            val fresh = try {
                authClient.refreshAndGetToken(force = true, failedToken = token)
                    .also {
                        DiagLog.d("SUPABASE", "retry after refresh path=$shortPath")
                        Log.d(TAG, "Retry after refresh: $path")
                    }
            } catch (se: SupabaseAuthClient.SessionExpiredException) {
                DiagLog.e("AUTH", "session expired — cannot retry path=$shortPath")
                throw se
            }
            try {
                block(fresh).also {
                    DiagLog.d("SUPABASE", "retry success path=$shortPath")
                }
            } catch (e2: JwtExpiredException) {
                DiagLog.e("SUPABASE", "retry failed JWT expired again path=$shortPath")
                throw SupabaseAuthClient.SessionExpiredException()
            }
        }
    }

    // ── User profile ───────────────────────────────────────────────────────

    fun upsertUserProfile(userId: String, email: String, displayName: String) {
        val body = JSONObject().apply {
            put("id", userId)
            put("email", email)
            put("display_name", displayName)
        }
        upsert("/rest/v1/users", body)
    }

    fun updateFcmToken(userId: String, token: String) {
        patch("/rest/v1/users?id=eq.$userId", JSONObject().put("fcm_token", token))
    }

    fun updateNotifyOnTaskCancelledPref(userId: String, enabled: Boolean) {
        patch("/rest/v1/users?id=eq.$userId",
            JSONObject().put("notify_on_task_cancelled", enabled))
    }

    fun notifyTaskEvent(
        eventType: String,
        taskId: String,
        taskTitle: String,
        actorUserId: String,
        actorName: String,
        actorEmail: String? = null,
        creatorUserId: String,
        targetUserId: String? = null,
        taskType: TaskType? = null
    ) {
        val body = JSONObject().apply {
            put("event_type", eventType)
            put("task_id", taskId)
            put("task_title", taskTitle)
            put("actor_user_id", actorUserId)
            put("actor_name", actorName)
            if (!actorEmail.isNullOrBlank()) put("actor_email", actorEmail)
            put("creator_user_id", creatorUserId)
            if (targetUserId != null) put("target_user_id", targetUserId)
            if (taskType != null) put("task_type", taskType.name)
        }
        callEdgeFunction("notify-task-event", body)
    }

    fun notifyConnectionRequest(toUserId: String, fromUserId: String, fromName: String, fromEmail: String) {
        val body = JSONObject().apply {
            put("event_type", "connection_request")
            put("target_user_id", toUserId)
            put("actor_user_id", fromUserId)
            put("actor_name", fromName)
            put("actor_email", fromEmail)
            put("task_id", "")
            put("task_title", "")
            put("creator_user_id", fromUserId)
        }
        callEdgeFunction("notify-task-event", body)
    }

    fun notifyConnectionAccepted(toUserId: String, fromName: String, fromUserId: String) {
        val body = JSONObject().apply {
            put("event_type", "connection_accepted")
            put("target_user_id", toUserId)
            put("actor_user_id", fromUserId)
            put("actor_name", fromName)
            put("task_id", "")
            put("task_title", "")
            put("creator_user_id", fromUserId)
        }
        callEdgeFunction("notify-task-event", body)
    }

    fun cancelContactInvite(id: String) {
        patch("/rest/v1/contact_invites?id=eq.$id",
            JSONObject().put("status", "CANCELLED").put("updated_at", System.currentTimeMillis()))
    }

    fun resendContactInvite(id: String) {
        patch("/rest/v1/contact_invites?id=eq.$id",
            JSONObject().put("status", "PENDING").put("updated_at", System.currentTimeMillis()))
    }

    fun lookupUserByEmail(email: String): Pair<String, String>? {
        val rows = get("/rest/v1/users?email=eq.${urlEncode(email)}&select=id,display_name&limit=1")
        val row = rows.optJSONObject(0) ?: return null
        return row.getString("id") to row.optString("display_name", "")
    }

    fun lookupUserById(userId: String): Pair<String, String>? {
        if (userId.isBlank() || !UUID_REGEX.matches(userId)) return null
        val rows = get("/rest/v1/users?id=eq.$userId&select=email,display_name&limit=1")
        val row = rows.optJSONObject(0) ?: return null
        return row.optString("email", "") to row.optString("display_name", "")
    }

    // ── Tasks ──────────────────────────────────────────────────────────────

    fun pushTask(task: Task) {
        val useCompat = taskSchemaHasNewColumns == false
        try {
            upsert("/rest/v1/tasks", taskToJson(task, includeNewColumns = !useCompat))
            if (taskSchemaHasNewColumns == null) taskSchemaHasNewColumns = true
        } catch (e: Exception) {
            val msg = e.message ?: ""
            if ("PGRST204" in msg || "is_everywhere" in msg || "place_mode" in msg ||
                "place_type_id" in msg || "place_type_name" in msg || "task_type" in msg) {
                DiagLog.e("SUPABASE_SCHEMA", "new task columns missing — run migration 20260617000000; retrying in compat mode")
                taskSchemaHasNewColumns = false
                upsert("/rest/v1/tasks", taskToJson(task, includeNewColumns = false))
            } else {
                throw e
            }
        }
    }

    fun updateTaskStatus(taskId: String, status: String) {
        patch("/rest/v1/tasks?id=eq.$taskId",
            JSONObject().put("status", status).put("updated_at", System.currentTimeMillis()))
    }

    fun deleteTask(taskId: String) {
        delete("/rest/v1/tasks?id=eq.$taskId")
    }

    fun deleteShoppingItemsForTask(taskId: String) {
        delete("/rest/v1/shopping_list_items?task_id=eq.$taskId")
    }

    fun fetchTasksForUser(uid: String): List<Map<String, Any?>> {
        val rows = get("/rest/v1/tasks?or=(created_by_user_id.eq.$uid,assigned_to_user_id.eq.$uid)&select=*")
        return (0 until rows.length()).map { jsonObjectToMap(rows.getJSONObject(it)) }
    }

    // ── Task events ────────────────────────────────────────────────────────

    fun pushTaskEvent(event: TaskEvent) {
        // Use upsert (on_conflict=id) so re-uploads after a missed markSynced are idempotent.
        upsert("/rest/v1/task_events?on_conflict=id", JSONObject().apply {
            put("id", event.id)
            put("task_id", event.taskId)
            put("type", event.type.name)
            put("actor_user_id", event.actorUserId)
            put("created_at", event.createdAt)
        })
    }

    // ── Contact invites ────────────────────────────────────────────────────

    fun sendContactInvite(contact: TrustedContact) {
        sendContactInvite(
            contact = contact,
            requesterEmailSnapshot = null,
            requesterDisplayNameSnapshot = null
        )
    }

    fun sendContactInvite(
        contact: TrustedContact,
        requesterEmailSnapshot: String?,
        requesterDisplayNameSnapshot: String?
    ) {
        // PENDING_SENT is local-only; always send "PENDING" so the recipient's SyncWorker can find it
        val serverStatus = if (contact.status == ContactStatus.PENDING_SENT) "PENDING" else contact.status.name
        upsert("/rest/v1/contact_invites", JSONObject().apply {
            put("id", contact.id)
            put("from_user_id", contact.userId)
            put("to_user_id", contact.contactUserId)
            put("to_email", contact.contactEmail)
            if (!requesterEmailSnapshot.isNullOrBlank()) put("requester_email_snapshot", requesterEmailSnapshot)
            if (!requesterDisplayNameSnapshot.isNullOrBlank()) put("requester_display_name_snapshot", requesterDisplayNameSnapshot)
            put("status", serverStatus)
            put("created_at", contact.createdAt)
            put("updated_at", contact.updatedAt)
        })
    }

    fun fetchAcceptedOutgoingInvites(uid: String): List<Map<String, Any?>> {
        val rows = get("/rest/v1/contact_invites?from_user_id=eq.$uid&to_user_id=neq.$uid&status=eq.ACCEPTED&select=*")
        return (0 until rows.length()).map { jsonObjectToMap(rows.getJSONObject(it)) }
    }

    fun updateContactStatus(id: String, status: String) {
        patch("/rest/v1/contact_invites?id=eq.$id",
            JSONObject().put("status", status).put("updated_at", System.currentTimeMillis()))
    }

    fun fetchPendingInvitesForUser(uid: String): List<Map<String, Any?>> {
        val rows = get("/rest/v1/contact_invites?to_user_id=eq.$uid&from_user_id=neq.$uid&status=eq.PENDING&select=*")
        return (0 until rows.length()).map { jsonObjectToMap(rows.getJSONObject(it)) }
    }

    // ── Shopping list items ────────────────────────────────────────────────

    fun pushShoppingItems(taskId: String, items: List<ShoppingListItem>) {
        items.forEach { item ->
            upsert("/rest/v1/shopping_list_items", JSONObject().apply {
                put("id", item.id)
                put("task_id", taskId)
                put("text", item.text)
                put("normalized_text", item.normalizedText)
                if (!item.rawText.isNullOrBlank()) put("raw_text", item.rawText)
                else put("raw_text", JSONObject.NULL)
                put("canonical_name", item.canonicalOrText)
                put("order_index", item.orderIndex)
                put("checked", item.checked)
                if (item.checkedByUserId != null) put("checked_by_user_id", item.checkedByUserId)
                else put("checked_by_user_id", JSONObject.NULL)
                if (item.checkedAt != null) put("checked_at", item.checkedAt)
                else put("checked_at", JSONObject.NULL)
                if (item.updatedByUserId != null) put("updated_by_user_id", item.updatedByUserId)
                else put("updated_by_user_id", JSONObject.NULL)
                if (item.addedByUserId != null) put("added_by_user_id", item.addedByUserId)
                else put("added_by_user_id", JSONObject.NULL)
                if (item.addedByDisplayName != null) put("added_by_display_name", item.addedByDisplayName)
                else put("added_by_display_name", JSONObject.NULL)
                if (item.addedAt != null) put("added_at", item.addedAt)
                else put("added_at", JSONObject.NULL)
                if (item.originColorKey != null) put("origin_color_key", item.originColorKey)
                else put("origin_color_key", JSONObject.NULL)
                put("created_at", item.createdAt)
                put("updated_at", item.updatedAt)
            })
        }
    }

    fun softDeleteShoppingItem(itemId: String, deletedByUserId: String, deletedAt: Long) {
        patch("/rest/v1/shopping_list_items?id=eq.$itemId", org.json.JSONObject().apply {
            put("deleted_at", deletedAt)
            put("deleted_by_user_id", deletedByUserId)
            put("updated_at", deletedAt)
        })
    }

    fun fetchShoppingItemsForTask(taskId: String): List<Map<String, Any?>> {
        val rows = get("/rest/v1/shopping_list_items?task_id=eq.$taskId&select=*&order=order_index.asc")
        return (0 until rows.length()).map { jsonObjectToMap(rows.getJSONObject(it)) }
    }

    fun updateShoppingItemChecked(
        itemId: String, taskId: String, checked: Boolean,
        checkedByUserId: String?, checkedAt: Long?, updatedByUserId: String?, updatedAt: Long
    ) {
        patch("/rest/v1/shopping_list_items?id=eq.$itemId", JSONObject().apply {
            put("checked", checked)
            put("updated_at", updatedAt)
            if (checkedByUserId != null) put("checked_by_user_id", checkedByUserId)
            else put("checked_by_user_id", JSONObject.NULL)
            if (checkedAt != null) put("checked_at", checkedAt)
            else put("checked_at", JSONObject.NULL)
            if (updatedByUserId != null) put("updated_by_user_id", updatedByUserId)
            else put("updated_by_user_id", JSONObject.NULL)
        })
        DiagLog.d("SHOP_SYNC", "item checked itemId=${itemId.take(8)} by=${checkedByUserId?.take(8)}")
    }

    // ── Task shares ─────────────────────────────────────────────────────────

    fun createTaskShare(
        shareId: String, taskId: String,
        ownerUserId: String, sharedWithUserId: String, now: Long
    ) {
        DiagLog.d("SHOP_SHARE", "share start taskId=${taskId.take(8)} sharedWith=${sharedWithUserId.take(8)}")
        upsert("/rest/v1/task_shares", JSONObject().apply {
            put("id", shareId)
            put("task_id", taskId)
            put("owner_user_id", ownerUserId)
            put("shared_with_user_id", sharedWithUserId)
            put("permission", "EDIT")
            put("status", "ACTIVE")
            put("created_at", now)
            put("updated_at", now)
        })
        DiagLog.d("SHOP_SHARE", "share created taskId=${taskId.take(8)} sharedWith=${sharedWithUserId.take(8)}")
    }

    fun fetchTaskSharesForUser(uid: String): List<Map<String, Any?>> {
        val rows = get("/rest/v1/task_shares?or=(owner_user_id.eq.$uid,shared_with_user_id.eq.$uid)&select=*")
        return (0 until rows.length()).map { jsonObjectToMap(rows.getJSONObject(it)) }
    }

    fun fetchTasksByIds(ids: List<String>): List<Map<String, Any?>> {
        if (ids.isEmpty()) return emptyList()
        val idList = ids.joinToString(",")
        DiagLog.d("TASK_SYNC", "fetchTasksByIds count=${ids.size}")
        val rows = get("/rest/v1/tasks?id=in.($idList)&select=*")
        return (0 until rows.length()).map { jsonObjectToMap(rows.getJSONObject(it)) }
    }

    // ── Connection invites (code-based) ──────────────────────────────────

    private fun generateShortInviteCode(): String {
        // Avoids visually confusing chars: O/0, I/1, L
        val charset = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
        return (1..6).map { charset.random() }.joinToString("")
    }

    fun createConnectionInvite(userId: String): String {
        val raw = generateShortInviteCode()
        val display = "${raw.take(3)}-${raw.drop(3)}"
        val now = System.currentTimeMillis()
        DiagLog.d("SUPABASE", "createConnectionInvite display=$display userId=${userId.take(8)}")
        upsert("/rest/v1/contact_invites", JSONObject().apply {
            put("id", UUID.randomUUID().toString())
            put("from_user_id", userId)
            put("to_user_id", JSONObject.NULL)
            put("to_email", JSONObject.NULL)
            put("invite_code", display)
            put("normalized_invite_code", raw)
            put("status", "ACTIVE")
            put("created_at", now)
            put("updated_at", now)
            put("expires_at", now + 7L * 24 * 60 * 60 * 1000L)
        })
        DiagLog.d("SUPABASE", "createConnectionInvite done display=$display")
        return display
    }

    fun getConnectionInviteByNormalizedCode(normalizedCode: String): Map<String, Any?>? {
        // Filter to ACTIVE only; order deterministically to handle any stale duplicates gracefully
        val rows = get(
            "/rest/v1/contact_invites" +
            "?normalized_invite_code=eq.${urlEncode(normalizedCode)}" +
            "&status=eq.ACTIVE" +
            "&order=created_at.desc" +
            "&limit=1"
        )
        return if (rows.length() > 0) jsonObjectToMap(rows.getJSONObject(0)) else null
    }

    fun acceptConnectionInvite(inviteId: String, usedByUserId: String) {
        val now = System.currentTimeMillis()
        patch(
            "/rest/v1/contact_invites?id=eq.$inviteId",
            JSONObject().apply {
                put("to_user_id", usedByUserId)
                put("status", "PENDING_CONFIRMATION")
                put("used_by_user_id", usedByUserId)
                put("used_at", now)
                put("updated_at", now)
            }
        )
    }

    fun deleteCurrentUserData(): Result<Unit> = runCatching {
        callEdgeFunction("delete-user-data", JSONObject())
    }

    // ── HTTP helpers ───────────────────────────────────────────────────────

    private fun callEdgeFunction(name: String, body: JSONObject) {
        val path = "functions/v1/$name"
        withAuthRetry(path) { token ->
            val conn = URL("$baseUrl/$path").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 12000
            conn.readTimeout = 15000
            conn.setRequestProperty("apikey", anonKey)
            conn.setRequestProperty("Content-Type", "application/json")
            if (token.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $token")
            writeBody(conn, body)
            readResponse(conn, path)
        }
    }

    private fun get(path: String): JSONArray {
        val text = withAuthRetry(path) { token ->
            val conn = openConnection("GET", path, token)
            conn.setRequestProperty("Accept", "application/json")
            readResponse(conn, path)
        }
        return if (text.startsWith("[")) JSONArray(text) else JSONArray()
    }

    private fun insert(path: String, body: JSONObject) {
        withAuthRetry(path) { token ->
            val conn = openConnection("POST", path, token)
            conn.setRequestProperty("Prefer", "return=minimal")
            writeBody(conn, body)
            readResponse(conn, path)
        }
    }

    private fun upsert(path: String, body: JSONObject) {
        withAuthRetry(path) { token ->
            val conn = openConnection("POST", path, token)
            conn.setRequestProperty("Prefer", "resolution=merge-duplicates,return=minimal")
            writeBody(conn, body)
            readResponse(conn, path)
        }
    }

    private fun patch(path: String, body: JSONObject) {
        withAuthRetry(path) { token ->
            val conn = openConnection("PATCH", path, token)
            conn.setRequestProperty("Prefer", "return=minimal")
            writeBody(conn, body)
            readResponse(conn, path)
        }
    }

    private fun delete(path: String) {
        withAuthRetry(path) { token ->
            val conn = openConnection("DELETE", path, token)
            conn.setRequestProperty("Prefer", "return=minimal")
            readResponse(conn, path)
        }
    }

    private fun openConnection(method: String, path: String, token: String): HttpURLConnection {
        val conn = URL("$baseUrl$path").openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 12000
        conn.readTimeout = 15000
        conn.setRequestProperty("apikey", anonKey)
        conn.setRequestProperty("Content-Type", "application/json")
        if (token.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $token")
        return conn
    }

    private fun writeBody(conn: HttpURLConnection, body: JSONObject) {
        conn.doOutput = true
        OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
    }

    private fun readResponse(conn: HttpURLConnection, path: String = ""): String {
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
        val shortPath = path.substringBefore("?")
        Log.d(TAG, "Supabase $code $shortPath jwtExpired=${code == 401 && text.isJwtExpiredError()}")
        if (code !in 200..299) {
            DiagLog.e("SUPABASE", "HTTP $code $shortPath body=${text.take(200)}")
        }
        if (code == 401 && text.isJwtExpiredError()) throw JwtExpiredException()
        if (code !in 200..299) throw IllegalStateException("Supabase $code: ${text.take(300)}")
        return text
    }

    private fun taskToJson(t: Task, includeNewColumns: Boolean = true) = JSONObject().apply {
        put("id", t.id)
        put("title", t.title)
        put("description", t.description)
        put("created_by_user_id", t.createdByUserId)
        put("assigned_to_user_id", t.assignedToUserId)
        put("place_name", t.placeName)
        put("address", t.address)
        put("latitude", t.latitude)
        put("longitude", t.longitude)
        put("radius_meters", t.radiusMeters)
        put("status", t.status.name)
        put("arrival_share_allowed", t.arrivalShareAllowed)
        put("active_from_date", t.activeFromDate)
        put("active_to_date", t.activeToDate)
        put("active_days_of_week", t.activeDaysOfWeek)
        put("active_start_time", t.activeStartTime)
        put("active_end_time", t.activeEndTime)
        put("remind_until_done", t.remindUntilDone)
        if (includeNewColumns) {
            put("priority", t.priority.name)
            put("place_mode", t.placeMode.name)
            put("place_type_id", t.placeTypeId)
            put("place_type_name", t.placeTypeName)
            put("task_type", t.taskType.name)
            put("is_everywhere", t.isEverywhere)
        }
        put("archived", t.archived)
        if (t.archivedAt != null) put("archived_at", t.archivedAt)
        put("created_at", t.createdAt)
        put("updated_at", t.updatedAt)
    }

    fun archiveTaskRemote(taskId: String, archived: Boolean, archivedAt: Long) {
        val body = JSONObject().apply {
            put("archived", archived)
            put("archived_at", if (archived) archivedAt else JSONObject.NULL)
            put("updated_at", System.currentTimeMillis())
        }
        patch("/rest/v1/tasks?id=eq.$taskId", body)
    }

    fun fetchFoodHealthTags(): List<Map<String, Any?>> {
        val rows = get("/rest/v1/food_health_tags?select=id,normalized_name,language,health_tag,suggestion,subcategory&order=normalized_name")
        return (0 until rows.length()).map { jsonObjectToMap(rows.getJSONObject(it)) }
    }


    private fun jsonObjectToMap(obj: JSONObject): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        for (key in obj.keys()) map[key] = obj.opt(key)
        return map
    }

    private fun urlEncode(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    companion object {
        private const val TAG = "SupabaseClient"
        private val UUID_REGEX = Regex(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
            RegexOption.IGNORE_CASE
        )
        // null = unknown (first push will probe), true = full schema, false = compat mode (pre-migration)
        @Volatile var taskSchemaHasNewColumns: Boolean? = null
    }
}
