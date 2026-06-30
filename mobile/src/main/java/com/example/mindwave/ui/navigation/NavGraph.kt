package com.example.mindwave.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.navigation.navDeepLink
import com.example.mindwave.data.EmotionalJournal
import com.example.mindwave.ui.*
import com.example.mindwave.ui.auth.AuthViewModel
import com.example.mindwave.ui.auth.LoginScreen
import com.example.mindwave.ui.auth.RegisterScreen
import kotlinx.coroutines.launch

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
        if (authViewModel.isLoggedIn()) Screen.Today.route else Screen.Login.route
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = BottomTab.entries.any { it.screen.route == currentRoute }

    // Snackbar state and helper function to show messages from anywhere in the graph
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun snack(msg: String) { scope.launch { snackbarHostState.showSnackbar(msg) } }

    Scaffold(
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState) { data ->
                Snackbar(snackbarData = data,
                    containerColor = MaterialTheme.colorScheme.inverseSurface,
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface)
            }
        },
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    BottomTab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.screen.route,
                            onClick = {
                                if (currentRoute != tab.screen.route) {
                                    navController.navigate(tab.screen.route) {
                                        popUpTo(Screen.Today.route) { saveState = true }
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
                        navController.navigate(Screen.Today.route) {
                            popUpTo(Screen.Login.route) { inclusive = true }
                        }
                    },
                    onNavigateToRegister = { navController.navigate(Screen.Register.route) },
                    onContinueOffline = {
                        authViewModel.continueOffline {
                            navController.navigate(Screen.Today.route) {
                                popUpTo(Screen.Login.route) { inclusive = true }
                            }
                        }
                    },
                )
            }
            composable(Screen.Register.route) {
                RegisterScreen(
                    vm = authViewModel,
                    onRegistered = {
                        navController.navigate(Screen.Today.route) {
                            popUpTo(Screen.Login.route) { inclusive = true }
                        }
                    },
                    onBackToLogin = { navController.popBackStack() },
                )
            }

            // Dashboard
            composable(Screen.Today.route) {
                val latest by dashViewModel.latestReading.collectAsStateWithLifecycle()
                val xai    by dashViewModel.xaiExplanations.collectAsStateWithLifecycle()
                val recs   by dashViewModel.recommendations.collectAsStateWithLifecycle()
                var showJournal by remember { mutableStateOf(false) }
                TodayScreen(
                    latestReading      = latest,
                    xaiExplanations    = xai,
                    recommendations    = recs,
                    onOpenDetail       = { latest?.let { navController.navigate(Screen.StressDetail.createRoute(it.id)) } },
                    onStartBreathing   = { navController.navigate(Screen.Breathing.route) },
                    onJournalClick     = { showJournal = true },
                    onFeedback         = { isStressed ->
                        dashViewModel.saveFeedback(isStressed)
                        snack(if (isStressed) "Feedback saved — confirmed stress" else "Feedback saved — marked as false alarm")
                    },
                    onSensorStatusClick= { navController.navigate(Screen.SensorStatus.route) },
                )
                if (showJournal) {
                    JournalDialog(
                        onDismiss = { showJournal = false },
                        onSave = { mood, note ->
                            dashViewModel.saveJournal(mood, note)
                            showJournal = false
                            snack("Journal entry saved ")
                        },
                    )
                }
            }

            // Insights tab
            composable(Screen.Insights.route) {
                val latest    by dashViewModel.latestReading.collectAsStateWithLifecycle()
                val xai       by dashViewModel.xaiExplanations.collectAsStateWithLifecycle()
                val all       by dashViewModel.allReadings.collectAsStateWithLifecycle()
                val confirmed by dashViewModel.confirmedStressEventCount.collectAsStateWithLifecycle()
                InsightsScreen(
                    latestReading        = latest,
                    xaiExplanations      = xai,
                    allReadings          = all,
                    confirmedStressCount = confirmed,
                    onOpenDetail         = { latest?.let { navController.navigate(Screen.StressDetail.createRoute(it.id)) } },
                )
            }

            // Stress detail from notification
            composable(
                route = Screen.StressDetail.route,
                arguments = listOf(navArgument(Screen.StressDetail.ARG_READING_ID) {
                    type = NavType.LongType
                }),
                deepLinks = listOf(navDeepLink {
                    uriPattern = "mindwave://stress_detail/{readingId}"
                }),
            ) { entry ->
                val id = entry.arguments?.getLong(Screen.StressDetail.ARG_READING_ID) ?: 0L
                LaunchedEffect(id) { dashViewModel.loadStressDetail(id) }
                StressDetailScreen(
                    reading           = dashViewModel.detailReading,
                    xaiExplanations   = dashViewModel.detailXai,
                    contextAnnotation = dashViewModel.detailContext,
                    onBreathingExercise = { navController.navigate(Screen.Breathing.route) },
                    onDismiss         = { navController.popBackStack() },
                    onJournalEntry    = { mood, note ->
                        dashViewModel.saveJournal(mood, note)
                        snack("Reading documented ")
                    },
                )
            }

            // History tab
            composable(Screen.History.route) {
                val readings by dashViewModel.allReadings.collectAsStateWithLifecycle()
                HistoryScreen(readings = readings)
            }

            // Journal tab
            composable(Screen.Journal.route) {
                val entries by dashViewModel.journalEntries.collectAsStateWithLifecycle()
                var showJournal  by remember { mutableStateOf(false) }
                var editingEntry by remember { mutableStateOf<EmotionalJournal?>(null) }
                JournalListScreen(
                    entries             = entries,
                    xaiByReading        = dashViewModel.xaiByReading,
                    stressScoreByReading= dashViewModel.stressScoreByReading,
                    onAddEntry          = { showJournal = true },
                    onDeleteEntry       = {
                        dashViewModel.deleteJournal(it)
                        snack("Entry deleted")
                    },
                    onEditEntry         = { editingEntry = it },
                )
                if (showJournal) {
                    JournalDialog(
                        onDismiss = { showJournal = false },
                        onSave = { mood, note ->
                            dashViewModel.saveJournal(mood, note)
                            showJournal = false
                            snack("Journal entry saved")
                        },
                    )
                }
                editingEntry?.let { entry ->
                    JournalDialog(
                        initialMood  = entry.userMood,
                        initialNote  = entry.note,
                        title        = "Edit entry",
                        confirmLabel = "Update",
                        onDismiss    = { editingEntry = null },
                        onSave = { mood, note ->
                            dashViewModel.updateJournal(entry, mood, note)
                            editingEntry = null
                            snack("Entry updated ")
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
                    onOpenBenchmark = { navController.navigate(Screen.Benchmark.route) },
                )
            }

            // breathing ex
            composable(Screen.Breathing.route) {
                BreathingExerciseScreen(onClose = { navController.popBackStack() })
            }

            // sensor status
            composable(Screen.SensorStatus.route) {
                val latest by dashViewModel.latestReading.collectAsStateWithLifecycle()
                SensorStatusScreen(
                    latestReading = latest,
                    onBack = { navController.popBackStack() },
                )
            }

            // benchmark / profiling
            composable(Screen.Benchmark.route) {
                BenchmarkScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}
