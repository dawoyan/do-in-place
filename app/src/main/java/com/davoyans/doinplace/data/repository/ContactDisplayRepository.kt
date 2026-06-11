package com.davoyans.doinplace.data.repository

import com.davoyans.doinplace.data.db.AppDatabase
import com.davoyans.doinplace.data.model.ContactDisplayPref
import com.davoyans.doinplace.data.model.TrustedContact

class ContactDisplayRepository(private val db: AppDatabase) {

    fun observeAll(ownerUid: String) = db.contactDisplayPrefDao().observeAll(ownerUid)

    suspend fun save(pref: ContactDisplayPref) = db.contactDisplayPrefDao().upsert(pref)

    suspend fun get(ownerUid: String, contactUserId: String): ContactDisplayPref? =
        db.contactDisplayPrefDao().getById("$ownerUid:$contactUserId")

    suspend fun delete(ownerUid: String, contactUserId: String) =
        db.contactDisplayPrefDao().delete("$ownerUid:$contactUserId")

    companion object {
        fun makeId(ownerUserId: String, contactUserId: String) = "$ownerUserId:$contactUserId"

        fun resolveDisplayName(
            contactUserId: String,
            contact: TrustedContact?,
            pref: ContactDisplayPref?
        ): String = when {
            !pref?.nickname.isNullOrBlank() -> pref!!.nickname
            !contact?.contactDisplayName.isNullOrBlank() -> contact!!.contactDisplayName
            !contact?.contactEmail.isNullOrBlank() -> contact!!.contactEmail
            else -> contactUserId.take(6) + "…"
        }
    }
}
