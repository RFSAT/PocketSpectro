package com.rfsat.pocketspectro

import androidx.annotation.StringRes
import com.rfsat.pocketspectro.core.DeviceCatalog
import com.rfsat.pocketspectro.data.SaveMode
import com.rfsat.pocketspectro.proc.DisplayMode

/*
 * Translatable names for enums. The enums keep their English names for log files and settings;
 * everything shown on screen goes through these resources (res/values/strings.xml).
 */

@StringRes fun DeviceCatalog.Support.labelRes(): Int = when (this) {
    DeviceCatalog.Support.SUPPORTED -> R.string.support_supported
    DeviceCatalog.Support.EXPERIMENTAL -> R.string.support_experimental_long
    DeviceCatalog.Support.EXTERNAL_POWER -> R.string.support_external_power_long
    DeviceCatalog.Support.PLANNED -> R.string.support_planned_long
}

@StringRes fun DeviceCatalog.Support.shortRes(): Int = when (this) {
    DeviceCatalog.Support.SUPPORTED -> R.string.support_supported
    DeviceCatalog.Support.EXPERIMENTAL -> R.string.support_experimental
    DeviceCatalog.Support.EXTERNAL_POWER -> R.string.support_external_power
    DeviceCatalog.Support.PLANNED -> R.string.support_planned
}

@StringRes fun DisplayMode.chipRes(): Int = when (this) {
    DisplayMode.COUNTS -> R.string.mode_counts
    DisplayMode.ABSORBANCE -> R.string.mode_absorbance
    DisplayMode.TRANSMITTANCE -> R.string.mode_transmittance_chip
}

@StringRes fun SaveMode.labelRes(): Int = when (this) {
    SaveMode.SINGLE -> R.string.save_single
    SaveMode.MULTI -> R.string.save_multi
}
