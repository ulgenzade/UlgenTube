package com.ulgentube.app.data.local

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
import com.ulgentube.app.domain.model.DownloadStatus
import com.ulgentube.app.domain.model.DownloadTask
import com.ulgentube.app.domain.model.MediaCategory
import com.ulgentube.app.domain.model.PlatformType
import kotlinx.coroutines.flow.Flow

/**
 * Room veritabanında saklanan indirme görevi kaydı.
 */
@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey
    val id: String,
    val url: String,
    val title: String,
    val author: String?,
    val thumbnailUrl: String?,
    val platform: String,
    val category: String,
    val formatLabel: String,
    val fileName: String,
    val savedUri: String?,
    val savedPath: String?,
    val fileSizeBytes: Long,
    val progress: Float,
    val speedText: String?,
    val etaSeconds: Long?,
    val status: String,
    val errorMessage: String?,
    val ytDlpFormatSelector: String?,
    val directUrl: String?,
    val isAudioExtraction: Boolean,
    val transcodeTargetHeight: Int?,
    val targetExtension: String,
    val createdAt: Long
)

fun DownloadEntity.toDomain(): DownloadTask {
    return DownloadTask(
        id = id,
        url = url,
        title = title,
        author = author,
        thumbnailUrl = thumbnailUrl,
        platform = runCatching { PlatformType.valueOf(platform) }.getOrDefault(PlatformType.OTHER),
        category = runCatching { MediaCategory.valueOf(category) }.getOrDefault(MediaCategory.VIDEO),
        formatLabel = formatLabel,
        fileName = fileName,
        savedUri = savedUri,
        savedPath = savedPath,
        fileSizeBytes = fileSizeBytes,
        progress = progress,
        speedText = speedText,
        etaSeconds = etaSeconds,
        status = runCatching { DownloadStatus.valueOf(status) }.getOrDefault(DownloadStatus.FAILED),
        errorMessage = errorMessage,
        ytDlpFormatSelector = ytDlpFormatSelector,
        directUrl = directUrl,
        isAudioExtraction = isAudioExtraction,
        transcodeTargetHeight = transcodeTargetHeight,
        targetExtension = targetExtension,
        createdAt = createdAt
    )
}

fun DownloadTask.toEntity(): DownloadEntity {
    return DownloadEntity(
        id = id,
        url = url,
        title = title,
        author = author,
        thumbnailUrl = thumbnailUrl,
        platform = platform.name,
        category = category.name,
        formatLabel = formatLabel,
        fileName = fileName,
        savedUri = savedUri,
        savedPath = savedPath,
        fileSizeBytes = fileSizeBytes,
        progress = progress,
        speedText = speedText,
        etaSeconds = etaSeconds,
        status = status.name,
        errorMessage = errorMessage,
        ytDlpFormatSelector = ytDlpFormatSelector,
        directUrl = directUrl,
        isAudioExtraction = isAudioExtraction,
        transcodeTargetHeight = transcodeTargetHeight,
        targetExtension = targetExtension,
        createdAt = createdAt
    )
}

@Dao
interface DownloadDao {

    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE status IN ('QUEUED', 'DOWNLOADING', 'MERGING') ORDER BY createdAt ASC")
    suspend fun getPendingTasks(): List<DownloadEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DownloadEntity)

    @Query(
        """
        UPDATE downloads
        SET progress = :progress,
            speedText = :speedText,
            etaSeconds = :etaSeconds,
            status = :status
        WHERE id = :id
        """
    )
    suspend fun updateProgress(
        id: String,
        progress: Float,
        speedText: String?,
        etaSeconds: Long?,
        status: String
    )

    @Query(
        """
        UPDATE downloads
        SET status = :status,
            errorMessage = :errorMessage,
            savedUri = COALESCE(:savedUri, savedUri),
            savedPath = COALESCE(:savedPath, savedPath),
            fileSizeBytes = CASE WHEN :fileSizeBytes > 0 THEN :fileSizeBytes ELSE fileSizeBytes END,
            progress = CASE WHEN :status = 'COMPLETED' THEN 100.0 ELSE progress END,
            speedText = CASE WHEN :status IN ('COMPLETED', 'FAILED', 'CANCELLED') THEN NULL ELSE speedText END,
            etaSeconds = CASE WHEN :status IN ('COMPLETED', 'FAILED', 'CANCELLED') THEN NULL ELSE etaSeconds END
        WHERE id = :id
        """
    )
    suspend fun updateStatus(
        id: String,
        status: String,
        errorMessage: String? = null,
        savedUri: String? = null,
        savedPath: String? = null,
        fileSizeBytes: Long = 0L
    )

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM downloads WHERE status = 'COMPLETED'")
    suspend fun deleteCompleted()
}

@Database(
    entities = [DownloadEntity::class],
    version = 2,
    exportSchema = false
)
abstract class UlgenTubeDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao

    companion object {
        private const val DATABASE_NAME = "ulgentube_downloads.db"

        @Volatile
        private var INSTANCE: UlgenTubeDatabase? = null

        fun getInstance(context: Context): UlgenTubeDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    UlgenTubeDatabase::class.java,
                    DATABASE_NAME
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
