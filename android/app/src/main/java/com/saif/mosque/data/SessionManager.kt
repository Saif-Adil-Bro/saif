package com.saif.mosque.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.saif.mosque.data.model.AppUser
import com.saif.mosque.util.Config
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.sessionDataStore by preferencesDataStore("session")

/**
 * Local session cache: the signed-in user's profile (for instant role-based UI)
 * and the login lockout table (spec 0.4: 5 failures -> 15 min lock).
 * Firebase Auth remains the source of truth for authentication.
 */
class SessionManager(private val context: Context) {

    private val gson = Gson()
    private val KEY_USER = stringPreferencesKey("user_json")
    private val KEY_LOCKS = stringPreferencesKey("login_locks") // json: Map<email, [fails, lockUntil]>

    val userFlow: Flow<AppUser?> = context.sessionDataStore.data.map { prefs ->
        prefs[KEY_USER]?.let { runCatching { gson.fromJson(it, AppUser::class.java) }.getOrNull() }
    }

    suspend fun saveUser(user: AppUser) {
        context.sessionDataStore.edit { it[KEY_USER] = gson.toJson(user) }
    }

    suspend fun clear() {
        context.sessionDataStore.edit { it.remove(KEY_USER) }
    }

    // ---- login lockout (client-side; pair with Cloud Functions/App Check in prod) ----

    private val locksType = object : com.google.gson.reflect.TypeToken<MutableMap<String, List<Double>>>() {}.type

    private suspend fun readLocks(): MutableMap<String, LongArray> {
        val raw = context.sessionDataStore.data.first()[KEY_LOCKS] ?: return mutableMapOf()
        val parsed = runCatching {
            gson.fromJson<MutableMap<String, List<Double>>>(raw, locksType)
        }.getOrNull() ?: return mutableMapOf()
        return parsed.mapValues { (_, v) ->
            val arr = v.map { it.toLong() }.toLongArray()
            if (arr.size >= 2) arr else longArrayOf(arr.getOrElse(0) { 0L }, 0L)
        }.toMutableMap()
    }

    private suspend fun writeLocks(locks: Map<String, LongArray>) {
        context.sessionDataStore.edit { it[KEY_LOCKS] = gson.toJson(locks) }
    }

    /** Returns remaining lock milliseconds if this email is locked, else null. */
    suspend fun lockRemaining(email: String): Long {
        val lock = readLocks()[email.trim().lowercase()] ?: return 0L
        val lockUntil = lock.getOrElse(1) { 0L }
        val remaining = lockUntil - System.currentTimeMillis()
        return if (remaining > 0) remaining else 0L
    }

    /** Record a failed attempt. Returns remaining lock ms (0 if not locked). */
    suspend fun recordFailure(email: String): Long {
        val key = email.trim().lowercase()
        val locks = readLocks()
        val entry = locks[key] ?: longArrayOf(0L, 0L)
        if (entry[1] > 0L && entry[1] <= System.currentTimeMillis()) {
            entry[0] = 0L
            entry[1] = 0L
        }
        entry[0] = entry[0] + 1
        val remaining = if (entry[0] >= Config.MAX_LOGIN_ATTEMPTS) {
            entry[1] = System.currentTimeMillis() + Config.LOCKOUT_MILLIS
            Config.LOCKOUT_MILLIS
        } else 0L
        locks[key] = entry
        writeLocks(locks)
        return remaining
    }

    suspend fun clearLock(email: String) {
        val locks = readLocks()
        locks.remove(email.trim().lowercase())
        writeLocks(locks)
    }

    fun subscriptionFundFlow(mosqueId: String): Flow<String?> {
        val key = stringPreferencesKey("sub_fund_$mosqueId")
        return context.sessionDataStore.data.map { it[key] }
    }

    suspend fun setSubscriptionFund(mosqueId: String, fundId: String) {
        val key = stringPreferencesKey("sub_fund_$mosqueId")
        context.sessionDataStore.edit { it[key] = fundId }
    }
}
