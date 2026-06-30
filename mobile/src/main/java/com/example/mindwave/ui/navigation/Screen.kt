package com.example.mindwave.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Today
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Navigation routes for the MindWave app.
 *
 * Bottom nav: Today - Insights - History - Journal - Profile
 * Secondary:  Breathing, StressDetail, SensorStatus
 */
sealed class Screen(val route: String) {
    // auth
    object Login    : Screen("login")
    object Register : Screen("register")

    // bottom nav (5 tabs)
    object Today    : Screen("today")
    object Insights : Screen("insights")
    object History  : Screen("history")
    object Journal  : Screen("journal")
    object Profile  : Screen("profile")

    // secondary screens
    object Breathing     : Screen("breathing")
    object SensorStatus  : Screen("sensor_status")
    object Benchmark     : Screen("benchmark")
    object StressDetail  : Screen("stress_detail/{readingId}") {
        const val ARG_READING_ID = "readingId"
        fun createRoute(readingId: Long) = "stress_detail/$readingId"
    }
}

/**
 * Bottom-navigation tab definitions.
 */
enum class BottomTab(
    val screen: Screen,
    val label: String,
    val icon: ImageVector,
) {
    TODAY   (Screen.Today,    "Today",    Icons.Filled.Today),
    INSIGHTS(Screen.Insights, "Insights", Icons.Filled.BarChart),
    HISTORY (Screen.History,  "History",  Icons.Filled.History),
    JOURNAL (Screen.Journal,  "Journal",  Icons.AutoMirrored.Filled.MenuBook),
    PROFILE (Screen.Profile,  "Profile",  Icons.Filled.Person),
}
