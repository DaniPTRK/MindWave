package com.example.mindwave.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Navigation routes for the MindWave app.
 */
sealed class Screen(val route: String) {
    // auth
    object Login : Screen("login")
    object Register : Screen("register")

    // bottom nav dest
    object Home : Screen("home")
    object Stress : Screen("stress")
    object History : Screen("history")
    object Journal : Screen("journal")
    object Profile : Screen("profile")

    // secondary c dest
    object Breathing : Screen("breathing")
    object StressDetail : Screen("stress_detail/{readingId}") {
        const val ARG_READING_ID = "readingId"
        fun createRoute(readingId: Long) = "stress_detail/$readingId"
    }
}

/**
 * Bottom-navigation tab definitions (Home / Stress / History / Journal / Profile).
 */
enum class BottomTab(
    val screen: Screen,
    val label: String,
    val icon: ImageVector,
) {
    HOME(Screen.Home, "Home", Icons.Filled.Home),
    STRESS(Screen.Stress, "Stress", Icons.Filled.Favorite),
    HISTORY(Screen.History, "History", Icons.Filled.History),
    JOURNAL(Screen.Journal, "Journal", Icons.AutoMirrored.Filled.MenuBook),
    PROFILE(Screen.Profile, "Profile", Icons.Filled.Person),
}

