package com.iamgasgass.gassplayer.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.iamgasgass.gassplayer.ui.screens.EpgScreen
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
    const val SOURCES = "sources"
    const val SETTINGS = "settings"
    const val EPISODES = "episodes/{id}"
    const val PLAYER = "player/{id}?url={url}&title={title}"

    fun episodes(id: String): String = "episodes/${Uri.encode(id)}"

    fun player(
        id: String,
        url: String = "",
        title: String = "",
    ): String = "player/${Uri.encode(id)}" +
        "?url=${Uri.encode(url)}&title=${Uri.encode(title)}"
}

@Composable
fun GassPlayerApp(vm: MainViewModel) {
    GassTheme {
        val navController = rememberNavController()
        val state by vm.state.collectAsState()
        val startDestination = if (state.sources.isEmpty()) {
            Routes.SOURCES
        } else {
            Routes.HOME
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
                        navigate = { route -> navController.navigate(route) },
                        play = { id -> navController.navigate(Routes.player(id)) },
                    )
                }

                composable(Routes.LIVE) {
                    LiveScreen(
                        state = state,
                        onBack = { navController.popBackStack() },
                        onFavorite = { id -> vm.favorite(id) },
                        play = { id -> navController.navigate(Routes.player(id)) },
                    )
                }

                composable(Routes.VOD) {
                    VodScreen(
                        state = state,
                        onBack = { navController.popBackStack() },
                        onFavorite = { id -> vm.favorite(id) },
                        play = { id -> navController.navigate(Routes.player(id)) },
                    )
                }

                composable(Routes.SERIES) {
                    SeriesScreen(
                        state = state,
                        onBack = { navController.popBackStack() },
                        onFavorite = { id -> vm.favorite(id) },
                        open = { id -> navController.navigate(Routes.episodes(id)) },
                    )
                }

                composable(Routes.EPG) {
                    EpgScreen(
                        state = state,
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

                composable(Routes.SOURCES) {
                    SourcesScreen(
                        state = state,
                        onBack = { navController.popBackStack() },
                        add = { name, type, url, user, password ->
                            vm.add(name, type, url, user, password)
                        },
                        select = { source -> vm.select(source) },
                        delete = { id -> vm.delete(id) },
                    )
                }

                composable(Routes.SETTINGS) {
                    SettingsScreen(
                        state = state,
                        onBack = { navController.popBackStack() },
                        compact = { enabled -> vm.setCompact(enabled) },
                        numbers = { enabled -> vm.setNumbers(enabled) },
                        refresh = { vm.reload(true) },
                    )
                }

                composable(
                    route = Routes.EPISODES,
                    arguments = listOf(
                        navArgument("id") { type = NavType.StringType },
                    ),
                ) { entry ->
                    SeriesEpisodesScreen(
                        state = state,
                        id = entry.arguments?.getString("id").orEmpty(),
                        vm = vm,
                        onBack = { navController.popBackStack() },
                        play = { url, title, id ->
                            navController.navigate(
                                Routes.player(id = id, url = url, title = title),
                            )
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
                        save = { progress -> vm.saveProgress(progress) },
                    )
                }
            }
        }
    }
}
