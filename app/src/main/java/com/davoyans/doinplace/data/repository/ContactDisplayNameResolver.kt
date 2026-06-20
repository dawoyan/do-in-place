package com.davoyans.doinplace.data.repository

import com.davoyans.doinplace.data.model.ContactDisplayPref
import com.davoyans.doinplace.data.model.TrustedContact

enum class DisplayNameSource {
    NICKNAME,
    CONTACT_NAME,
    EMAIL,
    PROFILE_NAME,
    EMAIL_PREFIX,
    UNKNOWN
}

data class DisplayNameResult(
    val primary: String,
    val secondaryEmail: String?,
    val source: DisplayNameSource
)

object ContactDisplayNameResolver {

    fun resolveForUi(
        viewerUserId: String,
        otherUserId: String,
        contact: TrustedContact?,
        pref: ContactDisplayPref?,
        emailSnapshot: String? = null,
        nameSnapshot: String? = null
    ): DisplayNameResult = resolve(
        viewerUserId = viewerUserId,
        otherUserId = otherUserId,
        nickname = pref?.nickname,
        contactDisplayName = contact?.contactDisplayName,
        contactEmail = contact?.contactEmail,
        emailSnapshot = emailSnapshot,
        nameSnapshot = nameSnapshot
    )

    fun resolveForNotification(
        viewerUserId: String,
        otherUserId: String,
        contact: TrustedContact?,
        pref: ContactDisplayPref?,
        emailSnapshot: String? = null,
        nameSnapshot: String? = null
    ): DisplayNameResult = resolveForUi(
        viewerUserId = viewerUserId,
        otherUserId = otherUserId,
        contact = contact,
        pref = pref,
        emailSnapshot = emailSnapshot,
        nameSnapshot = nameSnapshot
    )

    fun resolve(
        viewerUserId: String,
        otherUserId: String,
        nickname: String?,
        contactDisplayName: String?,
        contactEmail: String?,
        emailSnapshot: String? = null,
        nameSnapshot: String? = null
    ): DisplayNameResult {
        val cleanNickname = sanitizeDisplayText(nickname)
        val cleanContactName = sanitizeDisplayText(contactDisplayName)
        val realEmail = firstRealEmail(contactEmail, emailSnapshot, otherUserId)
        val cleanProfileName = sanitizeDisplayText(nameSnapshot)
            ?.takeUnless { realEmail != null && it.equals(realEmail, ignoreCase = true) }
        val emailPrefix = emailPrefixCandidate(realEmail)

        return when {
            cleanNickname != null -> DisplayNameResult(
                primary = cleanNickname,
                secondaryEmail = realEmail?.takeUnless { it.equals(cleanNickname, ignoreCase = true) },
                source = DisplayNameSource.NICKNAME
            )

            cleanContactName != null -> DisplayNameResult(
                primary = cleanContactName,
                secondaryEmail = realEmail?.takeUnless { it.equals(cleanContactName, ignoreCase = true) },
                source = DisplayNameSource.CONTACT_NAME
            )

            realEmail != null -> DisplayNameResult(
                primary = realEmail,
                secondaryEmail = null,
                source = DisplayNameSource.EMAIL
            )

            cleanProfileName != null -> DisplayNameResult(
                primary = cleanProfileName,
                secondaryEmail = realEmail?.takeUnless { it.equals(cleanProfileName, ignoreCase = true) },
                source = DisplayNameSource.PROFILE_NAME
            )

            emailPrefix != null -> DisplayNameResult(
                primary = emailPrefix,
                secondaryEmail = null,
                source = DisplayNameSource.EMAIL_PREFIX
            )

            else -> DisplayNameResult(
                primary = "",
                secondaryEmail = null,
                source = DisplayNameSource.UNKNOWN
            )
        }
    }

    private fun firstRealEmail(vararg candidates: String?): String? =
        candidates
            .mapNotNull { it?.trim() }
            .firstOrNull { it.contains("@") && !isBadDisplayText(it) }

    private fun emailPrefixCandidate(fullEmail: String?): String? {
        return fullEmail?.substringBefore("@")?.takeIf { it.isNotBlank() }
    }

    private fun sanitizeDisplayText(value: String?): String? {
        val trimmed = value?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return trimmed.takeUnless { isBadDisplayText(it) }
    }

    private fun isBadDisplayText(value: String): Boolean {
        val normalized = value.trim().lowercase()
        if (normalized.isBlank()) return true
        if (normalized in BAD_DISPLAY_VALUES) return true
        if (RAW_USER_LABEL.matches(value.trim())) return true
        if (UUID_ONLY.matches(value.trim())) return true
        return false
    }

    private val BAD_DISPLAY_VALUES = setOf(
        "contact",
        "unknown contact",
        "shared by owner",
        "owner",
        "null",
        "null null"
    )

    private val RAW_USER_LABEL = Regex("^User\\s+[0-9a-fA-F]{4,}$")
    private val UUID_ONLY = Regex(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
    )
}
