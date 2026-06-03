package com.example.mindwave.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.ui.graphics.vector.ImageVector
import com.example.mindwave.data.XaiExplanation

/**
 * Groups the 23 raw feature importances into 4 human-readable sensor categories.
 *
 * Feature prefix → group:
 *   hrv_*   → Heart rate variability (indices 0–6)
 *   eda_*   → Sweat response         (indices 7–13)
 *   temp_*  → Skin temperature       (indices 14–18)
 *   acc_*   → Movement               (indices 19–22)
 */
data class XaiGroup(
    val icon: ImageVector,
    val label: String,
    val description: String,
    val share: Float,           // fraction of total importance (0–1)
)

private fun featurePrefix(name: String): String = name.substringBefore("_")

fun groupXaiExplanations(explanations: List<XaiExplanation>): List<XaiGroup> {
    if (explanations.isEmpty()) return emptyList()

    data class GroupDef(val icon: ImageVector, val label: String, val description: String)

    val groupDefs = linkedMapOf(
        "hrv"  to GroupDef(Icons.Filled.Favorite,       "Heart rhythm",     "Heart rate variability"),
        "eda"  to GroupDef(Icons.Filled.WaterDrop,      "Sweat response",   "Electrodermal activity"),
        "temp" to GroupDef(Icons.Filled.Thermostat,     "Skin temperature", "Wrist temperature changes"),
        "acc"  to GroupDef(Icons.Filled.DirectionsRun,  "Movement",         "Wrist acceleration"),
    )

    val sums = mutableMapOf<String, Float>()
    for (xai in explanations) {
        val prefix = featurePrefix(xai.featureName)
        sums[prefix] = (sums[prefix] ?: 0f) + xai.importance
    }

    val total = sums.values.sum().coerceAtLeast(1e-6f)

    return groupDefs.mapNotNull { (prefix, def) ->
        val importance = sums[prefix] ?: 0f
        if (importance == 0f) return@mapNotNull null
        XaiGroup(
            icon = def.icon,
            label = def.label,
            description = def.description,
            share = importance / total,
        )
    }.sortedByDescending { it.share }
}
