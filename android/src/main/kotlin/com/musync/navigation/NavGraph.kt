package com.musync.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.musync.ui.home.HomeScreen
import com.musync.ui.player.PlayerScreen

sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object Player : Screen("player")
}

@Composable
fun MuSyncNavGraph(
    navController: NavHostController = rememberNavController(),
    startDestination: String = Screen.Home.route
) {
    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(Screen.Home.route) {
            HomeScreen(onNavigateToPlayer = { navController.navigate(Screen.Player.route) })
        }
        composable(Screen.Player.route) {
            PlayerScreen()
        }
    }
}
