package com.example.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        PeerEntity::class,
        MessageEntity::class,
        GroupEntity::class,
        RouteHopEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class ZungaDatabase : RoomDatabase() {
    abstract fun peerDao(): PeerDao
    abstract fun messageDao(): MessageDao
    abstract fun groupDao(): GroupDao
    abstract fun routeHopDao(): RouteHopDao

    companion object {
        @Volatile
        private var INSTANCE: ZungaDatabase? = null

        fun getDatabase(context: Context): ZungaDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    ZungaDatabase::class.java,
                    "zunga_mesh_database"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
