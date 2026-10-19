package com.rfsat.pocketspectro.tools

import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Teach-and-predict: a PLS (partial least squares) regression built from your own samples with
 * reference values (sugar content, moisture, dry matter...), then used to predict new samples.
 * This is how most NIR analysers work. Spectra are taken as pseudo-absorbance log10(1/R),
 * resampled to a 2 nm grid, optionally SNV-normalised and differentiated, then mean-centred.
 */
object Chemometrics {

    enum class Pre { NONE, SNV, SNV_D1 }

    data class Sample(val label: String, val value: Double, val x: DoubleArray)

    data class Model(
        val name: String,
        val unit: String,
        val lo: Double,
        val hi: Double,
        val pre: Pre,
        val samples: List<Sample>,
    ) {
        val grid: DoubleArray get() = Spectra.grid(lo, hi, STEP)
    }

    const val STEP = 2.0

    /** Pseudo-absorbance on the model grid from a reflectance (or transmittance) spectrum. */
    fun spectrum(wl: DoubleArray, ratio: DoubleArray, lo: Double, hi: Double): DoubleArray {
        val g = Spectra.grid(lo, hi, STEP)
        return DoubleArray(g.size) {
            val r = Spectra.at(wl, ratio, g[it])
            if (r > 1e-4) log10(1 / r) else 4.0
        }
    }

    fun preprocess(x: DoubleArray, pre: Pre): DoubleArray = when (pre) {
        Pre.NONE -> x.copyOf()
        Pre.SNV -> snv(x)
        Pre.SNV_D1 -> derivative(snv(x))
    }

    fun snv(x: DoubleArray): DoubleArray {
        val m = x.average()
        val sd = sqrt(x.sumOf { (it - m) * (it - m) } / (x.size - 1).coerceAtLeast(1))
        return DoubleArray(x.size) { if (sd > 0) (x[it] - m) / sd else 0.0 }
    }

    /** Savitzky–Golay first derivative, window 11, quadratic (per grid step). */
    fun derivative(x: DoubleArray): DoubleArray {
        val h = 5
        val norm = (1..h).sumOf { 2.0 * it * it }
        return DoubleArray(x.size) { i ->
            var s = 0.0
            for (k in -h..h) s += k * x[(i + k).coerceIn(0, x.size - 1)]
            s / norm
        }
    }

    /** A fitted PLS1 regression: y = intercept + x · coef (x preprocessed). */
    class Pls(val coef: DoubleArray, val intercept: Double, val components: Int) {
        fun predict(x: DoubleArray): Double { var s = intercept; for (i in x.indices) s += x[i] * coef[i]; return s }
    }

    /** NIPALS PLS1 with up to [maxA] components; returns one regression per number of components. */
    fun plsAll(xs: List<DoubleArray>, ys: DoubleArray, maxA: Int): List<Pls> {
        val n = xs.size; val p = xs[0].size
        val xm = DoubleArray(p) { j -> xs.sumOf { it[j] } / n }
        val ym = ys.average()
        val x = Array(n) { i -> DoubleArray(p) { j -> xs[i][j] - xm[j] } }
        val y = DoubleArray(n) { ys[it] - ym }
        val a = minOf(maxA, n - 1, p)
        val w = ArrayList<DoubleArray>(); val pl = ArrayList<DoubleArray>(); val q = ArrayList<Double>()
        for (k in 0 until a) {
            val wk = DoubleArray(p) { j -> var s = 0.0; for (i in 0 until n) s += x[i][j] * y[i]; s }
            val nw = sqrt(wk.sumOf { it * it })
            if (nw < 1e-12) break
            for (j in 0 until p) wk[j] /= nw
            val t = DoubleArray(n) { i -> var s = 0.0; for (j in 0 until p) s += x[i][j] * wk[j]; s }
            val tt = t.sumOf { it * it }
            if (tt < 1e-12) break
            val pk = DoubleArray(p) { j -> var s = 0.0; for (i in 0 until n) s += x[i][j] * t[i]; s / tt }
            val qk = (0 until n).sumOf { y[it] * t[it] } / tt
            for (i in 0 until n) { for (j in 0 until p) x[i][j] -= t[i] * pk[j]; y[i] -= qk * t[i] }
            w += wk; pl += pk; q += qk
        }
        val out = ArrayList<Pls>()
        for (k in 1..w.size) {
            // B = W (PᵀW)⁻¹ q
            val m = Array(k) { r -> DoubleArray(k) { c -> var s = 0.0; for (j in 0 until p) s += pl[r][j] * w[c][j]; s } }
            val z = solve(m, DoubleArray(k) { q[it] }) ?: break
            val b = DoubleArray(p) { j -> var s = 0.0; for (c in 0 until k) s += w[c][j] * z[c]; s }
            var b0 = ym; for (j in 0 until p) b0 -= xm[j] * b[j]
            out += Pls(b, b0, k)
        }
        return out
    }

