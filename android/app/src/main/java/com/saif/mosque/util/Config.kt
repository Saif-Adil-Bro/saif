package com.saif.mosque.util

object Config {
    /**
     * Demo mosque document id — protected from deactivation (spec 1.12).
     * Set this to the fixed id of your seeded demo mosque in Firestore.
     */
    const val DEMO_MOSQUE_ID = "demo-mosque-0001"

    /** Starter funds created atomically with every new mosque (spec 1.6–1.7). */
    val STARTER_FUNDS: List<Pair<String, Int>> = listOf(
        "GEN" to com.saif.mosque.R.string.fund_gen,
        "CHD" to com.saif.mosque.R.string.fund_chd,
        "CON" to com.saif.mosque.R.string.fund_con,
        "WAK" to com.saif.mosque.R.string.fund_wak,
        "UTL" to com.saif.mosque.R.string.fund_utl
    )

    /** Demo quick-login (spec 0.5 / 13) — change to your seeded demo accounts. */
    const val DEMO_SUPERADMIN_EMAIL = "superadmin@demo.local"
    const val DEMO_ADMIN_EMAIL = "admin@demo.local"
    const val DEMO_PASSWORD = "demo12345"

    /** Login lockout (spec 0.4): 5 failures -> 15 minute lock. */
    const val MAX_LOGIN_ATTEMPTS = 5
    const val LOCKOUT_MILLIS = 15 * 60 * 1000L
}
