package io.github.jqssun.gpssetter.xposed

// https://github.com/rovo89/XposedBridge/wiki/Helpers

import android.annotation.SuppressLint
import android.location.Location
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.jqssun.gpssetter.BuildConfig
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.lang.reflect.Method
import java.util.Random
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * KHPAL GPS location injection.
 *
 * Provider rule (brief section 3): the injected Location keeps the provider Android really used
 * (gps / network / fused ...). Priority: the origin Location's provider, then the provider that was
 * requested, then the last real provider seen in this process, and only if nothing is known the
 * neutral "passive" label. It is never forced to GPS.
 */
object LocationHook {

    private const val TAG = "KHPAL"
    private const val EARTH = 6378137.0
    private const val GPS = "gps"
    private const val NETWORK = "network"
    private const val FUSED = "fused"
    private const val PASSIVE = "passive"

    private val settings = Xshare()
    private val rnd = Random()
    private val ignorePkg = arrayListOf("com.android.location.fused", BuildConfig.APPLICATION_ID)

    /** Values that fluctuate (re-rolled every 3 s or when a setting changes). */
    private data class Live(
        val gps: Float = 10f,
        val net: Float = 10f,
        val fused: Float = 10f,
        val alt: Double? = null,
        val speed: Double? = null,
        val dLat: Double = 0.0,
        val dLng: Double = 0.0
    )

    @Volatile private var cfg: MockConfig? = null
    @Volatile private var live = Live()
    @Volatile private var lastRealProvider: String? = null

    private val started = AtomicBoolean(false)
    private var prevCfg: MockConfig? = null
    private var lastRoll = 0L
    private var nextOffsetAt = 0L

    // diagnostics
    @Volatile private var lastLogKey = ""
    @Volatile private var lastLogAt = 0L
    @Volatile private var lastInjected: String? = null
    @Volatile private var mockFlagLogged = false

    private fun log(msg: String) {
        try {
            XposedBridge.log("$TAG: $msg")
        } catch (ignored: Throwable) {
        }
    }

    // ------------------------------------------------------------------ loop

    private fun uniform(range: Double): Double = if (range <= 0.0) 0.0 else (rnd.nextDouble() * 2.0 - 1.0) * range

    private fun rollAcc(base: Double?, range: Double): Float {
        if (base == null) return 10f
        return (base + uniform(range)).coerceAtLeast(0.1).toFloat()
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        val c = settings.snapshot()
        val p = prevCfg
        val neutral = c.copy(lat = 0.0, lng = 0.0, start = false)
        val changed = p == null || p.copy(lat = 0.0, lng = 0.0, start = false) != neutral
        var l = live

        if (changed || now - lastRoll >= 3000L) {
            lastRoll = now
            l = l.copy(
                gps = rollAcc(c.gpsAcc, c.gpsAccRand),
                net = rollAcc(c.netAcc, c.netAccRand),
                fused = rollAcc(c.fusedAcc, c.fusedAccRand),
                alt = c.alt?.let { it + uniform(c.altRand) },
                speed = c.speed?.let { (it + uniform(c.speedRand)).coerceIn(0.0, 500.0) }
            )
        }

        val offsetSettingsChanged = p == null || p.radius != c.radius ||
            p.intervalSec != c.intervalSec || p.intervalRandSec != c.intervalRandSec
        if (c.radius <= 0.0) {
            if (l.dLat != 0.0 || l.dLng != 0.0) l = l.copy(dLat = 0.0, dLng = 0.0)
            nextOffsetAt = 0L
        } else if (offsetSettingsChanged || now >= nextOffsetAt) {
            val dist = c.radius * sqrt(rnd.nextDouble())
            val angle = 2.0 * Math.PI * rnd.nextDouble()
            val cosLat = cos(Math.toRadians(c.lat)).coerceAtLeast(1e-6)
            l = l.copy(
                dLat = Math.toDegrees(dist * sin(angle) / EARTH),
                dLng = Math.toDegrees(dist * cos(angle) / (EARTH * cosLat))
            )
            val base = (c.intervalSec ?: ValueDefaults.INTERVAL_SECONDS).toDouble()
            val next = (base + uniform(c.intervalRandSec.toDouble())).coerceAtLeast(1.0)
            nextOffsetAt = now + (next * 1000.0).toLong()
        }

        prevCfg = c
        live = l
        cfg = c
    }

