package com.rfsat.pocketspectro.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.Eco
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.BeachAccess
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.Grass
import androidx.compose.material.icons.filled.Water
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Biotech
import androidx.compose.material.icons.filled.Opacity
import androidx.compose.material.icons.filled.LocalBar
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.Yard
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Polymer
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Diamond
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.WbTwilight
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Functions
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.rfsat.pocketspectro.R
import com.rfsat.pocketspectro.core.AcquisitionEngine
import com.rfsat.pocketspectro.core.Connection
import com.rfsat.pocketspectro.sim.SimModel
import com.rfsat.pocketspectro.tools.Availability
import com.rfsat.pocketspectro.tools.ToolId
import com.rfsat.pocketspectro.tools.availability
import java.util.Locale

val ToolId.icon: ImageVector get() = when (this) {
    ToolId.FIRE -> Icons.Filled.LocalFireDepartment
    ToolId.EMISSION -> Icons.Filled.ShowChart
    ToolId.VEGETATION -> Icons.Filled.Eco
    ToolId.MATCH -> Icons.Filled.Fingerprint
    ToolId.CONCENTRATION -> Icons.Filled.WaterDrop
    ToolId.COLOUR -> Icons.Filled.ColorLens
    ToolId.LIGHT -> Icons.Filled.Lightbulb
    ToolId.KINETICS -> Icons.Filled.Timeline
    ToolId.SUN -> Icons.Filled.WbSunny
    ToolId.UV_INDEX -> Icons.Filled.BeachAccess
    ToolId.LAMPS -> Icons.Filled.NightsStay
    ToolId.FLUORESCENCE -> Icons.Filled.Grass
    ToolId.WATER -> Icons.Filled.Water
    ToolId.PREDICT -> Icons.Filled.School
    ToolId.RAMAN -> Icons.Filled.Biotech
    ToolId.OLIVE -> Icons.Filled.Opacity
    ToolId.DRINKS -> Icons.Filled.LocalBar
    ToolId.TEMPERATURE -> Icons.Filled.Thermostat
    ToolId.FILM -> Icons.Filled.Layers
    ToolId.FILTER -> Icons.Filled.Visibility
    ToolId.HEALTH -> Icons.Filled.Build
    ToolId.GAS_COLUMNS -> Icons.Filled.Cloud
    ToolId.LEAF_CLIP -> Icons.Filled.Spa
    ToolId.GREENHOUSE -> Icons.Filled.Yard
    ToolId.WATER_COLOUR -> Icons.Filled.Waves
    ToolId.SOIL -> Icons.Filled.Landscape
    ToolId.TEST_KIT -> Icons.Filled.Science
    ToolId.PH -> Icons.Filled.Colorize
    ToolId.FLUORIMETER -> Icons.Filled.FlashOn
    ToolId.NUCLEIC -> Icons.Filled.Polymer
    ToolId.GROWTH -> Icons.AutoMirrored.Filled.TrendingUp
    ToolId.CRI -> Icons.Filled.Palette
    ToolId.DISPLAY -> Icons.Filled.PhoneAndroid
    ToolId.PIGMENT -> Icons.Filled.Brush
    ToolId.GEM -> Icons.Filled.Diamond
    ToolId.LIGHT_DOSE -> Icons.Filled.Bedtime
    ToolId.UV_DOSE -> Icons.Filled.WbTwilight
    ToolId.SKIN -> Icons.Filled.Face
    ToolId.PULSE -> Icons.Filled.MonitorHeart
    ToolId.DIARY -> Icons.Filled.CalendarMonth
    ToolId.PHYSICS -> Icons.Filled.Functions
}

