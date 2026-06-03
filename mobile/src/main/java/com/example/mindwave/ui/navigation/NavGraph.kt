package com.example.mindwave.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.mindwave.ui.BreathingExerciseScreen
import com.example.mindwave.ui.DashboardScreen
import com.example.mindwave.ui.DashboardViewModel
import com.example.mindwave.ui.HistoryScreen
import com.example.mindwave.ui.JournalDialog
import com.example.mindwave.ui.JournalListScreen
import com.example.mindwave.ui.ProfileScreen
import com.example.mindwave.ui.StressDetailScreen
import com.example.mindwave.ui.auth.AuthViewModel
import com.example.mindwave.ui.auth.LoginScreen
import com.example.mindwave.ui.auth.RegisterScreen

/**
 * Root composable: holds the navhost, the bottom navigation bar, and the
 * auth gate (start at Login when logged-out, Home otherwise).
 */
@Composable
fun MindWaveApp() {
    val navController = rememberNavController()
    val authViewModel: AuthViewModel = viewModel()
    val dashViewModel: DashboardViewModel = viewModel()

    val startDestination = remember {
        if (authViewModel.isLoggedIn()) Screen.Home.route else Screen.Login.route
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = BottomTab.entries.any { it.screen.route == currentRoute }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    BottomTab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.screen.route,
                            onClick = {
                                if (currentRoute != tab.screen.route) {
                                    navController.navigate(tab.screen.route) {
                                        popUpTo(Screen.Home.route) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            ),
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.padding(innerPadding),
        ) {
            // Auth flow
            composable(Screen.Login.route) {
                LoginScreen(
                    vm = authViewModel,
                    onLoginSuccess = {
                        navController.navigate(Screen.Home.route) {
                            popUpTo(Screen.Login.route) { inclusive = true }
                        }
                    },
                    onNavigateToRegister = { navController.navigate(Screen.Register.route) },
                )
            }
            composable(Screen.Register.route) {
                RegisterScreen(
                    vm = authViewModel,
                    onRegistered = {
                        navController.navigate(Screen.Home.route) {
                            popUpTo(Screen.Login.route) { inclusive = true }
                        }
                    },
                    onBackToLogin = { navController.popBackStack() },
                )
            }

            // home / dashboard
            composable(Screen.Home.route) {
                val latest by dashViewModel.latestReading.collectAsStateWithLifecycle()
                val xai by dashViewModel.xaiExplanations.collectAsStateWithLifecycle()
                val recs by dashViewModel.recommendations.collectAsStateWithLifecycle()
                var showJournal by remember { androidx.compose.runtime.mutableStateOf(false) }
                DashboardScreen(
                    latestReading = latest,
                    xaiExplanations = xai,
                    recommendations = recs,
                    onJournalClick = { showJournal = true },
                    onFeedback = { dashViewModel.saveFeedback(it) },
                    onOpenDetail = {
                        latest?.let { navController.navigate(Screen.StressDetail.createRoute(it.id)) }
                    },
                )
                if (showJournal) {
                    JournalDialog(
                        onDismiss = { showJournal = false },
                        onSave = { mood, note ->
                            dashViewModel.saveJournal(mood, note)
                            showJournal = false
                        },
                    )
                }
            }

            // stress tab
            composable(Screen.Stress.route) {
                androidx.compose.runtime.LaunchedEffect(Unit) { dashViewModel.loadStressDetail(0L) }
                StressDetailScreen(
                    reading = dashViewModel.detailReading,
                    xaiExplanations = dashViewModel.detailXai,
                    contextAnnotation = dashViewModel.detailContext,
                    onBreathingExercise = { navController.navigate(Screen.Breathing.route) },
                    onDismiss = { navController.navigate(Screen.Home.route) },
                    onJournalEntry = { mood, note -> dashViewModel.saveJournal(mood, note) },
                )
            }

            // Stress detail from notification
            composable(
                route = Screen.StressDetail.route,
                arguments = listOf(navArgument(Screen.StressDetail.ARG_READING_ID) {
                    type = NavType.LongType
                }),
            ) { entry ->
                val id = entry.arguments?.getLong(Screen.StressDetail.ARG_READING_ID) ?: 0L
                androidx.compose.runtime.LaunchedEffect(id) { dashViewModel.loadStressDetail(id) }
                StressDetailScreen(
                    reading = dashViewModel.detailReading,
                    xaiExplanations = dashViewModel.detailXai,
                    contextAnnotation = dashViewModel.detailContext,
                    onBreathingExercise = { navController.navigate(Screen.Breathing.route) },
                    onDismiss = { navController.popBackStack() },
                    onJournalEntry = { mood, note -> dashViewModel.saveJournal(mood, note) },
                )
            }

            // history tab
            composable(Screen.History.route) {
                val readings by dashViewModel.allReadings.collectAsStateWithLifecycle()
                HistoryScreen(readings = readings)
            }

            // journal tab
            composable(Screen.Journal.route) {
                val entries by dashViewModel.journalEntries.collectAsStateWithLifecycle()
                var showJournal by remember { androidx.compose.runtime.mutableStateOf(false) }
                var editingEntry by remember {
                    androidx.compose.runtime.mutableStateOf<com.example.mindwave.data.EmotionalJournal?>(null)
                }
                JournalListScreen(
                    entries = entries,
                    xaiByReading = dashViewModel.xaiByReading,
                    stressScoreByReading = dashViewModel.stressScoreByReading,
                    onAddEntry = { showJournal = true },
                    onDeleteEntry = { dashViewModel.deleteJournal(it) },
                    onEditEntry = { editingEntry = it },
                )
                if (showJournal) {
                    JournalDialog(
                        onDismiss = { showJournal = false },
                        onSave = { mood, note ->
                            dashViewModel.saveJournal(mood, note)
                            showJournal = false
                        },
                    )
                }
                editingEntry?.let { entry ->
                    JournalDialog(
                        initialMood = entry.userMood,
                        initialNote = entry.note,
                        title = "Edit entry",
                        confirmLabel = "Update",
                        onDismiss = { editingEntry = null },
                        onSave = { mood, note ->
                            dashViewModel.updateJournal(entry, mood, note)
                            editingEntry = null
                        },
                    )
                }
            }

            // profile
            composable(Screen.Profile.route) {
                ProfileScreen(
                    onLogout = {
                        authViewModel.logout {
                            navController.navigate(Screen.Login.route) {
                                popUpTo(0) { inclusive = true }
                            }
                        }
                    },
                )
            }

            // breathing ex
            composable(Screen.Breathing.route) {
                BreathingExerciseScreen(onClose = { navController.popBackStack() })
            }
        }
    }
}