    private object ValueDefaults {
        const val INTERVAL_SECONDS = 5L
    }

    private fun ensureStarted() {
        if (!started.compareAndSet(false, true)) return
        try {
            tick() // first snapshot right away, on the calling thread
        } catch (t: Throwable) {
            log("first settings read failed: $t")
        }
        try {
            val thread = HandlerThread("khpal-mock")
            thread.start()
            val handler = Handler(thread.looper)
            handler.postDelayed(object : Runnable {
                override fun run() {
                    try {
                        tick()
                    } catch (t: Throwable) {
                        log("tick failed: $t")
                    }
                    handler.postDelayed(this, 1000L)
                }
            }, 1000L)
        } catch (t: Throwable) {
            log("could not start timer: $t")
        }
    }

    private fun active(): Boolean {
        ensureStarted()
        return cfg?.start == true
    }

    private fun systemActive(): Boolean {
        ensureStarted()
        val c = cfg
        return c != null && c.start && c.systemHooked
    }

    // ------------------------------------------------------------------ provider

    private class Resolved(val provider: String, val source: String)

    private fun isReal(p: String?) = !p.isNullOrEmpty() && p != PASSIVE

    private fun resolveProvider(origin: String?, requested: String?): Resolved {
        if (isReal(origin)) {
            lastRealProvider = origin
            return Resolved(origin!!, "origin")
        }
        if (isReal(requested)) return Resolved(requested!!, "requested")
        val last = lastRealProvider
        if (isReal(last)) return Resolved(last!!, "last-real")
        if (!origin.isNullOrEmpty()) return Resolved(origin, "origin-passive")
        if (!requested.isNullOrEmpty()) return Resolved(requested, "requested-passive")
        // nothing known: never claim GPS
        return Resolved(PASSIVE, "fallback-passive")
    }

    private fun logProvider(path: String, received: String?, requested: String?, r: Resolved) {
        val now = SystemClock.elapsedRealtime()
        val key = "$path|$received|$requested|${r.provider}|${r.source}"
        val prev = lastInjected
        if (prev != null && prev != r.provider) log("PROVIDER SWITCH $prev -> ${r.provider}")
        lastInjected = r.provider
        if (key != lastLogKey || now - lastLogAt > 10_000L) {
            lastLogKey = key
            lastLogAt = now
            log("path=$path received=${received ?: "none"} requested=${requested ?: "none"} " +
                "lastReal=${lastRealProvider ?: "none"} injected=${r.provider} (${r.source})")
        }
    }

    private fun accuracyFor(provider: String?, c: MockConfig, l: Live): Float {
        c.hAcc?.let { return it.toFloat().coerceAtLeast(0.1f) }
        val p = if (provider == GPS || provider == NETWORK || provider == FUSED) provider else (lastRealProvider ?: GPS)
        return when (p) {
            NETWORK -> l.net
            FUSED -> l.fused
            else -> l.gps
        }
    }

    // ------------------------------------------------------------------ build

    @SuppressLint("NewApi")
    private fun buildLocation(origin: Location?, requested: String?, path: String): Location? {
        val c = cfg ?: return null
        val l = live
        val originProvider = try { origin?.provider } catch (ignored: Throwable) { null }
        val res = resolveProvider(originProvider, requested)
        logProvider(path, originProvider, requested, res)

        val loc = Location(res.provider)
        if (origin != null) {
            loc.time = origin.time
            loc.bearing = origin.bearing
            loc.bearingAccuracyDegrees = origin.bearingAccuracyDegrees
            loc.elapsedRealtimeNanos = origin.elapsedRealtimeNanos
            loc.verticalAccuracyMeters = origin.verticalAccuracyMeters
        } else {
            loc.time = System.currentTimeMillis() - 300
            loc.elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }

        loc.latitude = c.lat + l.dLat
        loc.longitude = c.lng + l.dLng
        loc.accuracy = accuracyFor(res.provider, c, l)

        loc.altitude = when {
            l.alt != null -> l.alt
            Build.VERSION.SDK_INT < 34 && c.msl != null -> c.msl
            else -> 0.0
        }
        loc.speed = (l.speed ?: 0.0).coerceIn(0.0, 500.0).toFloat()
        loc.speedAccuracyMetersPerSecond = 0f

        if (c.vAcc != null) {
            loc.verticalAccuracyMeters = c.vAcc.toFloat()
        } else if (Build.VERSION.SDK_INT < 34 && c.mslAcc != null) {
            loc.verticalAccuracyMeters = c.mslAcc.toFloat()
        }

        if (Build.VERSION.SDK_INT >= 34) {
            try {
                if (c.msl != null) loc.mslAltitudeMeters = c.msl
                if (c.mslAcc != null) loc.mslAltitudeAccuracyMeters = c.mslAcc.toFloat()
            } catch (t: Throwable) {
                log("MSL not applied: $t")
            }
        }

        try {
            HiddenApiBypass.invoke(loc.javaClass, loc, "setIsFromMockProvider", false)
        } catch (e: Throwable) {
            if (!mockFlagLogged) {
                mockFlagLogged = true
                log("unable to clear mock flag (a new Location is not marked as mock anyway): $e")
            }
        }
        return loc
    }

