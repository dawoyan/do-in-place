package com.davoyans.doinplace.data.repository

import com.davoyans.doinplace.data.db.AppDatabase
import com.davoyans.doinplace.data.model.ContactStatus
import com.davoyans.doinplace.data.model.TrustedContact

class ContactRepository(private val db: AppDatabase) {
    fun observeAll(uid: String) = db.trustedContactDao().observeAll(uid)

    suspend fun getAccepted(uid: String) = db.trustedContactDao().getAccepted(uid)

    suspend fun save(contact: TrustedContact) = db.trustedContactDao().upsert(contact)

    suspend fun updateStatus(id: String, status: ContactStatus) =
        db.trustedContactDao().updateStatus(id, status.name)

    suspend fun deleteById(id: String) =
        db.trustedContactDao().deleteById(id)

    suspend fun deleteByContactUserId(uid: String, contactUserId: String) =
        db.trustedContactDao().deleteByContactUserId(uid, contactUserId)

    suspend fun deleteSelfContacts(uid: String) =
        db.trustedContactDao().deleteSelfContacts(uid)

    suspend fun deleteSelfContactsByEmail(uid: String, email: String) =
        db.trustedContactDao().deleteSelfContactsByEmail(uid, email)
}
