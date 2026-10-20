package com.rfsat.pocketspectro.tools

/**
 * What a tool's background job needs from the shared spectrometer, so the running-tools sheet can
 * warn when jobs running together clash: they all see the same spectra, so they must look at the same
 * thing, share the stored reference and agree on who sets the exposure.
 */
object Jobs {
    /** What the probe looks at. */
    enum class View { SCENE, SUN, SKY, EYE, SURFACE, SAMPLE, SOURCE, FINGER }
    /** Stored reference the job divides by (DARK = a dark only). */
    enum class Ref { DARK, WHITE, BLANK }
    /** ANY: works at any exposure; FIXED: the exposure must not change; AUTO: the job changes it. */
    enum class Exposure { ANY, FIXED, AUTO }

    data class Needs(val view: View, val ref: Ref, val exposure: Exposure)

    /** Tools with a job that keeps running in the background, in the order of the sheet. */
    val TOOLS = listOf(ToolId.FIRE, ToolId.EMISSION, ToolId.VEGETATION, ToolId.LAMPS, ToolId.SUN, ToolId.KINETICS, ToolId.GROWTH,
        ToolId.LIGHT_DOSE, ToolId.UV_DOSE, ToolId.GREENHOUSE, ToolId.PULSE)

    /** [autoExposure]: the light loggers change the exposure only with prepared darks and auto-exposure on. */
    fun needs(t: ToolId, autoExposure: Boolean = false): Needs? = when (t) {
        ToolId.FIRE -> Needs(View.SCENE, Ref.DARK, Exposure.FIXED)
        ToolId.EMISSION -> Needs(View.SOURCE, Ref.DARK, Exposure.ANY)
        ToolId.VEGETATION -> Needs(View.SURFACE, Ref.WHITE, Exposure.FIXED)
        ToolId.LAMPS -> Needs(View.SOURCE, Ref.DARK, Exposure.ANY)
        ToolId.SUN -> Needs(View.SUN, Ref.DARK, Exposure.FIXED)
        ToolId.KINETICS -> Needs(View.SAMPLE, Ref.BLANK, Exposure.FIXED)
        ToolId.GROWTH -> Needs(View.SAMPLE, Ref.BLANK, Exposure.FIXED)
        ToolId.LIGHT_DOSE -> Needs(View.EYE, Ref.DARK, if (autoExposure) Exposure.AUTO else Exposure.ANY)
        ToolId.UV_DOSE -> Needs(View.SKY, Ref.DARK, Exposure.ANY)
        ToolId.GREENHOUSE -> Needs(View.SKY, Ref.DARK, if (autoExposure) Exposure.AUTO else Exposure.ANY)
        ToolId.PULSE -> Needs(View.FINGER, Ref.DARK, Exposure.FIXED)
        else -> null
    }

    enum class Clash { VIEW, REFERENCE, EXPOSURE }

    /** Why two jobs cannot share the spectra (empty: they can). */
    fun clash(a: Needs, b: Needs): Set<Clash> {
        val out = HashSet<Clash>()
        if (a.view != b.view) out += Clash.VIEW
        if (a.ref != Ref.DARK && b.ref != Ref.DARK && a.ref != b.ref) out += Clash.REFERENCE
        if ((a.exposure == Exposure.AUTO && b.exposure == Exposure.FIXED) || (b.exposure == Exposure.AUTO && a.exposure == Exposure.FIXED)) out += Clash.EXPOSURE
        return out
    }
}