val ToolId.titleRes: Int get() = when (this) {
    ToolId.FIRE -> R.string.tool_fire
    ToolId.EMISSION -> R.string.tool_emission
    ToolId.VEGETATION -> R.string.tool_vegetation
    ToolId.MATCH -> R.string.tool_match
    ToolId.CONCENTRATION -> R.string.tool_concentration
    ToolId.COLOUR -> R.string.tool_colour
    ToolId.LIGHT -> R.string.tool_light
    ToolId.KINETICS -> R.string.tool_kinetics
    ToolId.SUN -> R.string.tool_sun
    ToolId.UV_INDEX -> R.string.tool_uvi
    ToolId.LAMPS -> R.string.tool_lamps
    ToolId.FLUORESCENCE -> R.string.tool_sif
    ToolId.WATER -> R.string.tool_water
    ToolId.PREDICT -> R.string.tool_predict
    ToolId.RAMAN -> R.string.tool_raman
    ToolId.OLIVE -> R.string.tool_olive
    ToolId.DRINKS -> R.string.tool_drinks
    ToolId.TEMPERATURE -> R.string.tool_temperature
    ToolId.FILM -> R.string.tool_film
    ToolId.FILTER -> R.string.tool_filter
    ToolId.HEALTH -> R.string.tool_health
    ToolId.GAS_COLUMNS -> R.string.tool_gas
    ToolId.LEAF_CLIP -> R.string.tool_leaf
    ToolId.GREENHOUSE -> R.string.tool_greenhouse
    ToolId.WATER_COLOUR -> R.string.tool_watercolour
    ToolId.SOIL -> R.string.tool_soil
    ToolId.TEST_KIT -> R.string.tool_kit
    ToolId.PH -> R.string.tool_ph
    ToolId.FLUORIMETER -> R.string.tool_fluorimeter
    ToolId.NUCLEIC -> R.string.tool_nucleic
    ToolId.GROWTH -> R.string.tool_growth
    ToolId.CRI -> R.string.tool_cri
    ToolId.DISPLAY -> R.string.tool_display
    ToolId.PIGMENT -> R.string.tool_pigment
    ToolId.GEM -> R.string.tool_gem
    ToolId.LIGHT_DOSE -> R.string.tool_lightdose
    ToolId.UV_DOSE -> R.string.tool_uvdose
    ToolId.SKIN -> R.string.tool_skin
    ToolId.PULSE -> R.string.tool_pulse
    ToolId.DIARY -> R.string.tool_diary
    ToolId.PHYSICS -> R.string.tool_physics
}

private val ToolId.summaryRes: Int get() = when (this) {
    ToolId.FIRE -> R.string.tool_fire_summary
    ToolId.EMISSION -> R.string.tool_emission_summary
    ToolId.VEGETATION -> R.string.tool_vegetation_summary
    ToolId.MATCH -> R.string.tool_match_summary
    ToolId.CONCENTRATION -> R.string.tool_concentration_summary
    ToolId.COLOUR -> R.string.tool_colour_summary
    ToolId.LIGHT -> R.string.tool_light_summary
    ToolId.KINETICS -> R.string.tool_kinetics_summary
    ToolId.SUN -> R.string.tool_sun_summary
    ToolId.UV_INDEX -> R.string.tool_uvi_summary
    ToolId.LAMPS -> R.string.tool_lamps_summary
    ToolId.FLUORESCENCE -> R.string.tool_sif_summary
    ToolId.WATER -> R.string.tool_water_summary
    ToolId.PREDICT -> R.string.tool_predict_summary
    ToolId.RAMAN -> R.string.tool_raman_summary
    ToolId.OLIVE -> R.string.tool_olive_summary
    ToolId.DRINKS -> R.string.tool_drinks_summary
    ToolId.TEMPERATURE -> R.string.tool_temperature_summary
    ToolId.FILM -> R.string.tool_film_summary
    ToolId.FILTER -> R.string.tool_filter_summary
    ToolId.HEALTH -> R.string.tool_health_summary
    ToolId.GAS_COLUMNS -> R.string.tool_gas_summary
    ToolId.LEAF_CLIP -> R.string.tool_leaf_summary
    ToolId.GREENHOUSE -> R.string.tool_greenhouse_summary
    ToolId.WATER_COLOUR -> R.string.tool_watercolour_summary
    ToolId.SOIL -> R.string.tool_soil_summary
    ToolId.TEST_KIT -> R.string.tool_kit_summary
    ToolId.PH -> R.string.tool_ph_summary
    ToolId.FLUORIMETER -> R.string.tool_fluorimeter_summary
    ToolId.NUCLEIC -> R.string.tool_nucleic_summary
    ToolId.GROWTH -> R.string.tool_growth_summary
    ToolId.CRI -> R.string.tool_cri_summary
    ToolId.DISPLAY -> R.string.tool_display_summary
    ToolId.PIGMENT -> R.string.tool_pigment_summary
    ToolId.GEM -> R.string.tool_gem_summary
    ToolId.LIGHT_DOSE -> R.string.tool_lightdose_summary
    ToolId.UV_DOSE -> R.string.tool_uvdose_summary
    ToolId.SKIN -> R.string.tool_skin_summary
    ToolId.PULSE -> R.string.tool_pulse_summary
    ToolId.DIARY -> R.string.tool_diary_summary
    ToolId.PHYSICS -> R.string.tool_physics_summary
}

