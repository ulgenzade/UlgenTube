package com.ulgentube.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.ulgentube.app.MainActivity
import com.ulgentube.app.UlgenTubeApp
import com.ulgentube.app.data.DownloadRepository
import com.ulgentube.app.domain.model.DownloadStatus
import com.ulgentube.app.domain.model.DownloadTask
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Arka planda en fazla 3 eşzamanlı indirmeyi yöneten Ön Plan Servisi (Foreground Service).
 * Android 14+ (API 34+) için `FOREGROUND_SERVICE_TYPE_DATA_SYNC` tipini kullanır,
 * canlı ilerleme bildirimi ve "İptal" aksiyonu sunar.
 */
class DownloadForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val concurrencySemaphore = Semaphore(MAX_CONCURRENT_DOWNLOADS)
    private val activeProgressMap = ConcurrentHashMap<String, ActiveNotificationState>()
    private val completedCountInSession = AtomicInteger(0)
    private val lastCompletedTitle = java.util.concurrent.atomic.AtomicReference<String?>(null)

    private val repository: DownloadRepository
        get() = (application as UlgenTubeApp).container.downloadRepository

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL_TASK -> {
                val taskId = intent.getStringExtra(EXTRA_TASK_ID)
                if (!taskId.isNullOrBlank()) {
                    serviceScope.launch {
                        repository.cancel(taskId)
                        activeProgressMap.remove(taskId)
                        refreshProgressNotificationOrStop()
                    }
                }
                return START_NOT_STICKY
            }

            ACTION_CANCEL_ALL -> {
                serviceScope.launch {
                    repository.cancelAllActive()
                    activeProgressMap.clear()
                    stopServiceGracefully()
                }
                return START_NOT_STICKY
            }
        }

        promoteToForeground(buildInitialNotification())
        enqueuePendingTasks()

        return START_NOT_STICKY
    }

    private fun enqueuePendingTasks() {
        serviceScope.launch {
            val pendingTasks = repository.getPendingTasks()
            if (pendingTasks.isEmpty() && activeProgressMap.isEmpty()) {
                stopServiceGracefully()
                return@launch
            }

            pendingTasks.forEach { task ->
                if (!repository.isTaskRunning(task.id)) {
                    launchDownloadJob(task)
                }
            }
        }
    }

    private fun launchDownloadJob(task: DownloadTask) {
        val job = serviceScope.launch {
            activeProgressMap[task.id] = ActiveNotificationState(
                taskId = task.id,
                title = task.title,
                progress = 0,
                speedText = null,
                status = DownloadStatus.QUEUED
            )
            updateProgressNotification()

            try {
                concurrencySemaphore.withPermit {
                    val result = repository.executeDownload(task) { progress, speedText, status ->
                        activeProgressMap[task.id] = ActiveNotificationState(
                            taskId = task.id,
                            title = task.title,
                            progress = progress.toInt().coerceIn(0, 100),
                            speedText = speedText,
                            status = status
                        )
                        updateProgressNotification()
                    }

                    if (result.isSuccess) {
                        completedCountInSession.incrementAndGet()
                        lastCompletedTitle.set(task.title)
                    }
                }
            } catch (_: CancellationException) {
                // İptal durumu repository tarafında güncellendi
            } finally {
                activeProgressMap.remove(task.id)
                repository.unregisterActiveJob(task.id)
                refreshProgressNotificationOrStop()
            }
        }

        repository.registerActiveJob(task.id, job)
    }

    private suspend fun refreshProgressNotificationOrStop() {
        if (activeProgressMap.isNotEmpty()) {
            updateProgressNotification()
            return
        }

        // Yeni eklenmiş bekleyen görev var mı tekrar kontrol et
        val remaining = repository.getPendingTasks().filterNot { repository.isTaskRunning(it.id) }
        if (remaining.isNotEmpty()) {
            remaining.forEach { launchDownloadJob(it) }
            return
        }

        val completedTotal = completedCountInSession.getAndSet(0)
        if (completedTotal > 0) {
            showCompletionNotification(
                completedCount = completedTotal,
                sampleTitle = lastCompletedTitle.getAndSet(null)
            )
        }
        stopServiceGracefully()
    }

    private fun promoteToForeground(notification: Notification) {
        val foregroundType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID_PROGRESS, notification, foregroundType)
    }

    private fun buildInitialNotification(): Notification {
        return NotificationCompat.Builder(this, UlgenTubeApp.CHANNEL_DOWNLOADS_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("UlgenTube İndiriyor")
            .setContentText("İndirme hazırlanıyor...")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setProgress(100, 0, true)
            .setContentIntent(createOpenAppPendingIntent())
            .build()
    }

    private fun updateProgressNotification() {
        val activeList = activeProgressMap.values.toList()
        if (activeList.isEmpty()) return

        val single = activeList.size == 1
        val title: String
        val contentText: String
        val progressValue: Int
        val indeterminate: Boolean

        if (single) {
            val item = activeList.first()
            title = item.title
            progressValue = item.progress
            indeterminate = item.status == DownloadStatus.QUEUED || item.status == DownloadStatus.MERGING
            contentText = when (item.status) {
                DownloadStatus.QUEUED -> "Kuyrukta bekliyor..."
                DownloadStatus.MERGING -> "Medya birleştiriliyor ve kaydediliyor..."
                else -> buildString {
                    append("%${item.progress}")
                    if (!item.speedText.isNullOrBlank()) {
                        append(" • ${item.speedText}")
                    }
                }
            }
        } else {
            val avgProgress = activeList.map { it.progress }.average().toInt().coerceIn(0, 100)
            title = "${activeList.size} medya indiriliyor"
            contentText = "Ortalama ilerleme: %$avgProgress"
            progressValue = avgProgress
            indeterminate = false
        }

        val cancelIntent = if (single) {
            Intent(this, DownloadForegroundService::class.java).apply {
                action = ACTION_CANCEL_TASK
                putExtra(EXTRA_TASK_ID, activeList.first().taskId)
            }
        } else {
            Intent(this, DownloadForegroundService::class.java).apply {
                action = ACTION_CANCEL_ALL
            }
        }

        val cancelPendingIntent = PendingIntent.getService(
            this,
            REQUEST_CODE_CANCEL,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, UlgenTubeApp.CHANNEL_DOWNLOADS_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(contentText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setProgress(100, progressValue, indeterminate)
            .setContentIntent(createOpenAppPendingIntent())
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                if (single) "İptal" else "Tümünü İptal Et",
                cancelPendingIntent
            )
            .build()

        safeNotify(NOTIFICATION_ID_PROGRESS, notification)
    }

    private fun showCompletionNotification(completedCount: Int, sampleTitle: String?) {
        val title = if (completedCount == 1) {
            "İndirme tamamlandı"
        } else {
            "$completedCount indirme tamamlandı"
        }
        val text = sampleTitle?.takeIf { completedCount == 1 }
            ?: "Dosyalar cihaz galerisine kaydedildi."

        val notification = NotificationCompat.Builder(this, UlgenTubeApp.CHANNEL_DOWNLOADS_DONE)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(createOpenAppPendingIntent())
            .build()

        safeNotify(NOTIFICATION_ID_COMPLETED, notification)
    }

    private fun createOpenAppPendingIntent(): PendingIntent {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            REQUEST_CODE_OPEN_APP,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun safeNotify(id: Int, notification: Notification) {
        runCatching {
            NotificationManagerCompat.from(this).notify(id, notification)
        }
    }

    private fun stopServiceGracefully() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private data class ActiveNotificationState(
        val taskId: String,
        val title: String,
        val progress: Int,
        val speedText: String?,
        val status: DownloadStatus
    )

    companion object {
        private const val MAX_CONCURRENT_DOWNLOADS = 3
        private const val NOTIFICATION_ID_PROGRESS = 2001
        private const val NOTIFICATION_ID_COMPLETED = 2002
        private const val REQUEST_CODE_OPEN_APP = 1001
        private const val REQUEST_CODE_CANCEL = 1002

        const val ACTION_START = "com.ulgentube.app.action.START_DOWNLOADS"
        const val ACTION_CANCEL_TASK = "com.ulgentube.app.action.CANCEL_TASK"
        const val ACTION_CANCEL_ALL = "com.ulgentube.app.action.CANCEL_ALL"
        const val EXTRA_TASK_ID = "extra_task_id"

        fun start(context: Context) {
            val intent = Intent(context.applicationContext, DownloadForegroundService::class.java).apply {
                action = ACTION_START
            }
            ContextCompat.startForegroundService(context.applicationContext, intent)
        }
    }
}
