package com.droidlink.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [PairedDeviceEntity::class, ConnectionEntity::class, TransferEntity::class, SecurityEventEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun pairedDeviceDao(): PairedDeviceDao
    abstract fun connectionDao(): ConnectionDao
    abstract fun transferDao(): TransferDao
    abstract fun securityEventDao(): SecurityEventDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "droidlink.db")
                // Pairings are a cache of server state and the logs are local-only convenience data;
                // add real migrations before shipping a schema v2.
                .fallbackToDestructiveMigration()
                .build()
    }
}
