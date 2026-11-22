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
    val createdAt: Long,
    val clipStartSeconds: Long? = null,
    val clipEndSeconds: Long? = null,
    val subtitleLang: String? = null,
    val embedSubtitles: Boolean = false,
    val lastPlaybackPositionMs: Long = 0L,
    val isFavorite: Boolean = false,
    val isInVault: Boolean = false,
    val lyricsText: String? = null
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
        createdAt = createdAt,
        clipStartSeconds = clipStartSeconds,
        clipEndSeconds = clipEndSeconds,
        subtitleLang = subtitleLang,
        embedSubtitles = embedSubtitles,
        lastPlaybackPositionMs = lastPlaybackPositionMs,
        isFavorite = isFavorite,
        isInVault = isInVault,
        lyricsText = lyricsText
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
        createdAt = createdAt,
        clipStartSeconds = clipStartSeconds,
        clipEndSeconds = clipEndSeconds,
        subtitleLang = subtitleLang,
        embedSubtitles = embedSubtitles,
        lastPlaybackPositionMs = lastPlaybackPositionMs,
        isFavorite = isFavorite,
        isInVault = isInVault,
        lyricsText = lyricsText
    )
}

@Dao
interface DownloadDao {

    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE url = :url AND status = 'COMPLETED' ORDER BY createdAt DESC LIMIT 1")
    suspend fun findCompletedByUrl(url: String): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE status IN ('QUEUED', 'DOWNLOADING', 'MERGING') ORDER BY createdAt ASC")
    suspend fun getPendingTasks(): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE status = 'PAUSED' ORDER BY createdAt ASC")
    suspend fun getPausedTasks(): List<DownloadEntity>

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
          AND status IN ('QUEUED', 'DOWNLOADING', 'MERGING')
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
            speedText = CASE WHEN :status IN ('COMPLETED', 'FAILED', 'CANCELLED', 'PAUSED') THEN NULL ELSE speedText END,
            etaSeconds = CASE WHEN :status IN ('COMPLETED', 'FAILED', 'CANCELLED', 'PAUSED') THEN NULL ELSE etaSeconds END
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

    @Query("UPDATE downloads SET lastPlaybackPositionMs = :positionMs WHERE id = :id")
    suspend fun updatePlaybackPosition(id: String, positionMs: Long)

    @Query("UPDATE downloads SET isFavorite = :isFavorite WHERE id = :id")
    suspend fun updateFavorite(id: String, isFavorite: Boolean)

    @Query("UPDATE downloads SET isInVault = :isInVault, savedUri = COALESCE(:savedUri, savedUri), savedPath = COALESCE(:savedPath, savedPath) WHERE id = :id")
    suspend fun updateVaultState(id: String, isInVault: Boolean, savedUri: String?, savedPath: String?)

    @Query("UPDATE downloads SET lyricsText = :lyricsText WHERE id = :id")
    suspend fun updateLyricsText(id: String, lyricsText: String?)
}

@Entity(tableName = "followed_channels")
data class FollowedChannelEntity(
    @PrimaryKey
    val channelId: String,
    val title: String,
    val url: String,
    val platform: String,
    val autoDownloadLatest: Boolean,
    val lastKnownVideoId: String?,
    val lastKnownVideoTitle: String?,
    val lastCheckedAt: Long,
    val createdAt: Long
)

fun FollowedChannelEntity.toDomain(): com.ulgentube.app.domain.model.FollowedChannel {
    return com.ulgentube.app.domain.model.FollowedChannel(
        channelId = channelId,
        title = title,
        url = url,
        platform = runCatching { PlatformType.valueOf(platform) }.getOrDefault(PlatformType.YOUTUBE),
        autoDownloadLatest = autoDownloadLatest,
        lastKnownVideoId = lastKnownVideoId,
        lastKnownVideoTitle = lastKnownVideoTitle,
        lastCheckedAt = lastCheckedAt,
        createdAt = createdAt
    )
}

fun com.ulgentube.app.domain.model.FollowedChannel.toEntity(): FollowedChannelEntity {
    return FollowedChannelEntity(
        channelId = channelId,
        title = title,
        url = url,
        platform = platform.name,
        autoDownloadLatest = autoDownloadLatest,
        lastKnownVideoId = lastKnownVideoId,
        lastKnownVideoTitle = lastKnownVideoTitle,
        lastCheckedAt = lastCheckedAt,
        createdAt = createdAt
    )
}

@Dao
interface FollowedChannelDao {
    @Query("SELECT * FROM followed_channels ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<FollowedChannelEntity>>

    @Query("SELECT * FROM followed_channels ORDER BY createdAt DESC")
    suspend fun getAll(): List<FollowedChannelEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: FollowedChannelEntity)

    @Query("UPDATE followed_channels SET lastKnownVideoId = :videoId, lastKnownVideoTitle = :videoTitle, lastCheckedAt = :checkedAt WHERE channelId = :channelId")
    suspend fun updateLatestVideo(channelId: String, videoId: String?, videoTitle: String?, checkedAt: Long)

    @Query("UPDATE followed_channels SET autoDownloadLatest = :enabled WHERE channelId = :channelId")
    suspend fun updateAutoDownload(channelId: String, enabled: Boolean)

    @Query("DELETE FROM followed_channels WHERE channelId = :channelId")
    suspend fun deleteById(channelId: String)
}

@Database(
    entities = [DownloadEntity::class, FollowedChannelEntity::class],
    version = 4,
    exportSchema = false
)
abstract class UlgenTubeDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao
    abstract fun followedChannelDao(): FollowedChannelDao

    companion object {
        private const val DATABASE_NAME = "ulgentube_downloads.db"

        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE downloads ADD COLUMN transcodeTargetHeight INTEGER DEFAULT NULL")
            }
        }

        private val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE downloads ADD COLUMN clipStartSeconds INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE downloads ADD COLUMN clipEndSeconds INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE downloads ADD COLUMN subtitleLang TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE downloads ADD COLUMN embedSubtitles INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE downloads ADD COLUMN lastPlaybackPositionMs INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE downloads ADD COLUMN isFavorite INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE downloads ADD COLUMN isInVault INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE downloads ADD COLUMN lyricsText TEXT DEFAULT NULL")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS followed_channels (
                        channelId TEXT NOT NULL PRIMARY KEY,
                        title TEXT NOT NULL,
                        url TEXT NOT NULL,
                        platform TEXT NOT NULL,
                        autoDownloadLatest INTEGER NOT NULL,
                        lastKnownVideoId TEXT,
                        lastKnownVideoTitle TEXT,
                        lastCheckedAt INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        @Volatile
        private var INSTANCE: UlgenTubeDatabase? = null

        fun getInstance(context: Context): UlgenTubeDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    UlgenTubeDatabase::class.java,
                    DATABASE_NAME
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
