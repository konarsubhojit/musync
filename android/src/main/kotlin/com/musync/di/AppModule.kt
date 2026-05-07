package com.musync.di

import com.musync.data.remote.RoomStatusChecker
import com.musync.data.remote.RoomStatusCheckerImpl
import com.musync.data.repository.MusicRepository
import com.musync.data.repository.MusicRepositoryImpl
import com.musync.data.repository.RecentRoomsRepository
import com.musync.data.repository.RecentRoomsRepositoryImpl
import com.musync.data.repository.SessionRepository
import com.musync.data.repository.SessionRepositoryImpl
import com.musync.data.repository.YouTubeSearchRepository
import com.musync.data.repository.YouTubeSearchRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {
    @Binds
    @Singleton
    abstract fun bindMusicRepository(impl: MusicRepositoryImpl): MusicRepository

    @Binds
    @Singleton
    abstract fun bindSessionRepository(impl: SessionRepositoryImpl): SessionRepository

    @Binds
    @Singleton
    abstract fun bindYouTubeSearchRepository(impl: YouTubeSearchRepositoryImpl): YouTubeSearchRepository

    @Binds
    @Singleton
    abstract fun bindRecentRoomsRepository(impl: RecentRoomsRepositoryImpl): RecentRoomsRepository

    @Binds
    @Singleton
    abstract fun bindRoomStatusChecker(impl: RoomStatusCheckerImpl): RoomStatusChecker
}