private val ToolId.needsRes: Int get() = when (this) {
    ToolId.FIRE -> R.string.tool_fire_needs
    ToolId.EMISSION -> R.string.tool_emission_needs
    ToolId.VEGETATION -> R.string.tool_vegetation_needs
    ToolId.MATCH -> R.string.tool_match_needs
    ToolId.CONCENTRATION -> R.string.tool_concentration_needs
    ToolId.COLOUR -> R.string.tool_colour_needs
    ToolId.LIGHT -> R.string.tool_light_needs
    ToolId.KINETICS -> R.string.tool_kinetics_needs
    ToolId.SUN -> R.string.tool_sun_needs
    ToolId.UV_INDEX -> R.string.tool_uvi_needs
    ToolId.LAMPS -> R.string.tool_lamps_needs
    ToolId.FLUORESCENCE -> R.string.tool_sif_needs
    ToolId.WATER -> R.string.tool_water_needs
    ToolId.PREDICT -> R.string.tool_predict_needs
    ToolId.RAMAN -> R.string.tool_raman_needs
    ToolId.OLIVE -> R.string.tool_olive_needs
    ToolId.DRINKS -> R.string.tool_drinks_needs
    ToolId.TEMPERATURE -> R.string.tool_temperature_needs
    ToolId.FILM -> R.string.tool_film_needs
    ToolId.FILTER -> R.string.tool_filter_needs
    ToolId.HEALTH -> R.string.tool_health_needs
    ToolId.GAS_COLUMNS -> R.string.tool_gas_needs
    ToolId.LEAF_CLIP -> R.string.tool_leaf_needs
    ToolId.GREENHOUSE -> R.string.tool_greenhouse_needs
    ToolId.WATER_COLOUR -> R.string.tool_watercolour_needs
    ToolId.SOIL -> R.string.tool_soil_needs
    ToolId.TEST_KIT -> R.string.tool_kit_needs
    ToolId.PH -> R.string.tool_ph_needs
    ToolId.FLUORIMETER -> R.string.tool_fluorimeter_needs
    ToolId.NUCLEIC -> R.string.tool_nucleic_needs
    ToolId.GROWTH -> R.string.tool_growth_needs
    ToolId.CRI -> R.string.tool_cri_needs
    ToolId.DISPLAY -> R.string.tool_display_needs
    ToolId.PIGMENT -> R.string.tool_pigment_needs
    ToolId.GEM -> R.string.tool_gem_needs
    ToolId.LIGHT_DOSE -> R.string.tool_lightdose_needs
    ToolId.UV_DOSE -> R.string.tool_uvdose_needs
    ToolId.SKIN -> R.string.tool_skin_needs
    ToolId.PULSE -> R.string.tool_pulse_needs
    ToolId.DIARY -> R.string.tool_diary_needs
    ToolId.PHYSICS -> R.string.tool_physics_needs
}

val com.rfsat.pocketspectro.tools.ToolGroup.titleRes: Int get() = when (this) {
    com.rfsat.pocketspectro.tools.ToolGroup.MONITORING -> R.string.grp_monitoring
    com.rfsat.pocketspectro.tools.ToolGroup.FIELD -> R.string.grp_field
    com.rfsat.pocketspectro.tools.ToolGroup.LAB -> R.string.grp_lab
    com.rfsat.pocketspectro.tools.ToolGroup.LIGHT -> R.string.grp_light
    com.rfsat.pocketspectro.tools.ToolGroup.EXPOSURE -> R.string.grp_exposure
    com.rfsat.pocketspectro.tools.ToolGroup.INSTRUMENT -> R.string.grp_instrument
}

