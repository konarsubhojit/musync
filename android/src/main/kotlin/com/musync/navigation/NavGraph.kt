package com.musync.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import com.musync.ui.home.HomeScreen
import com.musync.ui.player.PlayerScreen

sealed class Screen(val route: String) {
    data object Home : Screen("home")

    data object Player : Screen("player?roomId={roomId}") {
        /** Route used when navigating to the player without a pre-supplied room ID. */
        const val BASE_ROUTE = "player"
    }
}

@Composable
fun MuSyncNavGraph(
    navController: NavHostController = rememberNavController(),
    startDestination: String = Screen.Home.route,
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
    ) {
        composable(Screen.Home.route) {
            HomeScreen(onNavigateToPlayer = { navController.navigate(Screen.Player.BASE_ROUTE) })
        }
        composable(
            route = Screen.Player.route,
            arguments = listOf(
                navArgument("roomId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
            deepLinks = listOf(
                navDeepLink { uriPattern = "https://listen.yourdomain.com/room/{roomId}" },
            ),
        ) {
            PlayerScreen()
        }
    }
}
