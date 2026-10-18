package com.rfsat.pocketspectro.sim

import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** How the simulator produces fire incidents (K I doublet emission). */
enum class SimFireMode { OFF, MANUAL, RANDOM }

/**
 * Simulated fire settings. [strength] scales the K I lines: 1.0 gives about 27 % above the
 * continuum at 200 ms (a clear detection); around 0.35 sits near the default 10 % threshold.
 */
data class SimFireConfig(
    val mode: SimFireMode = SimFireMode.OFF,
    val meanIntervalSec: Double = 60.0,
    val durationSec: Double = 10.0,
    val strength: Double = 1.0,
)

/** What the simulator is doing right now, for the UI. */
data class SimFireStatus(
    val mode: SimFireMode = SimFireMode.OFF,
    /** An incident (burst or held flame) is emitting. */
    val active: Boolean = false,
    val held: Boolean = false,
    /** Current line strength (envelope x strength), 0 when no flame. */
    val level: Double = 0.0,
    /** End of the current burst, or null. */
    val endsAtMs: Long? = null,
    /** Start of the next random incident, or null outside random mode. */
    val nextAtMs: Long? = null,
    /** Incidents started since the simulator was connected. */
    val incidents: Int = 0,
)

/**
 * Schedules simulated fire incidents. A burst ramps up and down over ~1.5 s (so the detector
 * sees rising, then falling emission and the confirmation logic is exercised), holds at its
 * peak in between, and ends on its own. Random mode starts bursts as a Poisson process with
 * the given mean interval, each with a duration and strength varied by ±50 %.
 * Thread-safe: the device thread reads [level], the UI calls [inject], [stop] and [hold].
 */
class SimFireInjector(seed: Long = 7) {
    private val rnd = java.util.Random(seed)
    private var cfg = SimFireConfig()
    private var burstStart = 0L
    private var burstEnd = 0L
    private var burstPeak = 0.0
    private var nextAt: Long? = null
    private var count = 0
    private var held = false

    @get:Synchronized @set:Synchronized
    var config: SimFireConfig
        get() = cfg
        set(v) {
            if (v.mode != cfg.mode) {
                burstEnd = 0L
                nextAt = null
                if (v.mode == SimFireMode.OFF) held = false
            }
            cfg = v
        }

    /** Flame held on until switched off (manual "hold"). */
    @get:Synchronized @set:Synchronized
    var hold: Boolean
        get() = held
        set(v) { if (v && !held) count++; held = v }

    /** Starts a burst now (manual "inject"). */
    @Synchronized
    fun inject(nowMs: Long, durationSec: Double = cfg.durationSec, strength: Double = cfg.strength) {
        burstStart = nowMs
        burstEnd = nowMs + (durationSec.coerceIn(1.0, 3600.0) * 1000).toLong()
        burstPeak = strength.coerceAtLeast(0.0)
        count++
    }

    /** Ends any burst and the held flame. */
    @Synchronized
    fun stop() { burstEnd = 0L; held = false }

    /** Line strength to add to the spectrum at [nowMs]. */
    @Synchronized
    fun level(nowMs: Long): Double {
        if (cfg.mode == SimFireMode.RANDOM) {
            val at = nextAt ?: scheduleFrom(nowMs)
            if (nowMs >= at && nowMs >= burstEnd) {
                val d = cfg.durationSec * (0.5 + rnd.nextDouble())
                inject(nowMs, d, cfg.strength * (0.5 + rnd.nextDouble()))
                scheduleFrom(burstEnd)
            }
        }
        return max(envelope(nowMs), if (held) cfg.strength else 0.0)
    }

    @Synchronized
    fun status(nowMs: Long): SimFireStatus {
        val burst = nowMs in burstStart until burstEnd
        return SimFireStatus(
            mode = cfg.mode,
            active = burst || held,
            held = held,
            level = max(envelope(nowMs), if (held) cfg.strength else 0.0),
            endsAtMs = if (burst) burstEnd else null,
            nextAtMs = if (cfg.mode == SimFireMode.RANDOM) nextAt else null,
            incidents = count,
        )
    }

    private fun scheduleFrom(t: Long): Long {
        // Exponential gaps (Poisson arrivals), at least 5 s apart.
        val gapSec = max(5.0, -cfg.meanIntervalSec.coerceAtLeast(5.0) * ln(1.0 - rnd.nextDouble()))
        val at = t + (gapSec * 1000).toLong()
        nextAt = at
        return at
    }

    private fun envelope(nowMs: Long): Double {
        if (nowMs < burstStart || nowMs >= burstEnd) return 0.0
        val d = (burstEnd - burstStart).toDouble()
        val t = (nowMs - burstStart).toDouble()
        val ramp = min(1500.0, d / 3)
        val e = when {
            t < ramp -> sin(PI / 2 * t / ramp).let { it * it }
            t > d - ramp -> sin(PI / 2 * (d - t) / ramp).let { it * it }
            else -> 1.0
        }
        return burstPeak * e
    }
}