/** Tools tab: the list of tools, or the open tool. */
@Composable
fun ToolsTab(engine: AcquisitionEngine, onReconnect: () -> Unit, initialTool: ToolId? = null) {
    LaunchedEffect(initialTool) { if (initialTool != null) engine.openTool(initialTool) }
    val active by engine.tools.active.collectAsState()
    val tool = active
    BackHandler(enabled = tool != null) { engine.openTool(null) }
    if (tool == null) ToolList(engine, onReconnect) else ToolHost(engine, tool, onReconnect)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ToolList(engine: AcquisitionEngine, onReconnect: () -> Unit) {
    val conn by engine.connection.collectAsState()
    val s by engine.store.settings.collectAsState()
    val info = (conn as? Connection.Ready)?.info
    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.tools_intro), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (info == null) {
                Text(stringResource(R.string.tools_not_connected), style = MaterialTheme.typography.bodySmall)
                ActionButton(onClick = {
                    engine.store.update { it.copy(useSimulator = true) }; onReconnect()
                }, emphasis = true) { Text(stringResource(R.string.tools_try_simulator)) }
            } else if (info.simulated) {
                // Try-before-you-buy: switch the simulated model and see which tools it supports.
                Text(stringResource(R.string.tools_sim_model), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (m in SimModel.entries) ChoiceChip(s.simModel == m, m.label, {
                        engine.store.update { it.copy(simModel = m) }; engine.connectSimulator(m)
                    })
                }
            }
        }
        KitsRow(engine)
        for (t in ToolId.entries) {
            if (t == ToolId.entries.first { it.group == t.group }) Text(stringResource(t.group.titleRes),
                Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 2.dp), style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            val av = t.availability(info)
            val ok = av !is Availability.NeedsRange
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp).clickable { engine.openTool(t) },
                border = BorderStroke(1.5.dp, if (ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)),
                colors = CardDefaults.cardColors(containerColor = if (ok) MaterialTheme.colorScheme.surfaceVariant
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
                shape = RoundedCornerShape(14.dp),
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(t.icon, null, Modifier.size(30.dp), tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(t.titleRes), fontWeight = FontWeight.SemiBold)
                        Text(stringResource(t.summaryRes), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(t.needsRes), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (av is Availability.NeedsRange) Text(stringResource(R.string.tools_needs_range,
                            "%.0f".format(Locale.US, av.lo), "%.0f".format(Locale.US, av.hi)),
                            style = MaterialTheme.typography.labelSmall, color = OffRed, fontWeight = FontWeight.SemiBold)
                        if (av is Availability.Partial) Text(stringResource(R.string.tools_partial_range,
                            "%.0f".format(Locale.US, av.coveredLo), "%.0f".format(Locale.US, av.coveredHi),
                            "%.0f".format(Locale.US, av.lo), "%.0f".format(Locale.US, av.hi)),
                            style = MaterialTheme.typography.labelSmall, color = WarnAmber, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolHost(engine: AcquisitionEngine, tool: ToolId, onReconnect: () -> Unit) {
    val conn by engine.connection.collectAsState()
    val info = (conn as? Connection.Ready)?.info
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            ActionButton(onClick = { engine.openTool(null) }, compact = true) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null, Modifier.size(18.dp))
                Text(" " + stringResource(R.string.tab_tools))
            }
            Spacer(Modifier.width(10.dp))
            Icon(tool.icon, null, tint = MaterialTheme.colorScheme.primary)
            Text(" " + stringResource(tool.titleRes), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
        }
        // One line on what the tool does, with a link to the full guide (kept out of the way for regular users).
        var showGuide by rememberSaveable(tool) { mutableStateOf(false) }
        val summary = stringResource(tool.summaryRes)
        val link = stringResource(R.string.guide_link)
        val linkColor = MaterialTheme.colorScheme.primary
        Text(
            buildAnnotatedString {
                append(summary); append("  ")
                withStyle(SpanStyle(color = linkColor, fontWeight = FontWeight.SemiBold, textDecoration = TextDecoration.Underline)) { append(link) }
            },
            Modifier.fillMaxWidth().clickable { showGuide = true }.padding(horizontal = 14.dp, vertical = 2.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (showGuide) ToolGuideDialog(tool) { showGuide = false }
        val av = tool.availability(info)
        when {
            info == null -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.tools_not_connected))
                ActionButton(onClick = { engine.store.update { it.copy(useSimulator = true) }; onReconnect() }, emphasis = true) {
                    Text(stringResource(R.string.tools_try_simulator))
                }
            }
            av is Availability.NeedsRange -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.tools_unavailable, "%.0f".format(Locale.US, av.lo), "%.0f".format(Locale.US, av.hi),
                    "%.0f".format(Locale.US, info.wavelengths().first()), "%.0f".format(Locale.US, info.wavelengths().last())))
                if (info.simulated) Text(stringResource(R.string.tools_unavailable_sim), style = MaterialTheme.typography.bodySmall)
            }
            else -> when (tool) {
                ToolId.FIRE -> FireScreen(engine)
                ToolId.EMISSION -> EmissionTool(engine)
                ToolId.VEGETATION -> VegetationTool(engine)
                ToolId.MATCH -> MatchTool(engine)
                ToolId.CONCENTRATION -> ConcentrationTool(engine)
                ToolId.COLOUR -> ColourTool(engine)
                ToolId.LIGHT -> LightTool(engine)
                ToolId.KINETICS -> KineticsTool(engine)
                ToolId.SUN -> SunTool(engine)
                ToolId.UV_INDEX -> UvIndexTool(engine)
                ToolId.LAMPS -> LampsTool(engine)
                ToolId.FLUORESCENCE -> FluorescenceTool(engine)
                ToolId.WATER -> WaterTool(engine)
                ToolId.PREDICT -> PredictTool(engine)
                ToolId.RAMAN -> RamanTool(engine)
                ToolId.OLIVE -> OliveTool(engine)
                ToolId.DRINKS -> DrinksTool(engine)
                ToolId.TEMPERATURE -> TemperatureTool(engine)
                ToolId.FILM -> FilmTool(engine)
                ToolId.FILTER -> FilterTool(engine)
                ToolId.HEALTH -> HealthTool(engine)
                ToolId.GAS_COLUMNS -> GasColumnsTool(engine)
                ToolId.LEAF_CLIP -> LeafClipTool(engine)
                ToolId.GREENHOUSE -> GreenhouseTool(engine)
                ToolId.WATER_COLOUR -> WaterColourTool(engine)
                ToolId.SOIL -> SoilTool(engine)
                ToolId.TEST_KIT -> TestKitTool(engine)
                ToolId.PH -> PhTool(engine)
                ToolId.FLUORIMETER -> FluorimeterTool(engine)
                ToolId.NUCLEIC -> NucleicTool(engine)
                ToolId.GROWTH -> GrowthTool(engine)
                ToolId.CRI -> CriTool(engine)
                ToolId.DISPLAY -> DisplayTool(engine)
                ToolId.PIGMENT -> PigmentTool(engine)
                ToolId.GEM -> GemTool(engine)
                ToolId.LIGHT_DOSE -> WellnessGate { LightDoseTool(engine) }
                ToolId.UV_DOSE -> WellnessGate { UvDoseTool(engine) }
                ToolId.SKIN -> WellnessGate { SkinTool(engine) }
                ToolId.PULSE -> WellnessGate { PulseTool(engine) }
                ToolId.DIARY -> WellnessGate { DiaryTool(engine) }
                ToolId.PHYSICS -> PhysicsTool(engine)
            }
        }
    }
}

