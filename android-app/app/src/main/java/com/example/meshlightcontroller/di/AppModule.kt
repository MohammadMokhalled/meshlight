package com.example.meshlightcontroller.di

import android.content.Context
import androidx.room.Room
import com.example.meshlightcontroller.data.AppDatabase
import com.google.gson.Gson
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton fun database(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "meshlight_metadata.db")
            .addMigrations(AppDatabase.Migration1To2).build()
    @Provides @Singleton fun gateway(real: com.example.meshlightcontroller.core.mesh.NordicMeshGateway): com.example.meshlightcontroller.core.mesh.MeshGateway = real
    @Provides @Singleton fun gson(): Gson = Gson()
    @Provides @Singleton @ApplicationScope fun scope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
}
