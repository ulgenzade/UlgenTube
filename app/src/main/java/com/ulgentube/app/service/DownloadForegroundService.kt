package com.ulgentube.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.ulgentube.app.MainActivity
import com.ulgentube.app.UlgenTubeApp
import com.ulgentube.app.data.DownloadRepository
import com.ulgentube.app.data.local.SettingsRepository
import com.ulgentube.app.domain.model.DownloadStatus
import com.ulgentube.app.domain.model.DownloadTask
import com.ulgentube.app.util.MediaUriHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Arka planda kullanıcı ayarına göre (1-4) dinamik eşzamanlı indirmeyi yöneten Ön Plan Servisi.
 * Android 14+ (API 34+) için `FOREGROUND_SERVICE_TYPE_DATA_SYNC` tipini kullanır,
 * Wi-Fi kısıtını denetler, canlı ilerleme bildirimi, "İptal" ve tamamlandı bildiriminde
 * doğrudan "Aç" / "Paylaş" aksiyonları sunar.
 */
class DownloadForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queueMutex = Mutex()
    private val activeCount = AtomicInteger(0)
    private val activeProgressMap = ConcurrentHashMap<String, ActiveNotificationState>()
    private val completedCountInSession = AtomicInteger(0)
    private val lastCompletedTask = AtomicReference<DownloadTask?>(null)
    private val lastStartId = AtomicInteger(-1)
    private val lastNotificationUpdateMs = AtomicLong(0L)
    private val scheduleWaitJob = AtomicReference<Job?>(null)

    private val repository: DownloadRepository
        get() = (application as UlgenTubeApp).container.downloadRepository

    private val settingsRepository: SettingsRepository
        get() = (application as UlgenTubeApp).container.settingsRepository

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId.set(startId)
        when (intent?.action) {
            ACTION_CANCEL_TASK -> {
                val taskId = intent.getStringExtra(EXTRA_TASK_ID)
                if (!taskId.isNullOrBlank()) {
                    serviceScope.launch {
                        repository.cancel(taskId)
                        activeProgressMap.remove(taskId)
                        enqueuePendingTasks()
                    }
                }
                return START_NOT_STICKY
            }

            ACTION_CANCEL_ALL -> {
                serviceScope.launch {
                    scheduleWaitJob.getAndSet(null)?.cancel()
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
            queueMutex.withLock {
                val settings = settingsRepository.getCurrentSettings()
                val maxConcurrent = settings.maxConcurrentDownloads.coerceIn(1, 4)

                if (settings.wifiOnly && !isWifiConnected()) {
                    if (activeCount.get() == 0 && activeProgressMap.isEmpty()) {
                        showWifiWaitingNotification()
                        stopServiceGracefully()
                    }
                    return@withLock
                }

                val pendingTasks = repository.getPendingTasks().filterNot { repository.isTaskRunning(it.id) }

                if (settings.scheduledDownloadEnabled) {
                    val currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
                    if (!isWithinScheduleWindow(currentHour, settings.scheduleStartHour, settings.scheduleEndHour)) {
                        if (pendingTasks.isNotEmpty()) {
                            updateScheduleWaitingNotification(settings.scheduleStartHour, settings.scheduleEndHour)
                            ensureScheduleWaitLoop()
                        } else if (activeCount.get() == 0 && activeProgressMap.isEmpty()) {
                            scheduleWaitJob.getAndSet(null)?.cancel()
                            stopServiceGracefully()
                        }
                        return@withLock
                    } else {
                        scheduleWaitJob.getAndSet(null)?.cancel()
                    }
                } else {
                    scheduleWaitJob.getAndSet(null)?.cancel()
                }

                val currentlyActive = activeCount.get()
                val availableSlots = (maxConcurrent - currentlyActive).coerceAtLeast(0)

                if (availableSlots > 0 && pendingTasks.isNotEmpty()) {
                    pendingTasks.take(availableSlots).forEach { task ->
                        launchDownloadJob(task)
                    }
                }

                if (activeCount.get() == 0 && activeProgressMap.isEmpty() && pendingTasks.isEmpty()) {
                    val completedTotal = completedCountInSession.getAndSet(0)
                    val completedTask = lastCompletedTask.getAndSet(null)
                    if (completedTotal > 0) {
                        showCompletionNotification(
                            completedCount = completedTotal,
                            lastTask = completedTask
                        )
                    }
                    stopServiceGracefully()
                } else if (activeProgressMap.isNotEmpty()) {
                    updateProgressNotification()
                }
            }
        }
    }

    private fun launchDownloadJob(task: DownloadTask) {
        lateinit var job: kotlinx.coroutines.Job
        job = serviceScope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = repository.executeDownload(task) { progress, speedText, status ->
                    val prevStatus = activeProgressMap[task.id]?.status
                    activeProgressMap[task.id] = ActiveNotificationState(
                        taskId = task.id,
                        title = task.title,
                        progress = progress.toInt().coerceIn(0, 100),
                        speedText = speedText,
                        status = status
                    )
                    updateProgressNotification(force = prevStatus != status)
                }

                result.getOrNull()?.let { completedTask ->
                    completedCountInSession.incrementAndGet()
                    lastCompletedTask.set(completedTask)
                }
            } catch (_: CancellationException) {
                // İptal durumu repository tarafında güncellendi
            } finally {
                activeProgressMap.remove(task.id)
                activeCount.updateAndGet { (it - 1).coerceAtLeast(0) }
                repository.unregisterActiveJob(task.id, job)
                if (activeProgressMap.isNotEmpty()) {
                    updateProgressNotification()
                }
                enqueuePendingTasks()
            }
        }

        if (repository.tryRegisterActiveJob(task.id, job)) {
            activeCount.incrementAndGet()
            activeProgressMap[task.id] = ActiveNotificationState(
                taskId = task.id,
                title = task.title,
                progress = 0,
                speedText = null,
                status = DownloadStatus.QUEUED
            )
            updateProgressNotification(force = true)
            if (!job.start()) {
                activeProgressMap.remove(task.id)
                activeCount.updateAndGet { (it - 1).coerceAtLeast(0) }
                repository.unregisterActiveJob(task.id, job)
            }
        } else {
            job.cancel()
        }
    }

    private fun isWifiConnected(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val activeNetwork = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun showWifiWaitingNotification() {
        val notification = NotificationCompat.Builder(this, UlgenTubeApp.CHANNEL_DOWNLOADS_DONE)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("Wi-Fi bekleniyor")
            .setContentText("Wi-Fi bekleniyor — indirmeler duraklatıldı")
            .setAutoCancel(true)
            .setContentIntent(createOpenAppPendingIntent())
            .build()

        safeNotify(NOTIFICATION_ID_WIFI_WAITING, notification)
    }

    private fun updateScheduleWaitingNotification(startHour: Int, endHour: Int) {
        val cancelAllIntent = Intent(this, DownloadForegroundService::class.java).apply {
            action = ACTION_CANCEL_ALL
        }
        val cancelPendingIntent = PendingIntent.getService(
            this,
            REQUEST_CODE_CANCEL,
            cancelAllIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val text = "Zamanlanmış indirme bekleniyor ($startHour:00 - $endHour:00)"
        val notification = NotificationCompat.Builder(this, UlgenTubeApp.CHANNEL_DOWNLOADS_PROGRESS)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("UlgenTube Zamanlayıcı")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(createOpenAppPendingIntent())
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "İptal",
                cancelPendingIntent
            )
            .build()

        safeNotify(NOTIFICATION_ID_PROGRESS, notification)
    }

    private fun ensureScheduleWaitLoop() {
        val existing = scheduleWaitJob.get()
        if (existing != null && existing.isActive) return

        val waitJob = serviceScope.launch {
            while (isActive) {
                delay(SCHEDULE_CHECK_INTERVAL_MS)
                val settings = settingsRepository.getCurrentSettings()
                val pending = repository.getPendingTasks().filterNot { repository.isTaskRunning(it.id) }
                if (pending.isEmpty()) {
                    if (activeCount.get() == 0 && activeProgressMap.isEmpty()) {
                        stopServiceGracefully()
                    }
                    break
                }
                val currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
                if (!settings.scheduledDownloadEnabled ||
                    isWithinScheduleWindow(currentHour, settings.scheduleStartHour, settings.scheduleEndHour)
                ) {
                    enqueuePendingTasks()
                    break
                } else {
                    updateScheduleWaitingNotification(settings.scheduleStartHour, settings.scheduleEndHour)
                }
            }
        }
        val previous = scheduleWaitJob.getAndSet(waitJob)
        if (previous != null && previous !== waitJob) {
            previous.cancel()
        }
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

    private fun updateProgressNotification(force: Boolean = false) {
        val activeList = activeProgressMap.values.toList()
        if (activeList.isEmpty()) return

        val now = System.currentTimeMillis()
        if (!force && now - lastNotificationUpdateMs.get() < NOTIFICATION_THROTTLE_MS) {
            return
        }
        lastNotificationUpdateMs.set(now)

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

    private fun showCompletionNotification(completedCount: Int, lastTask: DownloadTask?) {
        val title = if (completedCount == 1) {
            "İndirme tamamlandı"
        } else {
            "$completedCount indirme tamamlandı"
        }
        val text = lastTask?.title?.takeIf { completedCount == 1 }
            ?: "Dosyalar cihaz galerisine kaydedildi."

        val builder = NotificationCompat.Builder(this, UlgenTubeApp.CHANNEL_DOWNLOADS_DONE)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(createOpenAppPendingIntent())

        if (completedCount == 1 && lastTask != null) {
            val resolvedUri = MediaUriHelper.resolveTaskUri(this, lastTask)
            if (resolvedUri != null) {
                val mimeType = MediaUriHelper.resolveMimeType(lastTask.category)

                val openIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(resolvedUri, mimeType)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val openPendingIntent = PendingIntent.getActivity(
                    this,
                    REQUEST_CODE_OPEN_MEDIA,
                    openIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                    type = mimeType
                    putExtra(Intent.EXTRA_STREAM, resolvedUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val shareChooser = Intent.createChooser(sendIntent, "Medyayı Paylaş").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val sharePendingIntent = PendingIntent.getActivity(
                    this,
                    REQUEST_CODE_SHARE_MEDIA,
                    shareChooser,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                builder
                    .addAction(android.R.drawable.ic_media_play, "Aç", openPendingIntent)
                    .addAction(android.R.drawable.ic_menu_share, "Paylaş", sharePendingIntent)
            }
        }

        safeNotify(NOTIFICATION_ID_COMPLETED, builder.build())
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

    override fun onTimeout(startId: Int, fgsType: Int) {
        activeProgressMap.clear()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
        (application as? UlgenTubeApp)?.applicationScope?.launch {
            runCatching { repository.cancelAllActive() }
        }
    }

    private fun stopServiceGracefully() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        val id = lastStartId.get()
        if (id > 0) {
            stopSelf(id)
        } else {
            stopSelf()
        }
    }

    override fun onDestroy() {
        scheduleWaitJob.getAndSet(null)?.cancel()
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
        private const val NOTIFICATION_THROTTLE_MS = 500L
        private const val SCHEDULE_CHECK_INTERVAL_MS = 60_000L
        private const val NOTIFICATION_ID_PROGRESS = 2001
        private const val NOTIFICATION_ID_COMPLETED = 2002
        private const val NOTIFICATION_ID_WIFI_WAITING = 2003
        private const val REQUEST_CODE_OPEN_APP = 1001
        private const val REQUEST_CODE_CANCEL = 1002
        private const val REQUEST_CODE_OPEN_MEDIA = 1003
        private const val REQUEST_CODE_SHARE_MEDIA = 1004

        const val ACTION_START = "com.ulgentube.app.action.START_DOWNLOADS"
        const val ACTION_CANCEL_TASK = "com.ulgentube.app.action.CANCEL_TASK"
        const val ACTION_CANCEL_ALL = "com.ulgentube.app.action.CANCEL_ALL"
        const val EXTRA_TASK_ID = "extra_task_id"

        /**
         * Verilen saatin (`0..23`) başlangıç-bitiş zamanlama penceresi içinde olup olmadığını kontrol eder.
         * - `startHour == endHour` -> her zaman `true`
         * - Aynı gün penceresi (`startHour < endHour`, örn. 1..7) -> `current in startHour until endHour`
         * - Gece yarısını aşan pencere (`startHour > endHour`, örn. 23..6) -> `current >= startHour || current < endHour`
         */
        fun isWithinScheduleWindow(currentHour: Int, startHour: Int, endHour: Int): Boolean {
            val current = currentHour.coerceIn(0, 23)
            val start = startHour.coerceIn(0, 23)
            val end = endHour.coerceIn(0, 23)
            if (start == end) return true
            return if (start < end) {
                current in start until end
            } else {
                current >= start || current < end
            }
        }

        fun start(context: Context) {
            val intent = Intent(context.applicationContext, DownloadForegroundService::class.java).apply {
                action = ACTION_START
            }
            runCatching {
                ContextCompat.startForegroundService(context.applicationContext, intent)
            }
        }
    }
}