private val ToolId.guideRes: Triple<Int, Int, Int> get() = when (this) {
    ToolId.FIRE -> Triple(R.string.guide_fire_what, R.string.guide_fire_steps, R.string.guide_fire_tips)
    ToolId.EMISSION -> Triple(R.string.guide_emission_what, R.string.guide_emission_steps, R.string.guide_emission_tips)
    ToolId.VEGETATION -> Triple(R.string.guide_vegetation_what, R.string.guide_vegetation_steps, R.string.guide_vegetation_tips)
    ToolId.MATCH -> Triple(R.string.guide_match_what, R.string.guide_match_steps, R.string.guide_match_tips)
    ToolId.CONCENTRATION -> Triple(R.string.guide_concentration_what, R.string.guide_concentration_steps, R.string.guide_concentration_tips)
    ToolId.COLOUR -> Triple(R.string.guide_colour_what, R.string.guide_colour_steps, R.string.guide_colour_tips)
    ToolId.LIGHT -> Triple(R.string.guide_light_what, R.string.guide_light_steps, R.string.guide_light_tips)
    ToolId.KINETICS -> Triple(R.string.guide_kinetics_what, R.string.guide_kinetics_steps, R.string.guide_kinetics_tips)
    ToolId.SUN -> Triple(R.string.guide_sun_what, R.string.guide_sun_steps, R.string.guide_sun_tips)
    ToolId.UV_INDEX -> Triple(R.string.guide_uvi_what, R.string.guide_uvi_steps, R.string.guide_uvi_tips)
    ToolId.LAMPS -> Triple(R.string.guide_lamps_what, R.string.guide_lamps_steps, R.string.guide_lamps_tips)
    ToolId.FLUORESCENCE -> Triple(R.string.guide_sif_what, R.string.guide_sif_steps, R.string.guide_sif_tips)
    ToolId.WATER -> Triple(R.string.guide_water_what, R.string.guide_water_steps, R.string.guide_water_tips)
    ToolId.PREDICT -> Triple(R.string.guide_predict_what, R.string.guide_predict_steps, R.string.guide_predict_tips)
    ToolId.RAMAN -> Triple(R.string.guide_raman_what, R.string.guide_raman_steps, R.string.guide_raman_tips)
    ToolId.OLIVE -> Triple(R.string.guide_olive_what, R.string.guide_olive_steps, R.string.guide_olive_tips)
    ToolId.DRINKS -> Triple(R.string.guide_drinks_what, R.string.guide_drinks_steps, R.string.guide_drinks_tips)
    ToolId.TEMPERATURE -> Triple(R.string.guide_temperature_what, R.string.guide_temperature_steps, R.string.guide_temperature_tips)
    ToolId.FILM -> Triple(R.string.guide_film_what, R.string.guide_film_steps, R.string.guide_film_tips)
    ToolId.FILTER -> Triple(R.string.guide_filter_what, R.string.guide_filter_steps, R.string.guide_filter_tips)
    ToolId.HEALTH -> Triple(R.string.guide_health_what, R.string.guide_health_steps, R.string.guide_health_tips)
    ToolId.GAS_COLUMNS -> Triple(R.string.guide_gas_what, R.string.guide_gas_steps, R.string.guide_gas_tips)
    ToolId.LEAF_CLIP -> Triple(R.string.guide_leaf_what, R.string.guide_leaf_steps, R.string.guide_leaf_tips)
    ToolId.GREENHOUSE -> Triple(R.string.guide_greenhouse_what, R.string.guide_greenhouse_steps, R.string.guide_greenhouse_tips)
    ToolId.WATER_COLOUR -> Triple(R.string.guide_watercolour_what, R.string.guide_watercolour_steps, R.string.guide_watercolour_tips)
    ToolId.SOIL -> Triple(R.string.guide_soil_what, R.string.guide_soil_steps, R.string.guide_soil_tips)
    ToolId.TEST_KIT -> Triple(R.string.guide_kit_what, R.string.guide_kit_steps, R.string.guide_kit_tips)
    ToolId.PH -> Triple(R.string.guide_ph_what, R.string.guide_ph_steps, R.string.guide_ph_tips)
    ToolId.FLUORIMETER -> Triple(R.string.guide_fluorimeter_what, R.string.guide_fluorimeter_steps, R.string.guide_fluorimeter_tips)
    ToolId.NUCLEIC -> Triple(R.string.guide_nucleic_what, R.string.guide_nucleic_steps, R.string.guide_nucleic_tips)
    ToolId.GROWTH -> Triple(R.string.guide_growth_what, R.string.guide_growth_steps, R.string.guide_growth_tips)
    ToolId.CRI -> Triple(R.string.guide_cri_what, R.string.guide_cri_steps, R.string.guide_cri_tips)
    ToolId.DISPLAY -> Triple(R.string.guide_display_what, R.string.guide_display_steps, R.string.guide_display_tips)
    ToolId.PIGMENT -> Triple(R.string.guide_pigment_what, R.string.guide_pigment_steps, R.string.guide_pigment_tips)
    ToolId.GEM -> Triple(R.string.guide_gem_what, R.string.guide_gem_steps, R.string.guide_gem_tips)
    ToolId.LIGHT_DOSE -> Triple(R.string.guide_lightdose_what, R.string.guide_lightdose_steps, R.string.guide_lightdose_tips)
    ToolId.UV_DOSE -> Triple(R.string.guide_uvdose_what, R.string.guide_uvdose_steps, R.string.guide_uvdose_tips)
    ToolId.SKIN -> Triple(R.string.guide_skin_what, R.string.guide_skin_steps, R.string.guide_skin_tips)
    ToolId.PULSE -> Triple(R.string.guide_pulse_what, R.string.guide_pulse_steps, R.string.guide_pulse_tips)
    ToolId.DIARY -> Triple(R.string.guide_diary_what, R.string.guide_diary_steps, R.string.guide_diary_tips)
    ToolId.PHYSICS -> Triple(R.string.guide_physics_what, R.string.guide_physics_steps, R.string.guide_physics_tips)
}

/** Full guide for a tool: what it does, what it needs, the steps, and tips. */
@Composable
private fun ToolGuideDialog(tool: ToolId, onClose: () -> Unit) {
    val (what, steps, tips) = tool.guideRes
    AlertDialog(
        onDismissRequest = onClose,
        icon = { Icon(tool.icon, null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text(stringResource(tool.titleRes)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(what), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(tool.needsRes), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.guide_how), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text(stringResource(steps), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.guide_tips), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text(stringResource(tips), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { ActionButton(onClick = onClose, emphasis = true) { Text(stringResource(R.string.action_close)) } },
    )
}
