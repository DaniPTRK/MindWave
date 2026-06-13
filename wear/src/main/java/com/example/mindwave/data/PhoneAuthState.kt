package com.example.mindwave.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Stores the phone's auth state received via the Data Layer.
 * The SensorForegroundService checks this before sending data to verify
 * the phone is still logged in.
 */
object PhoneAuthState {

    private const val PREFS_NAME = "phone_auth_state"
    private const val KEY_IS_LOGGED_IN = "is_logged_in"
    private const val KEY_USER_EMAIL = "user_email"
    private const val KEY_LAST_UPDATE = "last_update"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Returns true if the phone is logged in with a real account.
     * Defaults to true if we haven't received an update yet.
     */
    fun isPhoneLoggedIn(context: Context): Boolean =
        prefs(context).getBoolean(KEY_IS_LOGGED_IN, true)

    fun getPhoneUserEmail(context: Context): String =
        prefs(context).getString(KEY_USER_EMAIL, "") ?: ""

    fun getLastUpdateTime(context: Context): Long =
        prefs(context).getLong(KEY_LAST_UPDATE, 0L)

    /**
     * Called by PhoneAuthListenerService when a new auth state is received
     * from the phone.
     */
    fun update(context: Context, isLoggedIn: Boolean, userEmail: String) {
        prefs(context).edit()
            .putBoolean(KEY_IS_LOGGED_IN, isLoggedIn)
            .putString(KEY_USER_EMAIL, userEmail)
            .putLong(KEY_LAST_UPDATE, System.currentTimeMillis())
            .apply()
    }
}

