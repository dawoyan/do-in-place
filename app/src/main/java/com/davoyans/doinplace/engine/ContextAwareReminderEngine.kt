package com.davoyans.doinplace.engine

import android.content.Context
import com.davoyans.doinplace.data.db.AppDatabase
import com.davoyans.doinplace.data.model.*
import com.davoyans.doinplace.data.remote.SupabaseAuthClient
import com.davoyans.doinplace.util.DiagLog
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

class ContextAwareReminderEngine(
    private val context: Context,
    private val db: AppDatabase = AppDatabase.get(context)
) {
    private val activityProvider = ActivityContextProvider(context)
    private val quietPolicy = QuietHoursPolicy(
        context.getSharedPreferences("dip_prefs", Context.MODE_PRIVATE)
    )
    private val authClient = SupabaseAuthClient(context)

    companion object {
        private const val SCORE_NOTIFY = 70
        private const val SCORE_MAYBE  = 40

        private const val BONUS_NEARBY_PLACE   = 70
        private const val BONUS_URGENT         = 50
        private const val BONUS_OVERDUE        = 80
        private const val BONUS_DUE_15MIN      = 70
        private const val BONUS_DUE_1HOUR      = 60
        private const val BONUS_DUE_2HOURS     = 40
        private const val BONUS_DAYTIME        = 10
        private const val BONUS_ACTIVE_MOVING  = 15
        private const val BONUS_USER_COMPLETES = 15

        private const val PENALTY_AT_HOME     = 30
        private const val PENALTY_QUIET_HOURS = 60
        private const val PENALTY_USER_IGNORES = 20

        private const val PREF_SMART_ENABLED   = "smart_reminders_enabled"
        private const val PREF_SUPPRESS_HOME   = "smart_suppress_at_home"
        private const val PREF_SUPPRESS_NIGHT  = "smart_suppress_at_night"

        private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm")
    }

    fun isEnabled(): Boolean =
        context.getSharedPreferences("dip_prefs", Context.MODE_PRIVATE)
            .getBoolean(PREF_SMART_ENABLED, true)

    suspend fun buildSnapshot(location: android.location.Location?): ContextSnapshot {
        val now   = System.currentTimeMillis()
        val hour  = LocalDateTime.now().hour
        val uid   = authClient.getCurrentUserId() ?: ""
        val places = if (uid.isNotBlank()) db.savedPlaceDao().getAll(uid) else emptyList()

        val (actType, conf) = activityProvider.getActivityType(location)
        val isDriving = actType == UserActivityType.IN_VEHICLE
        val isMoving  = actType in setOf(
            UserActivityType.WALKING, UserActivityType.RUNNING,
            UserActivityType.ON_BICYCLE, UserActivityType.ON_FOOT
        ) || isDriving

        return ContextSnapshot(
            nowMillis            = now,
            localHour            = hour,
            isDaytime            = quietPolicy.isDaytime(hour),
            isNight              = quietPolicy.isNight(hour),
            isQuietHours         = quietPolicy.isQuietHours(hour),
            currentLocation      = location,
            locationAgeMs        = location?.let { now - it.time },
            locationAccuracyMeters = location?.accuracy,
            isAtHome             = HomeDetector.isAtHome(location, places),
            activityType         = actType,
            activityConfidence   = conf,
            isMoving             = isMoving,
            isDriving            = isDriving
        )
    }

    suspend fun evaluate(
        task: Task,
        ctx: ContextSnapshot,
        matchedPlaceName: String?  = null,
        matchedPlaceAddress: String? = null,
        distanceMeters: Float?     = null
    ): ReminderDecision {
        if (!isEnabled()) {
            return ReminderDecision(
                shouldNotify = true,
                reason       = ReminderDecisionReason.NEARBY_EXACT_PLACE,
                score        = 100
            )
        }

        DiagLog.d("SMART_REMINDER",
            "context activity=${ctx.activityType} isHome=${ctx.isAtHome} " +
            "isNight=${ctx.isNight} isQuiet=${ctx.isQuietHours} locationAgeMs=${ctx.locationAgeMs}")
        DiagLog.d("SMART_REMINDER",
            "evaluate taskId=${task.id.take(8)} title=${task.title} priority=${task.priority}")

        val dueUrgency = computeDueUrgency(task, ctx.nowMillis)
        val isDueSoon  = dueUrgency >= DueUrgency.DUE_WITHIN_1_HOUR
        val isUrgent   = task.priority == TaskPriority.URGENT
        val isHomeTask = HomeDetector.isHomeName(task.placeName)

        // Stale location: suppress only place-triggered reminders, not due-date ones
        if (matchedPlaceName == null && ctx.locationAgeMs != null && ctx.locationAgeMs > 15 * 60_000L) {
            DiagLog.d("SMART_REMINDER",
                "suppressed taskId=${task.id.take(8)} reason=SUPPRESSED_STALE_LOCATION")
            return ReminderDecision(
                shouldNotify  = false,
                reason        = ReminderDecisionReason.SUPPRESSED_STALE_LOCATION,
                score         = 0,
                suppressReason = "location too old (${ctx.locationAgeMs / 60_000} min)"
            )
        }

        var score = 0

        if (matchedPlaceName != null) {
            score += BONUS_NEARBY_PLACE
            distanceMeters?.let {
                DiagLog.d("SMART_REMINDER", "nearby place distance=${it.toInt()} radius=${task.radiusMeters}")
            }
        }

        if (isUrgent) score += BONUS_URGENT

        score += when (dueUrgency) {
            DueUrgency.OVERDUE               -> BONUS_OVERDUE
            DueUrgency.DUE_WITHIN_15_MINUTES -> BONUS_DUE_15MIN
            DueUrgency.DUE_WITHIN_1_HOUR     -> BONUS_DUE_1HOUR
            DueUrgency.DUE_WITHIN_2_HOURS    -> BONUS_DUE_2HOURS
            else                             -> 0
        }

        if (ctx.isDaytime) score += BONUS_DAYTIME
        if (ctx.isDriving || ctx.isMoving) score += BONUS_ACTIVE_MOVING

        val prefs          = context.getSharedPreferences("dip_prefs", Context.MODE_PRIVATE)
        val suppressHome   = prefs.getBoolean(PREF_SUPPRESS_HOME,  true)
        val suppressNight  = prefs.getBoolean(PREF_SUPPRESS_NIGHT, true)

        if (ctx.isAtHome && !isHomeTask && !isUrgent && !isDueSoon && suppressHome)
            score -= PENALTY_AT_HOME
        if (ctx.isQuietHours && !isUrgent && !isDueSoon && suppressNight)
            score -= PENALTY_QUIET_HOURS

        // Learning adjustments (TYPE tasks only — needs placeTypeId for meaningful signal)
        val uid = authClient.getCurrentUserId() ?: ""
        if (uid.isNotBlank() && !task.placeTypeId.isNullOrBlank()) {
            val ignores = db.reminderOutcomeDao().countIgnoredNightlyForType(uid, task.placeTypeId)
            if (ignores >= 3) {
                score -= PENALTY_USER_IGNORES
                DiagLog.d("SMART_REMINDER", "learning: user ignores similar (n=$ignores), penalty=$PENALTY_USER_IGNORES")
            }
            val completes = db.reminderOutcomeDao().countCompletedDaytimeForType(uid, task.placeTypeId)
            if (completes >= 3) {
                score += BONUS_USER_COMPLETES
                DiagLog.d("SMART_REMINDER", "learning: user completes similar (n=$completes), bonus=$BONUS_USER_COMPLETES")
            }
        }

        DiagLog.d("SMART_REMINDER", "score=$score dueUrgency=$dueUrgency isUrgent=$isUrgent")

        return buildDecision(score, task, ctx, dueUrgency, isDueSoon, isUrgent,
            matchedPlaceName, matchedPlaceAddress, distanceMeters)
    }

    private fun buildDecision(
        score: Int, task: Task, ctx: ContextSnapshot,
        dueUrgency: DueUrgency, isDueSoon: Boolean, isUrgent: Boolean,
        matchedPlaceName: String?, matchedPlaceAddress: String?, distanceMeters: Float?
    ): ReminderDecision {
        when {
            score >= SCORE_NOTIFY -> {
                val reason = when {
                    isUrgent && isDueSoon        -> ReminderDecisionReason.URGENT_DUE_SOON
                    isDueSoon || dueUrgency == DueUrgency.OVERDUE -> ReminderDecisionReason.DUE_SOON
                    task.placeMode == PlaceMode.TYPE -> ReminderDecisionReason.NEARBY_PLACE_TYPE
                    else                         -> ReminderDecisionReason.NEARBY_EXACT_PLACE
                }
                val mode = when {
                    matchedPlaceName != null && isDueSoon -> NotificationMode.COMBINED_PLACE_AND_DUE_REMINDER
                    matchedPlaceName != null              -> NotificationMode.PLACE_REMINDER
                    else                                  -> NotificationMode.DUE_REMINDER
                }
                DiagLog.d("SMART_REMINDER", "decision=notify reason=$reason score=$score")
                return ReminderDecision(
                    shouldNotify              = true,
                    reason                    = reason,
                    score                     = score,
                    matchedPlaceName          = matchedPlaceName,
                    matchedPlaceAddress       = matchedPlaceAddress,
                    matchedPlaceDistanceMeters = distanceMeters,
                    notificationMode          = mode
                )
            }
            score >= SCORE_MAYBE -> {
                if (isDueSoon || isUrgent) {
                    val reason = if (isUrgent) ReminderDecisionReason.URGENT_DUE_SOON
                                 else ReminderDecisionReason.DUE_SOON
                    DiagLog.d("SMART_REMINDER", "decision=notify (maybe+due) reason=$reason score=$score")
                    return ReminderDecision(
                        shouldNotify  = true,
                        reason        = reason,
                        score         = score,
                        matchedPlaceName = matchedPlaceName,
                        matchedPlaceAddress = matchedPlaceAddress,
                        notificationMode = NotificationMode.DUE_REMINDER
                    )
                }
                val reason = suppressReason(ctx)
                DiagLog.d("SMART_REMINDER", "suppressed reason=$reason score=$score taskId=${task.id.take(8)}")
                return ReminderDecision(
                    shouldNotify  = false, reason = reason, score = score,
                    suppressReason = reason.name.lowercase().replace('_', ' ')
                )
            }
            else -> {
                val reason = suppressReason(ctx, matchedPlaceName == null)
                DiagLog.d("SMART_REMINDER", "suppressed reason=$reason score=$score taskId=${task.id.take(8)}")
                return ReminderDecision(
                    shouldNotify  = false, reason = reason, score = score,
                    suppressReason = reason.name.lowercase().replace('_', ' ')
                )
            }
        }
    }

    private fun suppressReason(ctx: ContextSnapshot, noPlace: Boolean = false): ReminderDecisionReason = when {
        ctx.isQuietHours      -> ReminderDecisionReason.SUPPRESSED_QUIET_HOURS
        ctx.isAtHome          -> ReminderDecisionReason.SUPPRESSED_AT_HOME
        noPlace               -> ReminderDecisionReason.SUPPRESSED_NOT_NEAR_PLACE
        else                  -> ReminderDecisionReason.SUPPRESSED_LOW_SCORE
    }

    fun computeDueUrgency(task: Task, nowMillis: Long): DueUrgency {
        val dateStr = task.activeFromDate ?: return DueUrgency.NO_DUE_DATE
        val timeStr = task.activeStartTime ?: "00:00"
        return try {
            val dueMillis = LocalDateTime
                .of(LocalDate.parse(dateStr, DATE_FMT), LocalTime.parse(timeStr, TIME_FMT))
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val diff = dueMillis - nowMillis
            when {
                diff < 0                   -> DueUrgency.OVERDUE
                diff <= 15 * 60_000L       -> DueUrgency.DUE_WITHIN_15_MINUTES
                diff <= 60 * 60_000L       -> DueUrgency.DUE_WITHIN_1_HOUR
                diff <= 2 * 60 * 60_000L   -> DueUrgency.DUE_WITHIN_2_HOURS
                else                       -> DueUrgency.DUE_LATER
            }
        } catch (_: Exception) { DueUrgency.NO_DUE_DATE }
    }

    suspend fun recordOutcome(
        task: Task,
        ctx: ContextSnapshot,
        dueUrgency: DueUrgency,
        decision: ReminderDecision,
        userId: String
    ) {
        if (userId.isBlank()) return
        val outcome = ReminderOutcome(
            id         = UUID.randomUUID().toString(),
            userId     = userId,
            taskId     = task.id,
            taskType   = task.taskType.name,
            placeTypeId = task.placeTypeId,
            placeId    = task.placeId,
            activityType = ctx.activityType.name,
            isAtHome   = ctx.isAtHome,
            isNight    = ctx.isNight,
            isDriving  = ctx.isDriving,
            priority   = task.priority.name,
            dueUrgency = dueUrgency.name,
            decision   = if (decision.shouldNotify) "SHOWN" else "SUPPRESSED",
            wasShown   = decision.shouldNotify
        )
        db.reminderOutcomeDao().insert(outcome)
    }
}