    private fun solve(a0: Array<DoubleArray>, b0: DoubleArray): DoubleArray? {
        val n = b0.size
        val a = Array(n) { a0[it].copyOf() }; val b = b0.copyOf()
        for (c in 0 until n) {
            var piv = c
            for (r in c + 1 until n) if (abs(a[r][c]) > abs(a[piv][c])) piv = r
            if (abs(a[piv][c]) < 1e-14) return null
            val tr = a[c]; a[c] = a[piv]; a[piv] = tr
            val tb = b[c]; b[c] = b[piv]; b[piv] = tb
            for (r in 0 until n) if (r != c) {
                val f = a[r][c] / a[c][c]
                for (k in c until n) a[r][k] -= f * a[c][k]
                b[r] -= f * b[c]
            }
        }
        return DoubleArray(n) { b[it] / a[it][it] }
    }

    data class Fit(
        val pls: Pls,
        /** Root-mean-square error of leave-one-out cross-validation, in the value's units. */
        val rmsecv: Double,
        /** Cross-validated R² (Q²). */
        val q2: Double,
        val rmsecvByComponents: List<Double>,
        val n: Int,
    )

    /** Builds the model with the number of components chosen by leave-one-out cross-validation. */
    fun build(model: Model, maxA: Int = 10): Fit? {
        val s = model.samples
        if (s.size < 5 || s.map { it.value }.distinct().size < 3) return null
        val xs = s.map { preprocess(it.x, model.pre) }
        val ys = DoubleArray(s.size) { s[it].value }
        val a = minOf(maxA, s.size - 2)
        val press = DoubleArray(a)
        val count = IntArray(a)
        for (leave in s.indices) {
            val tx = xs.filterIndexed { i, _ -> i != leave }
            val ty = ys.filterIndexed { i, _ -> i != leave }.toDoubleArray()
            val fits = plsAll(tx, ty, a)
            for ((k, f) in fits.withIndex()) { val e = f.predict(xs[leave]) - ys[leave]; press[k] += e * e; count[k]++ }
        }
        val rm = (0 until a).filter { count[it] == s.size }.map { sqrt(press[it] / s.size) }
        if (rm.isEmpty()) return null
        val min = rm.min()
        // Fewest components within 3 % of the best (guards against over-fitting).
        val k = rm.indexOfFirst { it <= min * 1.03 }
        val pls = plsAll(xs, ys, k + 1).getOrNull(k) ?: return null
        val ym = ys.average(); val sst = ys.sumOf { (it - ym) * (it - ym) }
        val q2 = if (sst > 0) 1 - press[k] / sst else 0.0
        return Fit(pls, rm[k], q2, rm, s.size)
    }

    fun predict(fit: Fit, model: Model, x: DoubleArray) = fit.pls.predict(preprocess(x, model.pre))

    // ---- storage: files/predict/<name>.csv -------------------------------------------------------

    fun dir(root: File) = File(root, "predict").apply { mkdirs() }

    fun list(root: File): List<String> = dir(root).listFiles { f -> f.name.endsWith(".csv") }?.map { it.name.removeSuffix(".csv") }?.sorted() ?: emptyList()

    private fun file(root: File, name: String) = File(dir(root), safe(name) + ".csv")

    fun safe(name: String) = name.trim().ifEmpty { "Model" }.replace(Regex("[^\\p{L}0-9 _.°%()+-]"), "_").take(40)

    fun save(root: File, m: Model) {
        file(root, m.name).writeText(buildString {
            append("# name=${m.name}\n# unit=${m.unit}\n# lo=${m.lo}\n# hi=${m.hi}\n# pre=${m.pre.name}\n")
            for (s in m.samples) {
                append(s.label.replace(",", " ")).append(',').append(String.format(Locale.US, "%.6g", s.value))
                for (v in s.x) append(',').append(String.format(Locale.US, "%.6g", v))
                append('\n')
            }
        })
    }

    fun load(root: File, name: String): Model? {
        val f = file(root, name)
        if (!f.exists()) return null
        val meta = HashMap<String, String>(); val samples = ArrayList<Sample>()
        f.forEachLine { line ->
            if (line.startsWith("#")) { val kv = line.removePrefix("#").trim().split('=', limit = 2); if (kv.size == 2) meta[kv[0]] = kv[1] }
            else if (line.isNotBlank()) {
                val p = line.split(',')
                val v = p.getOrNull(1)?.toDoubleOrNull() ?: return@forEachLine
                samples += Sample(p[0], v, DoubleArray(p.size - 2) { p[it + 2].toDoubleOrNull() ?: 0.0 })
            }
        }
        val lo = meta["lo"]?.toDoubleOrNull() ?: return null
        val hi = meta["hi"]?.toDoubleOrNull() ?: return null
        val n = Spectra.grid(lo, hi, STEP).size
        return Model(meta["name"] ?: name, meta["unit"] ?: "", lo, hi,
            Pre.entries.firstOrNull { it.name == meta["pre"] } ?: Pre.SNV, samples.filter { it.x.size == n })
    }

    fun delete(root: File, name: String) { file(root, name).delete() }
}
