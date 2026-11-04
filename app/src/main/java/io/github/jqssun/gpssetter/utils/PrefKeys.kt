package io.github.jqssun.gpssetter.utils

/** Preference keys shared by the settings screen and the Xposed side (plain constants only). */
object PrefKeys {
    const val START = "start"
    const val LATITUDE = "latitude"
    const val LONGITUDE = "longitude"
    const val SYSTEM_HOOKED = "system_hooked"

    const val GPS_ACC = "gps_accuracy"
    const val GPS_ACC_RAND = "gps_accuracy_rand"
    const val NET_ACC = "network_accuracy"
    const val NET_ACC_RAND = "network_accuracy_rand"
    const val FUSED_ACC = "fused_accuracy"
    const val FUSED_ACC_RAND = "fused_accuracy_rand"
    const val ALT = "altitude"
    const val ALT_RAND = "altitude_rand"
    const val SPEED = "speed_ms"
    const val SPEED_RAND = "speed_ms_rand"
    const val H_ACC = "horizontal_accuracy"
    const val V_ACC = "vertical_accuracy"
    const val MSL = "msl_altitude"
    const val MSL_ACC = "msl_accuracy"
    const val RADIUS = "random_radius"

    // custom interval (hours / minutes / seconds)
    const val INT_H = "random_interval_hours"
    const val INT_M = "random_interval_minutes"
    const val INT_S = "random_interval_seconds"
    // randomize (+/-) of that interval (hours / minutes / seconds)
    const val INT_RH = "random_interval_rand_hours"
    const val INT_RM = "random_interval_rand_minutes"
    const val INT_RS = "random_interval_rand_seconds"
}
