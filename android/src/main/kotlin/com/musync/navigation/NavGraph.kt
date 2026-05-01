package com.musync.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import com.musync.ui.createroom.CreateRoomScreen
import com.musync.ui.home.HomeScreen
import com.musync.ui.player.PlayerScreen

sealed class Screen(val route: String) {
    data object Home : Screen("home")

    data object CreateRoom : Screen("create_room")

    data object Player : Screen("player?roomId={roomId}&videoId={videoId}") {
        /** Route used when navigating to the player without any pre-supplied IDs. */
        const val BASE_ROUTE = "player"

        fun routeWithRoom(roomId: String): String = "$BASE_ROUTE?roomId=$roomId"

        fun routeWithVideo(
            sessionId: String,
            videoId: String,
        ): String = "$BASE_ROUTE?roomId=$sessionId&videoId=$videoId"
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
            HomeScreen(
                onNavigateToPlayer = { navController.navigate(Screen.Player.BASE_ROUTE) },
                onCreateRoom = { navController.navigate(Screen.CreateRoom.route) },
                onJoinRoom = { roomId -> navController.navigate(Screen.Player.routeWithRoom(roomId)) },
            )
        }
        composable(Screen.CreateRoom.route) {
            CreateRoomScreen(
                onBack = { navController.popBackStack() },
                onRoomCreated = { sessionId, videoId ->
                    navController.navigate(Screen.Player.routeWithVideo(sessionId, videoId)) {
                        // Replace the create-room screen so back returns to Home.
                        popUpTo(Screen.Home.route)
                    }
                },
            )
        }
        composable(
            route = Screen.Player.route,
            arguments =
                listOf(
                    navArgument("roomId") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                    navArgument("videoId") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            deepLinks =
                listOf(
                    navDeepLink { uriPattern = "https://listen.yourdomain.com/room/{roomId}" },
                ),
        ) {
            PlayerScreen(onBack = { navController.popBackStack() })
        }
    }
}
