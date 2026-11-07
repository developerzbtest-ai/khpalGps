package io.github.jqssun.gpssetter.xposed

// https://github.com/rovo89/XposedBridge/wiki/Helpers

import android.annotation.SuppressLint
import android.location.Location
import android.location.LocationManager
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
import kotlin.math.abs
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
    private val logSeen = java.util.concurrent.ConcurrentHashMap<String, Long>()
    @Volatile private var lastSwitchLogAt = 0L
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

        // a jump of more than ~1 km (teleport) re-rolls the jitter with the new latitude; normal
        // movement does not, so the offset interval keeps working
        val jumped = p != null && (abs(p.lat - c.lat) > 0.01 || abs(p.lng - c.lng) > 0.01)
        val offsetSettingsChanged = p == null || p.radius != c.radius ||
            p.intervalSec != c.intervalSec || p.intervalRandSec != c.intervalRandSec || jumped
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

    private fun resolveProvider(origin: String?, requested: String?, originSynthetic: Boolean): Resolved {
        if (isReal(origin)) {
            // a Location that was invented (no real Android result behind it) keeps its label,
            // but must never become the "last real provider"
            if (originSynthetic) return Resolved(origin!!, "origin-synthetic")
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
        val prev = lastInjected
        lastInjected = r.provider
        // at most one PROVIDER SWITCH line every 2 s
        if (prev != null && prev != r.provider && now - lastSwitchLogAt > 2_000L) {
            lastSwitchLogAt = now
            log("PROVIDER SWITCH $prev -> ${r.provider}")
        }
        // the same situation is written at most once every 10 s
        val key = "$path|$received|$requested|${r.provider}|${r.source}"
        val seen = logSeen[key]
        if (seen == null || now - seen > 10_000L) {
            if (logSeen.size > 200) logSeen.clear()
            logSeen[key] = now
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

    /** Latitude kept inside [-90, 90]. */
    private fun outLat(c: MockConfig, l: Live): Double = (c.lat + l.dLat).coerceIn(-90.0, 90.0)

    /** Longitude wrapped into [-180, 180]. */
    private fun outLng(c: MockConfig, l: Live): Double {
        var lng = c.lng + l.dLng
        if (lng > 180.0 || lng < -180.0) lng = ((lng + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
        return lng
    }

    @SuppressLint("NewApi")
    private fun buildLocation(origin: Location?, requested: String?, path: String): Location? {
        val c = cfg ?: return null
        val l = live
        val originProvider = try { origin?.provider } catch (ignored: Throwable) { null }
        val originSynthetic = origin != null && isSynthetic(origin)
        val res = resolveProvider(originProvider, requested, originSynthetic)
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

        loc.latitude = outLat(c, l)
        loc.longitude = outLng(c, l)
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
        // no real Android location behind this one: mark it, so a later copy of it
        // (Location.set) is recognised and never counted as a real provider
        if (origin == null) markSynthetic(loc)
        return loc
    }

    // ------------------------------------------------------------------ ask Android (not the app)

    private val glkMethod: Method? by lazy {
        try {
            LocationManager::class.java.getMethod("getLastKnownLocation", String::class.java)
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * Marker for a Location that the system-server hook had to INVENT because Android itself had no
     * location at all. The app-process hook must not mistake it for "Android's result".
     * Nothing is added to the Location: the last 8 digits of elapsedRealtimeNanos (a shift of at most
     * 100 ms, never looked at by anyone) are set to one of 101 fixed values. A real location matches
     * by chance about 1 time in 1,000,000; the only effect is that this one call falls back to
     * another provider.
     */
    private const val MARK_MOD = 100_000_000L
    private val MARKS = longArrayOf(
        5249378L, 5424816L, 5719367L, 5822392L, 6710262L,
        6788043L, 9387671L, 9840690L, 10050701L, 10967661L,
        11404382L, 11752721L, 12511689L, 13442888L, 14109332L,
        14149031L, 14922164L, 18546975L, 19049064L, 20750162L,
        23108613L, 23976176L, 24289246L, 25483638L, 25507541L,
        25912758L, 28471466L, 28835090L, 28858147L, 29162046L,
        29604342L, 31355668L, 31772866L, 33345490L, 33538259L,
        35121897L, 36021208L, 37048702L, 38479083L, 39817231L,
        41008728L, 41328710L, 42906327L, 45015399L, 45714334L,
        45794132L, 46251995L, 49943690L, 50810316L, 51009621L,
        53492561L, 54290031L, 56458712L, 57429512L, 58473837L,
        58718552L, 61600230L, 61860429L, 63052473L, 63813325L,
        64373382L, 65086792L, 66500222L, 67111661L, 67141722L,
        67233211L, 67249817L, 69393609L, 69942242L, 70977789L,
        71737513L, 72333093L, 72351223L, 72857054L, 73188256L,
        75133030L, 75407387L, 75782151L, 78625202L, 79109102L,
        79225193L, 80174072L, 80747514L, 82422271L, 82678957L,
        83193440L, 83939992L, 83975443L, 84098707L, 84173757L,
        90836242L, 91112648L, 92798052L, 93003143L, 94562678L,
        96419906L, 96590828L, 98345494L, 99220757L, 99863527L,
        99907406L
    )
    private val MARK_SET: Set<Long> = MARKS.toHashSet()

    private fun isSynthetic(loc: Location): Boolean = try {
        MARK_SET.contains(loc.elapsedRealtimeNanos % MARK_MOD)
    } catch (ignored: Throwable) {
        false
    }

    private fun markSynthetic(loc: Location) {
        val t = loc.elapsedRealtimeNanos
        var base = t - (t % MARK_MOD)
        val mark = MARKS[rnd.nextInt(MARKS.size)]
        if (base + mark > t) base -= MARK_MOD // never in the future
        loc.elapsedRealtimeNanos = base + mark
    }

    /**
     * What Android itself really knows for [provider] (calls the original method, not our hook).
     * A Location invented by the system hook (no real Android result behind it) counts as "nothing",
     * so the same provider rules apply whether "Hook system location" is on or off.
     */
    private fun androidLast(lm: Any?, provider: String?): Location? {
        if (provider.isNullOrEmpty() || lm == null) return null
        val m = glkMethod ?: return null
        return try {
            val loc = XposedBridge.invokeOriginalMethod(m, lm, arrayOf<Any?>(provider)) as Location?
            if (loc != null && isSynthetic(loc)) null else loc
        } catch (ignored: Throwable) {
            null
        }
    }

    private fun ageMs(loc: Location): Long =
        (SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos) / 1_000_000L

    private const val STALE_MS = 120_000L

    /** The freshest real location Android has from any provider other than [skip]. */
    private fun bestOther(lm: Any?, skip: String?): Location? {
        var best: Location? = null
        for (p in listOf(NETWORK, PASSIVE, FUSED)) {
            if (p == skip) continue
            val loc = androidLast(lm, p) ?: continue
            if (best == null || loc.elapsedRealtimeNanos > best.elapsedRealtimeNanos) best = loc
        }
        return best
    }

    private fun networkEnabled(lm: Any?): Boolean = try {
        (lm as LocationManager).isProviderEnabled(NETWORK)
    } catch (ignored: Throwable) {
        false
    }

    /** GPS has no fix, or only an old one (for example indoors). */
    private fun gpsDead(lm: Any?): Boolean {
        val loc = androidLast(lm, GPS) ?: return true
        // with "Hook system location" ON, Android may answer a gps request with another provider's
        // location: that is not a working gps
        if (loc.provider != GPS) return true
        return ageMs(loc) > STALE_MS
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

        hookGetter("getLatitude") { _, c, l -> outLat(c, l) }
        hookGetter("getLongitude") { _, c, l -> outLng(c, l) }
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
                            val lm = param.thisObject
                            val requested = param.args[0] as String?
                            var effective = requested
                            // 1. what Android has for the provider the app asked for
                            var origin: Location? = androidLast(lm, requested)
                            // 2. if that is missing or old, take the freshest provider Android really has
                            if (origin == null || ageMs(origin) > STALE_MS) {
                                val other = bestOther(lm, requested)
                                if (other != null && (origin == null || other.elapsedRealtimeNanos > origin.elapsedRealtimeNanos)) {
                                    origin = other
                                }
                            }
                            // 3. nothing at all, and the app asked for gps while gps is not working: do not claim gps
                            if (origin == null && requested == GPS && networkEnabled(lm) && gpsDead(lm)) {
                                val last = lastRealProvider
                                effective = if (isReal(last) && last != GPS) last else NETWORK
                            }
                            val built = buildLocation(origin, effective, "getLastKnownLocation")
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

        // An app that asks for "gps" while gps has no fix would wait for ever. Send that request to the
        // network provider instead, so the provider follows what Android can really deliver.
        for (name in listOf("requestLocationUpdates", "requestSingleUpdate", "getCurrentLocation")) {
            try {
                XposedBridge.hookAllMethods(LocationManager::class.java, name, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            if (!active()) return
                            val first = param.args.getOrNull(0) as? String ?: return
                            if (first != GPS) return
                            val lm = param.thisObject
                            if (networkEnabled(lm) && gpsDead(lm)) {
                                param.args[0] = NETWORK
                                log("REDIRECT $name: app asked gps, gps has no fix -> network")
                            }
                        } catch (t: Throwable) {
                            log("$name redirect failed: $t")
                        }
                    }
                })
            } catch (t: Throwable) {
                log("$name not hooked: $t")
            }
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

    /** Android 11: the provider is inside a LocationRequest, so build the same kind of request as getLastKnownLocation does. */
    private fun requestFor(template: Any, provider: String): Any? = try {
        XposedHelpers.callStaticMethod(template.javaClass, "createFromDeprecatedProvider", provider, 0L, 0f, true)
    } catch (ignored: Throwable) {
        null
    }

    /**
     * System side twin of bestOther(): the freshest REAL location Android has from a provider other
     * than [requested]. The original method is called again with the provider swapped (not our hook,
     * so nothing is mocked here). Works when the provider is a String argument (newer Android) or
     * inside a LocationRequest argument (Android 11). Returns null if neither is found or Android
     * has nothing.
     */
    private fun systemOtherProviders(m: Method, target: Any?, args: Array<Any?>, requested: String?): Location? {
        var idx = -1
        var viaRequest = false
        for (i in args.indices) {
            val a = args[i]
            if (a is String && knownProviders.contains(a)) {
                idx = i
                break
            }
        }
        if (idx < 0) {
            for (i in args.indices) {
                val a = args[i]
                if (a != null && a.javaClass.name == "android.location.LocationRequest") {
                    idx = i
                    viaRequest = true
                    break
                }
            }
        }
        if (idx < 0) return null
        var best: Location? = null
        for (p in listOf(NETWORK, PASSIVE, FUSED)) {
            if (p == requested) continue
            val a = args.copyOf()
            if (viaRequest) {
                a[idx] = requestFor(args[idx]!!, p) ?: continue
            } else {
                a[idx] = p
            }
            var loc: Location? = null
            try {
                loc = XposedBridge.invokeOriginalMethod(m, target, a) as? Location
            } catch (ignored: Throwable) {
            }
            if (loc == null) continue
            if (best == null || loc.elapsedRealtimeNanos > best.elapsedRealtimeNanos) best = loc
        }
        return best
    }

    /**
     * A location reported by a provider, as the system service receives it. Android 11: a Location.
     * Newer Android: a LocationResult (a wrapper holding a list of Locations). Returns the mocked
     * replacement, or null when [a] is neither.
     */
    private fun mockReportedArg(a: Any?, path: String): Any? {
        if (a is Location) return buildLocation(a, null, path)
        if (a != null && a.javaClass.name == "android.location.LocationResult") {
            val list = XposedHelpers.callMethod(a, "asList") as? List<*> ?: return null
            val out = ArrayList<Location>()
            for (e in list) {
                val l = e as? Location ?: continue
                val b = buildLocation(l, null, path) ?: continue
                out.add(b)
            }
            if (out.isEmpty()) return null
            return XposedHelpers.callStaticMethod(a.javaClass, "create", out)
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
                                    val requested = requestedProvider(param.args)
                                    // 1. what Android has for the provider the app asked for
                                    var origin = param.result as? Location
                                    // 2. if that is missing or old, take the freshest provider Android really has
                                    if (origin == null || ageMs(origin) > STALE_MS) {
                                        val other = systemOtherProviders(m, param.thisObject, param.args, requested)
                                        if (other != null && (origin == null || other.elapsedRealtimeNanos > origin.elapsedRealtimeNanos)) {
                                            origin = other
                                        }
                                    }
                                    val built = buildLocation(origin, requested, "system.getLastLocation")
                                    if (built != null) {
                                        // when Android had nothing at all, buildLocation has already marked
                                        // the invented Location, so the app-process hook does not treat it
                                        // as Android's own result
                                        param.result = built
                                    }
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

        // Android 11 and newer: live updates no longer go through Receiver. Every location reported by
        // a provider arrives in LocationProviderManager.onReportLocation (Location on Android 11,
        // LocationResult on newer versions). Each hooked method is written to the log.
        safeHook("LocationProviderManager.onReportLocation") {
            var hooked = 0
            for (cn in listOf(
                "com.android.server.location.LocationProviderManager",
                "com.android.server.location.provider.LocationProviderManager"
            )) {
                val lc: Class<*> = try {
                    XposedHelpers.findClass(cn, cl)
                } catch (t: Throwable) {
                    continue
                }
                for (mm in lc.declaredMethods) {
                    if (mm.name != "onReportLocation") continue
                    try {
                        XposedBridge.hookMethod(mm, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                try {
                                    if (!systemActive()) return
                                    for (i in param.args.indices) {
                                        val r = mockReportedArg(param.args[i], "system.onReportLocation")
                                        if (r != null) {
                                            param.args[i] = r
                                            break
                                        }
                                    }
                                } catch (t: Throwable) {
                                    log("onReportLocation failed: $t")
                                }
                            }
                        })
                        hooked++
                        log("hooked $cn.onReportLocation(" + mm.parameterTypes.joinToString { it.simpleName } + ")")
                    } catch (t: Throwable) {
                        log("onReportLocation not hooked ($cn): $t")
                    }
                }
            }
            if (hooked == 0) log("LocationProviderManager.onReportLocation not found")
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
