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

    enum class Support(val label: String, val short: String) {
        SUPPORTED("Supported", "Supported"),
        EXPERIMENTAL("Experimental (driver ported, not yet tested on hardware)", "Experimental"),
        EXTERNAL_POWER("Experimental; needs its own power supply or a powered USB hub", "Needs external power"),
        PLANNED("Driver planned", "Planned"),
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
        /** Unit of the integration-time argument on the wire (1 us; 10 us on the Ocean ST; 1 ms on old models). */
        val integrationBaseUs: Long = 1,
        val ooi: OoiSpec? = null,
    ) {
        val usable get() = support != Support.PLANNED
        /** OOI frame length: 2 bytes per pixel plus the 0x69 sync byte. */
        val rawLength get() = pixels * 2 + if (ooi?.syncByte != false) 1 else 0
    }

    const val OCEAN_VID = 0x2457
    /** Vendor ID of the OBP2 generation (Ocean ST, SR2/4/6, HR2/4/6). */
    const val OCEAN_VID_OBP2 = 0x0999

    private val FPGA_EPS = OoiSpec(OoiReadout.FPGA, epOut = 0x01, epLowIn = 0x81, epHighIn = 0x82, epHighIn2 = 0x86)
    private val OLD_EPS = OoiSpec(OoiReadout.OOI_2K, epOut = 0x02, epLowIn = 0x87, epHighIn = 0x82)

    val models: List<Model> = listOf(
        // Ocean Binary Protocol
        Model("STS (VIS / NIR / UV)", OCEAN_VID, 0x4000, Protocol.OBP, 1024, 16383, 10, 85_000_000,
            Support.SUPPORTED, "Micro-USB, bus powered (0.75 W). Variant named from its wavelength calibration."),
        Model("Spark", OCEAN_VID, 0x4200, Protocol.OBP, 1024, 16383, 10, 85_000_000,
            Support.EXPERIMENTAL, "Same OBP frame and pixel count as the STS."),
        Model("Ocean HDX", OCEAN_VID, 0x2003, Protocol.OBP, 2068, 65535, 6_000, 10_000_000,
            Support.EXPERIMENTAL, "OBP, same spectrum request as the STS; check OTG power draw."),
        Model("QE Pro", OCEAN_VID, 0x4004, Protocol.OBP, 1044, 262143, 8_000, 1_600_000_000,
            Support.PLANNED, "Cooled detector, 32-bit buffered spectra; needs its 12 V supply."),
        // Classic OOI protocol
        Model("USB2000+ / Flame-S", OCEAN_VID, 0x101E, Protocol.OOI, 2048, 65535, 1_000, 655_350_000,
            Support.EXPERIMENTAL, "Bus powered.", ooi = FPGA_EPS.copy(gain = OoiGain.U16_AT_6)),
        Model("USB4000 / Flame-T", OCEAN_VID, 0x1022, Protocol.OOI, 3840, 65535, 10, 65_535_000,
            Support.EXPERIMENTAL, "Bus powered.", ooi = FPGA_EPS.copy(readout = OoiReadout.FPGA_4K, gain = OoiGain.U16_AT_6)),
        Model("USB650", OCEAN_VID, 0x1014, Protocol.OOI, 2048, 4095, 3_000, 655_350_000,
            Support.EXPERIMENTAL, "Bus powered.", integrationBaseUs = 1000, ooi = OLD_EPS),
        Model("USB2000", OCEAN_VID, 0x1002, Protocol.OOI, 2048, 4095, 3_000, 655_350_000,
            Support.EXPERIMENTAL, "Bus powered.", integrationBaseUs = 1000, ooi = OLD_EPS),
        Model("HR2000+", OCEAN_VID, 0x1016, Protocol.OOI, 2048, 16383, 1_000, 655_350_000,
            Support.EXPERIMENTAL, "Bus powered.", ooi = FPGA_EPS),
        Model("HR4000", OCEAN_VID, 0x1012, Protocol.OOI, 3840, 16383, 10, 655_350_000,
            Support.EXPERIMENTAL, "Bus powered.", ooi = FPGA_EPS.copy(readout = OoiReadout.FPGA_4K)),
        Model("Maya2000 Pro", OCEAN_VID, 0x102A, Protocol.OOI, 2304, 64000, 7_200, 65_000_000,
            Support.EXPERIMENTAL, "Check OTG power draw.", ooi = FPGA_EPS.copy(gain = OoiGain.U16_AT_2_ALT)),
        Model("Torus", OCEAN_VID, 0x1040, Protocol.OOI, 2048, 65535, 1_000, 655_350_000,
            Support.EXPERIMENTAL, "Bus powered.", ooi = FPGA_EPS.copy(gain = OoiGain.U16_AT_6)),
        Model("Jaz", OCEAN_VID, 0x2000, Protocol.OOI, 2048, 65535, 1_000, 655_350_000,
            Support.EXPERIMENTAL, "Own battery; USB data only.",
            ooi = OoiSpec(OoiReadout.PLAIN, epOut = 0x01, epLowIn = 0x81, epHighIn = 0x82, syncByte = false, gain = OoiGain.U16_AT_6)),
        Model("NIRQuest512", OCEAN_VID, 0x1026, Protocol.OOI, 512, 65535, 1_000, 1_600_000_000,
            Support.EXTERNAL_POWER, "InGaAs, cooled.", integrationBaseUs = 1000,
            ooi = FPGA_EPS.copy(readout = OoiReadout.PLAIN, gain = OoiGain.U32_AT_6)),
        Model("NIRQuest256", OCEAN_VID, 0x1028, Protocol.OOI, 256, 65535, 1_000, 1_600_000_000,
            Support.EXTERNAL_POWER, "InGaAs, cooled.", integrationBaseUs = 1000,
            ooi = FPGA_EPS.copy(readout = OoiReadout.PLAIN, gain = OoiGain.U32_AT_6)),
        Model("QE65000", OCEAN_VID, 0x1018, Protocol.OOI, 1280, 65535, 8_000, 1_600_000_000,
            Support.PLANNED, "Cooled detector with offset wavelength indexing; needs its own supply."),
        // Ocean Binary Protocol 2 (USB-C generation)
        Model("Ocean ST", OCEAN_VID_OBP2, 0x1000, Protocol.OBP2, 1516, 16383, 1_560, 6_000_000,
            Support.EXPERIMENTAL, "USB-C, bus powered. Successor of the STS (incl. ST-NIR 645–1085 nm).", integrationBaseUs = 10),
        Model("Ocean SR2", OCEAN_VID_OBP2, 0x1001, Protocol.OBP2, 2110, 65535, 1, 2_000_000, Support.EXPERIMENTAL, "USB-C."),
        Model("Ocean SR4", OCEAN_VID_OBP2, 0x1002, Protocol.OBP2, 3648, 65535, 3_800, 10_000_000, Support.EXPERIMENTAL, "USB-C."),
        Model("Ocean SR6", OCEAN_VID_OBP2, 0x1005, Protocol.OBP2, 2048, 65535, 7_200, 5_000_000, Support.EXPERIMENTAL, "USB-C."),
        Model("Ocean HR2", OCEAN_VID_OBP2, 0x1003, Protocol.OBP2, 2110, 65535, 1, 2_000_000, Support.EXPERIMENTAL, "USB-C."),
        Model("Ocean HR4", OCEAN_VID_OBP2, 0x1004, Protocol.OBP2, 3648, 65535, 3_800, 10_000_000, Support.EXPERIMENTAL, "USB-C."),
        Model("Ocean HR6", OCEAN_VID_OBP2, 0x1006, Protocol.OBP2, 2048, 65535, 7_200, 5_000_000, Support.EXPERIMENTAL, "USB-C."),
    )

    /**
     * Fire detection is offered only on near-infrared units: a calibrated range that starts in the
     * red/NIR (>= 600 nm, like the STS-NIR and ST-NIR) and covers the K I doublet with margin.
     */
    fun coversPotassium(info: DeviceInfo): Boolean {
        if (!com.rfsat.pocketspectro.proc.Calibration.isPlausible(info.wavelengthCoefficients, info.unbinnedPixels)) return false
        val w = info.wavelengths()
        return w.first() >= 600.0 && w.first() <= 755.0 && w.last() >= 785.0
    }

    fun find(vendorId: Int, productId: Int): Model? = models.firstOrNull { it.vendorId == vendorId && it.productId == productId }

    fun isOcean(vendorId: Int) = vendorId == OCEAN_VID || vendorId == OCEAN_VID_OBP2

    /** STS variants share one PID; name them from the calibrated range. */
    fun stsVariant(firstNm: Double, lastNm: Double): String = when {
        lastNm <= 700 -> "STS-UV"
        firstNm >= 600 -> "STS-NIR"
        else -> "STS-VIS"
    }
}
