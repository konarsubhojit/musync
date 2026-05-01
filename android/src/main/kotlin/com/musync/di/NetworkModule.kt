package com.musync.di

import com.musync.sync.Clock
import com.musync.sync.SystemClock
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.socket.client.IO
import io.socket.client.Socket
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    /**
     * Signalling server URL.
     * `10.0.2.2` is the special alias that routes from the Android emulator to the host
     * machine's localhost.  Override via build-config or remote-config for production.
     */
    private const val SERVER_URL = "http://10.0.2.2:3000"

    @Provides
    @Singleton
    fun provideSocket(): Socket = IO.socket(SERVER_URL)

    @Provides
    @Singleton
    fun provideClock(): Clock = SystemClock
}
