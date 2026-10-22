package com.utemaster.dealtexter.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import com.utemaster.dealtexter.core.Status
import com.utemaster.dealtexter.core.TextRecord
import com.utemaster.dealtexter.core.TextStore
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/** One row per deal the app has looked at. A deal with status SENT is never texted again. */
@Entity(tableName = "texted_deals")
data class TextRecordEntity(
    @PrimaryKey val dealId: String,
    val dealName: String?,
    val status: String,
    val attempts: Int,
    val lastError: String?,
    val message: String?,
    val sentTo: String?,
    val sentAt: Long?,
    val testMode: Boolean,
    val notePending: Boolean,
    val updatedAt: Long,
) {
    fun toCore() = TextRecord(
        dealId = dealId,
        dealName = dealName,
        status = runCatching { Status.valueOf(status) }.getOrDefault(Status.FAILED),
        attempts = attempts,
        lastError = lastError,
        message = message,
        sentTo = sentTo,
        sentAt = sentAt?.let(Instant::ofEpochMilli),
        testMode = testMode,
        notePending = notePending,
        updatedAt = Instant.ofEpochMilli(updatedAt),
    )

    companion object {
        fun from(r: TextRecord) = TextRecordEntity(
            dealId = r.dealId,
            dealName = r.dealName,
            status = r.status.name,
            attempts = r.attempts,
            lastError = r.lastError,
            message = r.message,
            sentTo = r.sentTo,
            sentAt = r.sentAt?.toEpochMilli(),
            testMode = r.testMode,
            notePending = r.notePending,
            updatedAt = r.updatedAt.toEpochMilli(),
        )
    }
}

@Dao
interface TextRecordDao {
    @Query("SELECT * FROM texted_deals WHERE dealId = :dealId")
    suspend fun get(dealId: String): TextRecordEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(record: TextRecordEntity)

    @Query("SELECT * FROM texted_deals ORDER BY updatedAt DESC LIMIT 100")
    fun recent(): Flow<List<TextRecordEntity>>
}

@Database(entities = [TextRecordEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): TextRecordDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "texter.db")
                    .build()
                    .also { instance = it }
            }
    }
}

class RoomTextStore(private val dao: TextRecordDao) : TextStore {
    override suspend fun get(dealId: String) = dao.get(dealId)?.toCore()
    override suspend fun put(record: TextRecord) = dao.put(TextRecordEntity.from(record))
}
