package com.droidlink.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
abstract class PairedDeviceDao {
    @Query("SELECT * FROM paired_devices ORDER BY updatedAt DESC")
    abstract fun observeAll(): Flow<List<PairedDeviceEntity>>

    @Upsert
    abstract suspend fun upsert(entity: PairedDeviceEntity)

    @Upsert
    abstract suspend fun upsertAll(entities: List<PairedDeviceEntity>)

    @Query("DELETE FROM paired_devices")
    abstract suspend fun clear()

    /** Server is the source of truth: the local table mirrors it so the UI works offline. */
    @Transaction
    open suspend fun replaceAll(entities: List<PairedDeviceEntity>) {
        clear()
        upsertAll(entities)
    }
}

@Dao
abstract class ConnectionDao {
    @Query("SELECT * FROM connection_history ORDER BY startedAt DESC LIMIT 500")
    abstract fun observe(): Flow<List<ConnectionEntity>>

    @Insert
    abstract suspend fun insert(entity: ConnectionEntity): Long

    @Query("SELECT COUNT(*) FROM connection_history WHERE sessionId = :sessionId")
    abstract suspend fun countFor(sessionId: String): Int

    @Query("UPDATE connection_history SET endedAt = :endedAt, outcome = :outcome WHERE sessionId = :sessionId AND endedAt IS NULL")
    abstract suspend fun finish(sessionId: String, endedAt: Long, outcome: String)

    @Query("DELETE FROM connection_history")
    abstract suspend fun clear()
}

@Dao
abstract class TransferDao {
    @Query("SELECT * FROM transfers ORDER BY startedAt DESC LIMIT 500")
    abstract fun observe(): Flow<List<TransferEntity>>

    @Upsert
    abstract suspend fun upsert(entity: TransferEntity)

    @Query("DELETE FROM transfers")
    abstract suspend fun clear()
}

@Dao
abstract class SecurityEventDao {
    @Query("SELECT * FROM security_events ORDER BY id DESC LIMIT 500")
    abstract fun observe(): Flow<List<SecurityEventEntity>>

    @Insert
    abstract suspend fun insert(entity: SecurityEventEntity)

    @Query("DELETE FROM security_events WHERE id NOT IN (SELECT id FROM security_events ORDER BY id DESC LIMIT 1000)")
    abstract suspend fun trim()

    @Query("DELETE FROM security_events")
    abstract suspend fun clear()
}
