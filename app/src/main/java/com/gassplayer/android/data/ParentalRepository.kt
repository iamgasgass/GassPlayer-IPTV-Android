package com.gassplayer.android.data

import android.util.Base64
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.security.MessageDigest

class ParentalRepository(private val prefs: AppPreferences) {
    val flow: Flow<ParentalState> = prefs.parentalFlow
    suspend fun setPin(pin: String) { prefs.saveParental(prefs.parentalFlow.first().copy(enabled = true, pinHash = hash(pin))) }
    suspend fun disable(pin: String): Boolean { val s = prefs.parentalFlow.first(); if (s.pinHash != hash(pin)) return false; prefs.saveParental(s.copy(enabled = false, lockedIds = emptySet())); return true }
    suspend fun verify(pin: String): Boolean = prefs.parentalFlow.first().pinHash == hash(pin)
    suspend fun toggleLock(id: String) { val s = prefs.parentalFlow.first(); val set = s.lockedIds.toMutableSet(); if (!set.add(id)) set.remove(id); prefs.saveParental(s.copy(lockedIds = set)) }
    private fun hash(input: String): String { val d = MessageDigest.getInstance("SHA-256").digest(input.toByteArray()); return Base64.encodeToString(d, Base64.NO_WRAP) }
}
