package com.rfsat.pocketspectro.tools

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan

/**
 * Sun position and clear-sky atmosphere for the Sun photometer and UV index tools.
 * Position: low-precision solar ephemeris (Astronomical Almanac), good to about 0.01° in zenith
 * between 1950 and 2050. Airmass: Kasten & Young (1989). Rayleigh optical depth: Hansen & Travis
 * (1974) formula. Ozone: simple fits of the Huggins (UV) and Chappuis (visible) bands.
 */
object Solar {

    data class Position(val zenithDeg: Double, val azimuthDeg: Double) {
        val elevationDeg get() = 90.0 - zenithDeg
    }

    fun position(latDeg: Double, lonDeg: Double, timeMs: Long): Position {
        val n = timeMs / 86_400_000.0 + 2440587.5 - 2451545.0
        val l = mod360(280.460 + 0.9856474 * n)
        val g = Math.toRadians(mod360(357.528 + 0.9856003 * n))
        val lambda = Math.toRadians(l + 1.915 * sin(g) + 0.020 * sin(2 * g))
        val eps = Math.toRadians(23.439 - 0.0000004 * n)
        val ra = atan2(cos(eps) * sin(lambda), cos(lambda))
        val dec = asin(sin(eps) * sin(lambda))
        val gmstHours = (18.697374558 + 24.06570982441908 * n).mod(24.0)
        val ha = Math.toRadians(gmstHours * 15.0 + lonDeg) - ra
        val lat = Math.toRadians(latDeg)
        val cosZ = (sin(lat) * sin(dec) + cos(lat) * cos(dec) * cos(ha)).coerceIn(-1.0, 1.0)
        val az = atan2(-sin(ha), tan(dec) * cos(lat) - sin(lat) * cos(ha))
        return Position(Math.toDegrees(acos(cosZ)), mod360(Math.toDegrees(az)))
    }

    /** (mean Sun distance / actual distance)², about ±3.3 % over the year. */
    fun distanceFactor(timeMs: Long): Double {
        val doy = ((timeMs / 86_400_000.0) % 365.25)
        return 1.0 + 0.033 * cos(2 * PI * (doy - 2.0) / 365.25)
    }

    /** Relative optical airmass (Kasten & Young 1989); infinite below the horizon. */
    fun airmass(zenithDeg: Double): Double =
        if (zenithDeg >= 90.0) Double.POSITIVE_INFINITY
        else 1.0 / (cos(Math.toRadians(zenithDeg)) + 0.50572 * (96.07995 - zenithDeg).pow(-1.6364))

    /** Rayleigh optical depth at [nm] for station pressure [hPa]. */
    fun rayleighTau(nm: Double, hPa: Double = 1013.25): Double {
        val u = nm / 1000.0
        return 0.008569 * u.pow(-4) * (1 + 0.0113 * u.pow(-2) + 0.00013 * u.pow(-4)) * hPa / 1013.25
    }

    /** Ozone optical depth at [nm] for a column of [du] Dobson units. */
    fun ozoneTau(nm: Double, du: Double = 300.0): Double {
        val huggins = if (nm < 360.0) 2.9 * 10.0.pow(-0.054 * (nm - 300.0)) else 0.0
        val chappuis = 0.0435 * exp(-((nm - 602.0) / 75.0).pow(2))
        return du / 300.0 * (huggins + chappuis)
    }

    /** Pressure for a height above sea level (standard atmosphere), hPa. */
    fun pressureAt(heightM: Double) = 1013.25 * (1 - 2.25577e-5 * heightM).pow(5.25588)

    /** UTC time of the given local solar hour on the day of [timeMs] at longitude [lonDeg]. */
    fun solarHourToUtcMs(timeMs: Long, lonDeg: Double, hour: Double): Long {
        val day = Math.floorDiv(timeMs, 86_400_000L) * 86_400_000L
        return day + ((hour - lonDeg / 15.0) * 3_600_000.0).toLong()
    }

    private fun mod360(v: Double) = v.mod(360.0)
}
