package com.iamgasgass.gassplayer.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.iamgasgass.gassplayer.ui.screens.EpgScreen
import com.iamgasgass.gassplayer.ui.screens.FavoritesScreen
import com.iamgasgass.gassplayer.ui.screens.HomeScreen
import com.iamgasgass.gassplayer.ui.screens.LiveScreen
import com.iamgasgass.gassplayer.ui.screens.PlayerScreen
import com.iamgasgass.gassplayer.ui.screens.SearchScreen
import com.iamgasgass.gassplayer.ui.screens.SeriesEpisodesScreen
import com.iamgasgass.gassplayer.ui.screens.SeriesScreen
import com.iamgasgass.gassplayer.ui.screens.SettingsScreen
import com.iamgasgass.gassplayer.ui.screens.SourcesScreen
import com.iamgasgass.gassplayer.ui.screens.VodScreen
import com.iamgasgass.gassplayer.ui.theme.Background
import com.iamgasgass.gassplayer.ui.theme.GassTheme

object Routes {
    const val HOME = "home"
    const val LIVE = "live"
    const val VOD = "vod"
    const val SERIES = "series"
    const val EPG = "epg"
    const val SEARCH = "search"
    const val FAVORITES = "favorites"
    const val SOURCES = "sources"
    const val SETTINGS = "settings"
    const val EPISODES = "episodes/{id}"
    const val PLAYER = "player/{id}?url={url}&title={title}"

    fun episodes(id: String): String = "episodes/${Uri.encode(id)}"

    fun player(id: String, url: String = "", title: String = ""): String =
        "player/${Uri.encode(id)}?url=${Uri.encode(url)}&title=${Uri.encode(title)}"
}

@Composable
fun GassPlayerApp(vm: MainViewModel) {
    GassTheme {
        val navController = rememberNavController()
        val state by vm.state.collectAsState()
        val startDestination = if (state.sources.isEmpty()) Routes.SOURCES else Routes.HOME
        var hadSourceAtStartup by rememberSaveable { mutableStateOf(state.sources.isNotEmpty()) }

        LaunchedEffect(state.sources.isNotEmpty()) {
            val hasSource = state.sources.isNotEmpty()
            if (!hadSourceAtStartup && hasSource &&
                navController.currentDestination?.route == Routes.SOURCES
            ) {
                navController.navigate(Routes.HOME) {
                    popUpTo(Routes.SOURCES) { inclusive = true }
                    launchSingleTop = true
                }
            }
            if (hasSource) hadSourceAtStartup = true
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color(0xFF211340), Background),
                        radius = 1100f,
                    ),
                ),
        ) {
            NavHost(
                navController = navController,
                startDestination = startDestination,
            ) {
                composable(Routes.HOME) {
                    HomeScreen(
                        state = state,
                        navigate = navController::navigate,
                        continuePlaying = { progress ->
                            navController.navigate(
                                Routes.player(
                                    id = progress.mediaId,
                                    url = progress.streamUrl,
                                    title = progress.title,
                                ),
                            )
                        },
                    )
                }

                composable(Routes.LIVE) {
                    LiveScreen(
                        state = state,
                        onBack = { navController.popBackStack() },
                        onFavorite = vm::favorite,
                        play = { id -> navController.navigate(Routes.player(id)) },
                    )
                }

                composable(Routes.VOD) {
                    VodScreen(
                        state = state,
                        onBack = { navController.popBackStack() },
                        onFavorite = vm::favorite,
                        play = { id -> navController.navigate(Routes.player(id)) },
                    )
                }

                composable(Routes.SERIES) {
                    SeriesScreen(
                        state = state,
                        onBack = { navController.popBackStack() },
                        onFavorite = vm::favorite,
                        open = { id -> navController.navigate(Routes.episodes(id)) },
                    )
                }

                composable(Routes.EPG) {
                    EpgScreen(
                        state = state,
                        vm = vm,
                        onBack = { navController.popBackStack() },
                        play = { id -> navController.navigate(Routes.player(id)) },
                    )
                }

                composable(Routes.SEARCH) {
                    SearchScreen(
                        state = state,
                        onBack = { navController.popBackStack() },
                        open = { id, type ->
                            val route = if (type == "series") {
                                Routes.episodes(id)
                            } else {
                                Routes.player(id)
                            }
                            navController.navigate(route)
                        },
                    )
                }

                composable(Routes.FAVORITES) {
                    FavoritesScreen(
                        state = state,
                        onBack = { navController.popBackStack() },
                        toggle = vm::favorite,
                        play = { id -> navController.navigate(Routes.player(id)) },
                        openSeries = { id -> navController.navigate(Routes.episodes(id)) },
                    )
                }

                composable(Routes.SOURCES) {
                    SourcesScreen(
                        state = state,
                        onBack = { navController.popBackStack() },
                        add = vm::add,
                        select = vm::select,
                        delete = vm::delete,
                    )
                }

                composable(Routes.SETTINGS) {
                    SettingsScreen(
                        state = state,
                        vm = vm,
                        onBack = { navController.popBackStack() },
                        compact = vm::setCompact,
                        numbers = vm::setNumbers,
                        refresh = { vm.reload(true) },
                    )
                }

                composable(
                    route = Routes.EPISODES,
                    arguments = listOf(navArgument("id") { type = NavType.StringType }),
                ) { entry ->
                    SeriesEpisodesScreen(
                        state = state,
                        id = entry.arguments?.getString("id").orEmpty(),
                        vm = vm,
                        onBack = { navController.popBackStack() },
                        play = { url, title, id ->
                            navController.navigate(Routes.player(id, url, title))
                        },
                    )
                }

                composable(
                    route = Routes.PLAYER,
                    arguments = listOf(
                        navArgument("id") { type = NavType.StringType },
                        navArgument("url") {
                            type = NavType.StringType
                            defaultValue = ""
                        },
                        navArgument("title") {
                            type = NavType.StringType
                            defaultValue = ""
                        },
                    ),
                ) { entry ->
                    PlayerScreen(
                        state = state,
                        id = entry.arguments?.getString("id").orEmpty(),
                        explicitUrl = entry.arguments?.getString("url").orEmpty(),
                        explicitTitle = entry.arguments?.getString("title").orEmpty(),
                        onBack = { navController.popBackStack() },
                        save = vm::saveProgress,
                    )
                }
            }
        }
    }
}
