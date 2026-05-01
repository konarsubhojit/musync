package com.musync.di

import com.musync.BuildConfig
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
    @Provides
    @Singleton
    fun provideSocket(): Socket = IO.socket(BuildConfig.SERVER_URL)

    @Provides
    @Singleton
    fun provideClock(): Clock = SystemClock
}
