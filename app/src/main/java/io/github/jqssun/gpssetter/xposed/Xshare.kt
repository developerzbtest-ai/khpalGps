package io.github.jqssun.gpssetter.xposed

import de.robv.android.xposed.XSharedPreferences
import io.github.jqssun.gpssetter.BuildConfig
import io.github.jqssun.gpssetter.utils.PrefKeys as K

/** One immutable snapshot of every setting (one file read). null = "not set". */
data class MockConfig(
    val start: Boolean = false,
    val lat: Double = 45.0,
    val lng: Double = 0.0,
    val systemHooked: Boolean = false,
    val gpsAcc: Double? = null,
    val gpsAccRand: Double = 0.0,
    val netAcc: Double? = null,
    val netAccRand: Double = 0.0,
    val fusedAcc: Double? = null,
    val fusedAccRand: Double = 0.0,
    val alt: Double? = null,
    val altRand: Double = 0.0,
    val speed: Double? = null,
    val speedRand: Double = 0.0,
    val hAcc: Double? = null,
    val vAcc: Double? = null,
    val msl: Double? = null,
    val mslAcc: Double? = null,
    val radius: Double = 0.0,
    /** custom interval in seconds for location randomization, null = not set (default 5 s) */
    val intervalSec: Long? = null,
    /** +/- randomization of that interval, in seconds */
    val intervalRandSec: Long = 0L
)

class Xshare {

    private fun pref(): XSharedPreferences =
        XSharedPreferences(BuildConfig.APPLICATION_ID, "${BuildConfig.APPLICATION_ID}_prefs")

    private fun num(p: XSharedPreferences, key: String, min: Double, max: Double): Double? {
        return try {
            val v = p.getString(key, null)?.replace(',', '.')?.trim()?.toDoubleOrNull() ?: return null
            if (v.isNaN() || v.isInfinite()) null else v.coerceIn(min, max)
        } catch (e: Throwable) {
            null
        }
    }

    private fun whole(p: XSharedPreferences, key: String, max: Double): Double? = num(p, key, 0.0, max)

    /** Reads all settings. Limits (brief section 7) are enforced again here in case the file was edited by hand. */
    fun snapshot(): MockConfig {
        val p = pref()
        val speed = num(p, K.SPEED, 0.0, 500.0)
        val speedRandRaw = num(p, K.SPEED_RAND, 0.0, 500.0) ?: 0.0
        val speedRand = if (speed == null) 0.0 else speedRandRaw.coerceAtMost(minOf(speed, 500.0 - speed)).coerceAtLeast(0.0)

        fun total(h: String, m: String, s: String): Long? {
            val hh = whole(p, h, 999.0)
            val mm = whole(p, m, 60.0)
            val ss = whole(p, s, 60.0)
            if (hh == null && mm == null && ss == null) return null
            return ((hh ?: 0.0) * 3600 + (mm ?: 0.0) * 60 + (ss ?: 0.0)).toLong()
        }

        var interval = total(K.INT_H, K.INT_M, K.INT_S)
        if (interval != null && interval < 1L) interval = null
        val intervalRand = if (interval == null) 0L else (total(K.INT_RH, K.INT_RM, K.INT_RS) ?: 0L).coerceAtMost(interval)

        return MockConfig(
            start = p.getBoolean(K.START, false),
            lat = p.getFloat(K.LATITUDE, 45.0000000.toFloat()).toDouble(),
            lng = p.getFloat(K.LONGITUDE, 0.0000000.toFloat()).toDouble(),
            // same default as the settings screen (OFF) so the module and the screen always agree
            systemHooked = p.getBoolean(K.SYSTEM_HOOKED, false),
            gpsAcc = num(p, K.GPS_ACC, 0.0, 100.0),
            gpsAccRand = num(p, K.GPS_ACC_RAND, 0.0, 100.0) ?: 0.0,
            netAcc = num(p, K.NET_ACC, 0.0, 100.0),
            netAccRand = num(p, K.NET_ACC_RAND, 0.0, 100.0) ?: 0.0,
            fusedAcc = num(p, K.FUSED_ACC, 0.0, 100.0),
            fusedAccRand = num(p, K.FUSED_ACC_RAND, 0.0, 100.0) ?: 0.0,
            alt = num(p, K.ALT, -400.0, 5400.0),
            altRand = num(p, K.ALT_RAND, 0.0, 100.0) ?: 0.0,
            speed = speed,
            speedRand = speedRand,
            hAcc = num(p, K.H_ACC, 0.0, 100.0),
            vAcc = num(p, K.V_ACC, 0.0, 100.0),
            msl = num(p, K.MSL, -400.0, 5400.0),
            mslAcc = num(p, K.MSL_ACC, 0.0, 100.0),
            radius = num(p, K.RADIUS, 0.0, 2000.0) ?: 0.0,
            intervalSec = interval,
            intervalRandSec = intervalRand
        )
    }
}
