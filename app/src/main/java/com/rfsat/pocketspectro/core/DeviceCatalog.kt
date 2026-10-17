package com.rfsat.pocketspectro.core

/**
 * Ocean Optics / Ocean Insight USB spectrometers and how far this app supports them.
 * VID/PID, pixel counts, ADC range and integration limits are taken from SeaBreeze
 * (OOIUSBProductID.h, 2015) and python-seabreeze's device table (pyseabreeze/devices.py).
 *
 * Support depends on two things: the USB protocol family (each needs its own driver here)
 * and whether the phone's OTG port can power the unit. The S24 supplies roughly 0.5 A at
 * 5 V; units with thermo-electric coolers need a powered USB hub or their own supply.
 */
object DeviceCatalog {

    enum class Protocol(val label: String) {
        /** Ocean Binary Protocol (STS, Spark, QE Pro, HDX). Implemented: obp package. */
        OBP("Ocean Binary Protocol"),
        /** Legacy command protocol of the USB2000+/USB4000/Flame/Maya family. */
        OOI("OOI legacy protocol"),
        /** Ocean Binary Protocol 2 of the 2020s Ocean ST / SR / HR series (USB-C). */
        OBP2("Ocean Binary Protocol 2"),
    }

    enum class Support(val label: String) {
        SUPPORTED("Supported"),
        EXPERIMENTAL("Experimental"),
        PLANNED("Driver planned"),
        EXTERNAL_POWER("Planned; needs external power"),
    }

    data class Model(
        val name: String,
        val vendorId: Int,
        val productId: Int,
        val protocol: Protocol,
        val pixels: Int,
        val maxCounts: Int,
        val integrationMinUs: Long,
        val integrationMaxUs: Long,
        val support: Support,
        val note: String,
    ) {
        val usable get() = support == Support.SUPPORTED || support == Support.EXPERIMENTAL
    }

    const val OCEAN_VID = 0x2457
    /** Vendor ID of the OBP2 generation (Ocean ST, SR2/4/6, HR2/4/6). */
    const val OCEAN_VID_OBP2 = 0x0999

    val models: List<Model> = listOf(
        Model("STS (VIS / NIR / UV)", OCEAN_VID, 0x4000, Protocol.OBP, 1024, 16383, 10, 85_000_000,
            Support.SUPPORTED, "Micro-USB, bus powered (0.75 W). Variant named from its wavelength calibration."),
        Model("Spark", OCEAN_VID, 0x4200, Protocol.OBP, 1024, 16383, 10, 85_000_000,
            Support.EXPERIMENTAL, "Same OBP frame and pixel count as the STS; untested on hardware."),
        Model("Ocean HDX", OCEAN_VID, 0x2003, Protocol.OBP, 2068, 65535, 6_000, 10_000_000,
            Support.PLANNED, "OBP, high-speed USB; check OTG power draw."),
        Model("QE Pro", OCEAN_VID, 0x4004, Protocol.OBP, 1044, 262143, 8_000, 1_600_000_000,
            Support.EXTERNAL_POWER, "Cooled detector; 32-bit buffered spectra; needs its 12 V supply."),
        Model("USB2000+ / Flame-S", OCEAN_VID, 0x101E, Protocol.OOI, 2048, 65535, 1_000, 655_350_000,
            Support.PLANNED, "Bus powered. Port of the SeaBreeze OOI driver."),
        Model("USB4000 / Flame-T", OCEAN_VID, 0x1022, Protocol.OOI, 3840, 65535, 10, 65_535_000,
            Support.PLANNED, "Bus powered, high-speed USB."),
        Model("USB650", OCEAN_VID, 0x1014, Protocol.OOI, 2048, 4095, 3_000, 655_350_000, Support.PLANNED, "Bus powered."),
        Model("USB2000", OCEAN_VID, 0x1002, Protocol.OOI, 2048, 4095, 3_000, 655_350_000, Support.PLANNED, "Bus powered."),
        Model("HR2000+", OCEAN_VID, 0x1016, Protocol.OOI, 2048, 16383, 1_000, 655_350_000, Support.PLANNED, "Bus powered."),
        Model("HR4000", OCEAN_VID, 0x1012, Protocol.OOI, 3840, 16383, 10, 655_350_000, Support.PLANNED, "Bus powered."),
        Model("Maya2000 Pro", OCEAN_VID, 0x102A, Protocol.OOI, 2304, 64000, 7_200, 65_000_000, Support.PLANNED, "Check OTG power draw."),
        Model("Torus", OCEAN_VID, 0x1040, Protocol.OOI, 2048, 65535, 1_000, 655_350_000, Support.PLANNED, "Bus powered."),
        Model("Jaz", OCEAN_VID, 0x2000, Protocol.OOI, 2048, 65535, 1_000, 655_350_000, Support.PLANNED, "Own battery; USB data only."),
        Model("NIRQuest512", OCEAN_VID, 0x1026, Protocol.OOI, 512, 65535, 1_000, 1_600_000_000,
            Support.EXTERNAL_POWER, "InGaAs, cooled; needs its own supply."),
        Model("NIRQuest256", OCEAN_VID, 0x1028, Protocol.OOI, 256, 65535, 1_000, 1_600_000_000,
            Support.EXTERNAL_POWER, "InGaAs, cooled; needs its own supply."),
        Model("QE65000", OCEAN_VID, 0x1018, Protocol.OOI, 1280, 65535, 8_000, 1_600_000_000,
            Support.EXTERNAL_POWER, "Cooled detector; needs its own supply."),
        Model("Ocean ST", OCEAN_VID_OBP2, 0x1000, Protocol.OBP2, 1516, 16383, 1_560, 6_000_000,
            Support.PLANNED, "USB-C, bus powered. Successor of the STS (incl. ST-NIR 645–1085 nm)."),
        Model("Ocean SR2", OCEAN_VID_OBP2, 0x1001, Protocol.OBP2, 2110, 65535, 1, 2_000_000, Support.PLANNED, "USB-C."),
        Model("Ocean SR4", OCEAN_VID_OBP2, 0x1002, Protocol.OBP2, 3648, 65535, 3_800, 10_000_000, Support.PLANNED, "USB-C."),
        Model("Ocean SR6", OCEAN_VID_OBP2, 0x1005, Protocol.OBP2, 2048, 65535, 7_200, 5_000_000, Support.PLANNED, "USB-C."),
        Model("Ocean HR2", OCEAN_VID_OBP2, 0x1003, Protocol.OBP2, 2110, 65535, 1, 2_000_000, Support.PLANNED, "USB-C."),
        Model("Ocean HR4", OCEAN_VID_OBP2, 0x1004, Protocol.OBP2, 3648, 65535, 3_800, 10_000_000, Support.PLANNED, "USB-C."),
        Model("Ocean HR6", OCEAN_VID_OBP2, 0x1006, Protocol.OBP2, 2048, 65535, 7_200, 5_000_000, Support.PLANNED, "USB-C."),
    )

    fun find(vendorId: Int, productId: Int): Model? = models.firstOrNull { it.vendorId == vendorId && it.productId == productId }

    fun isOcean(vendorId: Int) = vendorId == OCEAN_VID || vendorId == OCEAN_VID_OBP2

    /** STS variants share one PID; name them from the calibrated range. */
    fun stsVariant(firstNm: Double, lastNm: Double): String = when {
        lastNm <= 700 -> "STS-UV"
        firstNm >= 600 -> "STS-NIR"
        else -> "STS-VIS"
    }
}