    // ------------------------------------------------------------------ app-process hooks

    private fun hookGetter(name: String, value: (Location, MockConfig, Live) -> Any?) {
        try {
            XposedBridge.hookAllMethods(Location::class.java, name, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        if (!active()) return
                        val c = cfg ?: return
                        val r = value(param.thisObject as Location, c, live)
                        if (r != null) param.result = r
                    } catch (t: Throwable) {
                        log("getter $name failed: $t")
                    }
                }
            })
        } catch (t: Throwable) {
            log("getter $name not hooked: $t")
        }
    }

    @SuppressLint("NewApi")
    private fun installAppHooks(cl: ClassLoader?, pkg: String) {
        log("hooking app process $pkg")

        hookGetter("getLatitude") { _, c, l -> c.lat + l.dLat }
        hookGetter("getLongitude") { _, c, l -> c.lng + l.dLng }
        hookGetter("getAccuracy") { loc, c, l -> accuracyFor(loc.provider, c, l) }
        hookGetter("getAltitude") { _, c, l -> if (c.alt != null) l.alt else null }
        hookGetter("hasAltitude") { _, c, _ -> if (c.alt != null) true else null }
        hookGetter("getSpeed") { _, c, l -> if (c.speed != null) (l.speed ?: 0.0).toFloat() else null }
        hookGetter("hasSpeed") { _, c, _ -> if (c.speed != null) true else null }
        hookGetter("getVerticalAccuracyMeters") { _, c, _ -> c.vAcc?.toFloat() }
        hookGetter("hasVerticalAccuracy") { _, c, _ -> if (c.vAcc != null) true else null }
        if (Build.VERSION.SDK_INT >= 34) {
            hookGetter("getMslAltitudeMeters") { _, c, _ -> c.msl }
            hookGetter("hasMslAltitude") { _, c, _ -> if (c.msl != null) true else null }
            hookGetter("getMslAltitudeAccuracyMeters") { _, c, _ -> c.mslAcc?.toFloat() }
            hookGetter("hasMslAltitudeAccuracy") { _, c, _ -> if (c.mslAcc != null) true else null }
        }

        // Location.set(Location): same provider as the Location Android delivered
        try {
            XposedHelpers.findAndHookMethod(
                Location::class.java, "set", Location::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            if (!active()) return
                            val built = buildLocation(param.args[0] as Location?, null, "Location.set")
                            if (built != null) param.args[0] = built
                        } catch (t: Throwable) {
                            log("Location.set failed: $t")
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            log("Location.set not hooked: $t")
        }

        // LocationManager.getLastKnownLocation(provider): ask Android first and keep its provider,
        // otherwise use the provider the app asked for
        try {
            XposedHelpers.findAndHookMethod(
                "android.location.LocationManager", cl, "getLastKnownLocation", String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            if (!active()) return
                            val requested = param.args[0] as String?
                            var origin: Location? = null
                            try {
                                origin = XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args) as Location?
                            } catch (ignored: Throwable) {
                            }
                            val built = buildLocation(origin, requested, "getLastKnownLocation")
                            if (built != null) param.result = built
                        } catch (t: Throwable) {
                            log("getLastKnownLocation failed: $t")
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            log("getLastKnownLocation not hooked: $t")
        }
    }

    // ------------------------------------------------------------------ system-server hooks

    private val knownProviders = setOf(GPS, NETWORK, FUSED, PASSIVE)

    private fun requestedProvider(args: Array<Any?>): String? {
        for (a in args) {
            if (a is String && knownProviders.contains(a)) return a
        }
        for (a in args) {
            if (a != null && a.javaClass.name == "android.location.LocationRequest") {
                try {
                    val p = XposedHelpers.callMethod(a, "getProvider") as? String
                    if (!p.isNullOrEmpty()) return p
                } catch (ignored: Throwable) {
                }
            }
        }
        return null
    }

    private fun safeHook(what: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            log("system hook skipped ($what): $t")
        }
    }

    private fun installSystemHooks(cl: ClassLoader) {
        log("hooking system server")
        val names = listOf(
            "com.android.server.location.LocationManagerService",
            "com.android.server.LocationManagerService"
        )
        for (name in names) {
            val sc: Class<*> = try {
                XposedHelpers.findClass(name, cl)
            } catch (t: Throwable) {
                log("class $name not present")
                continue
            }
            log("found $name")

            for (m: Method in sc.declaredMethods) {
                val mName = m.name
                if (mName == "getLastLocation" && m.returnType == Location::class.java) {
                    safeHook("getLastLocation") {
                        XposedBridge.hookMethod(m, object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                try {
                                    if (!systemActive()) return
                                    val built = buildLocation(
                                        param.result as? Location, requestedProvider(param.args), "system.getLastLocation"
                                    )
                                    if (built != null) param.result = built
                                } catch (t: Throwable) {
                                    log("system.getLastLocation failed: $t")
                                }
                            }
                        })
                    }
                } else if (m.returnType == java.lang.Boolean.TYPE &&
                    (mName == "addGnssBatchingCallback" || mName == "addGnssMeasurementsListener" ||
                        mName == "addGnssNavigationMessageListener")
                ) {
                    safeHook(mName) {
                        XposedBridge.hookMethod(m, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (systemActive()) param.result = false
                            }
                        })
                    }
                } else if (m.returnType == Void.TYPE &&
                    (mName == "startGnssBatch" || mName == "addGnssAntennaInfoListener" ||
                        mName == "addGnssMeasurementsListener" || mName == "addGnssNavigationMessageListener")
                ) {
                    safeHook(mName) {
                        XposedBridge.hookMethod(m, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (systemActive()) param.result = null
                            }
                        })
                    }
                }
            }

            // older Android: every listener delivery
            safeHook("Receiver.callLocationChangedLocked") {
                val rc = XposedHelpers.findClass(name + "\$Receiver", cl)
                XposedHelpers.findAndHookMethod(rc, "callLocationChangedLocked", Location::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            try {
                                if (!systemActive()) return
                                val built = buildLocation(param.args[0] as Location?, null, "system.callLocationChangedLocked")
                                if (built != null) param.args[0] = built
                            } catch (t: Throwable) {
                                log("callLocationChangedLocked failed: $t")
                            }
                        }
                    })
            }

            safeHook("injectLocation") {
                XposedHelpers.findAndHookMethod(sc, "injectLocation", Location::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            try {
                                if (!systemActive()) return
                                val built = buildLocation(param.args[0] as Location?, null, "system.injectLocation")
                                if (built != null) param.args[0] = built
                            } catch (t: Throwable) {
                                log("injectLocation failed: $t")
                            }
                        }
                    })
            }
        }
    }

    // ------------------------------------------------------------------ entry

    fun initHooks(lpparam: XC_LoadPackage.LoadPackageParam) {
        val pkg = lpparam.packageName
        if (pkg == "android") {
            // "Hook system location" decides whether system_server is touched at all
            val systemOn = try { settings.snapshot().systemHooked } catch (t: Throwable) { false }
            if (!systemOn) {
                log("Hook system location is OFF: system server not touched")
                return
            }
            ensureStarted()
            installSystemHooks(lpparam.classLoader)
            installAppHooks(lpparam.classLoader, pkg) // as the reference app does
        } else if (!ignorePkg.contains(pkg)) {
            installAppHooks(lpparam.classLoader, pkg)
        }
    }
}
