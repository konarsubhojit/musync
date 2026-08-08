package com.musync.di

import com.musync.data.remote.ServerConfig
import com.musync.sync.Clock
import com.musync.sync.SystemClock
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.socket.client.IO
import io.socket.client.Socket
import io.socket.engineio.client.transports.WebSocket
import okhttp3.OkHttpClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides
    @Singleton
    fun provideSocket(serverConfig: ServerConfig): Socket =
        IO.socket(
            serverConfig.baseUrl,
            IO.Options.builder()
                .setReconnection(true)
                .setReconnectionAttempts(100)
                .setReconnectionDelay(1_000)
                .setReconnectionDelayMax(10_000)
                .setRandomizationFactor(0.5)
                // Skip the XHR-polling handshake: it is the step that fails behind
                // proxies without sticky sessions, and it makes a wrong/unreachable
                // host look like an endless "Reconnecting…" instead of an error.
                .setTransports(arrayOf(WebSocket.NAME))
                .build(),
        )

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient()

    @Provides
    @Singleton
    fun provideClock(): Clock = SystemClock
}
