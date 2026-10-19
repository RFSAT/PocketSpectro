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
        for (t in ToolId.entries) {
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
