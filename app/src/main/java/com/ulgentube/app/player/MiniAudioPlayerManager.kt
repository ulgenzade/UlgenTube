package com.ulgentube.app.player

import android.content.Context
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.ulgentube.app.domain.model.DownloadTask
import com.ulgentube.app.domain.model.EqualizerPreset
import com.ulgentube.app.util.MediaUriHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Dahili mini müzik ve arka plan ses çalarının anlık oynatma durumu.
 */
data class MiniPlayerState(
    val currentTask: DownloadTask? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val queue: List<DownloadTask> = emptyList(),
    val currentIndex: Int = -1,
    val shuffleEnabled: Boolean = false,
    val repeatMode: Int = 0, // 0 = OFF, 1 = ALL, 2 = ONE
    val equalizerPreset: EqualizerPreset = EqualizerPreset.FLAT,
    val isBackgroundVideoAudio: Boolean = false
)

/**
 * AndroidX Media3 [ExoPlayer] tabanlı dahili mini müzik çalar, kuyruk,
 * arka plan video ses dinleme ve 5-bant donanım ekolayzer yöneticisi.
 * Tüm ExoPlayer çağrıları güvenli biçimde `Dispatchers.Main` üzerinde yürütülür.
 */
class MiniAudioPlayerManager(private val context: Context) {

    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(MiniPlayerState())
    val state: StateFlow<MiniPlayerState> = _state.asStateFlow()

    /**
     * Oynatma duraklatıldığında, parça değiştiğinde veya periyodik olarak son konumu kaydeder.
     */
    var onSavePosition: ((taskId: String, positionMs: Long) -> Unit)? = null

    private var player: ExoPlayer? = null
    private var progressJob: Job? = null
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var boundAudioSessionId: Int = C.AUDIO_SESSION_ID_UNSET
    private var lastSavedTickMs: Long = 0L

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val currentPos = player?.currentPosition?.coerceAtLeast(0L) ?: _state.value.positionMs
            _state.update { current ->
                current.copy(
                    isPlaying = isPlaying,
                    positionMs = currentPos,
                    durationMs = resolveValidDuration(player?.duration) ?: current.durationMs
                )
            }
            if (isPlaying) {
                startProgressTicker()
            } else {
                stopProgressTicker()
                saveCurrentPositionInternal(currentPos)
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                _state.update { current ->
                    current.copy(
                        durationMs = resolveValidDuration(player?.duration) ?: current.durationMs
                    )
                }
            } else if (playbackState == Player.STATE_ENDED) {
                stopProgressTicker()
                handlePlaybackEnded()
            }
        }

        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            attachAndApplyAudioEffects(audioSessionId, _state.value.equalizerPreset)
        }
    }

    /**
     * Verilen indirilen medya görevini güvenli URI üzerinden oynatmaya başlar.
     */
    fun play(task: DownloadTask) {
        playQueue(
            tasks = listOf(task),
            startIndex = 0,
            playAsBackgroundVideoAudio = false
        )
    }

    /**
     * Verilen medya kuyruğunu belirtilen başlangıç indeksinden itibaren oynatır.
     */
    fun playQueue(
        tasks: List<DownloadTask>,
        startIndex: Int = 0,
        playAsBackgroundVideoAudio: Boolean = false
    ) {
        if (tasks.isEmpty()) return
        val safeIndex = startIndex.coerceIn(0, tasks.lastIndex)
        mainScope.launch {
            saveCurrentPositionInternal()
            val targetTask = tasks[safeIndex]
            val initialPos = targetTask.lastPlaybackPositionMs.takeIf { it > RESUME_THRESHOLD_MS } ?: 0L
            startTaskInternal(
                task = targetTask,
                queue = tasks,
                index = safeIndex,
                startPositionMs = initialPos,
                isBackgroundVideoAudio = playAsBackgroundVideoAudio
            )
        }
    }

    /**
     * Video dosyasının yalnızca sesini arka planda ExoPlayer üzerinden oynatır.
     */
    fun playAsBackgroundAudio(
        task: DownloadTask,
        startPositionMs: Long = task.lastPlaybackPositionMs
    ) {
        mainScope.launch {
            saveCurrentPositionInternal()
            val safeStart = startPositionMs.coerceAtLeast(0L)
            startTaskInternal(
                task = task,
                queue = listOf(task),
                index = 0,
                startPositionMs = safeStart,
                isBackgroundVideoAudio = true
            )
        }
    }

    /**
     * Kuyruktaki bir sonraki parçaya geçer.
     */
    fun playNext() {
        mainScope.launch {
            playNextInternal(fromEnded = false)
        }
    }

    /**
     * Mevcut konum 3 saniyeden ilerideyse parçayı başa sarar; aksi halde önceki parçaya geçer.
     */
    fun playPrevious() {
        mainScope.launch {
            val exo = player
            val currentState = _state.value
            val currentPos = exo?.currentPosition?.coerceAtLeast(0L) ?: currentState.positionMs

            if (currentPos > PREVIOUS_RESTART_THRESHOLD_MS && exo != null) {
                exo.seekTo(0L)
                _state.update { it.copy(positionMs = 0L) }
                return@launch
            }

            val queue = currentState.queue
            if (queue.isEmpty()) return@launch

            saveCurrentPositionInternal(currentPos)

            val prevIndex = when {
                currentState.shuffleEnabled && queue.size > 1 -> pickRandomIndex(queue.size, currentState.currentIndex)
                currentState.currentIndex > 0 -> currentState.currentIndex - 1
                currentState.repeatMode == REPEAT_MODE_ALL -> queue.lastIndex
                else -> 0
            }

            startTaskInternal(
                task = queue[prevIndex],
                queue = queue,
                index = prevIndex,
                startPositionMs = 0L,
                isBackgroundVideoAudio = currentState.isBackgroundVideoAudio
            )
        }
    }

    /**
     * Karışık çalma (shuffle) modunu açar/kapatır.
     */
    fun toggleShuffle() {
        _state.update { it.copy(shuffleEnabled = !it.shuffleEnabled) }
    }

    /**
     * Tekrarlama modunu döngüsel olarak değiştirir: OFF (0) -> ALL (1) -> ONE (2) -> OFF (0).
     */
    fun cycleRepeatMode() {
        _state.update { current ->
            val nextMode = (current.repeatMode + 1) % 3
            current.copy(repeatMode = nextMode)
        }
    }

    /**
     * 5-bant donanım ekolayzer ve Bas Güçlendirme ön ayarını uygular.
     */
    @OptIn(UnstableApi::class)
    fun applyEqualizerPreset(preset: EqualizerPreset) {
        mainScope.launch {
            _state.update { it.copy(equalizerPreset = preset) }
            val sessionId = player?.audioSessionId ?: boundAudioSessionId
            if (sessionId != C.AUDIO_SESSION_ID_UNSET && sessionId != 0) {
                attachAndApplyAudioEffects(sessionId, preset)
            }
        }
    }

    /**
     * Şarkı sözü veya meta veri sonradan güncellendiğinde mevcut görevi ve kuyruğu senkronize eder.
     */
    fun updateCurrentTaskMetadata(updated: DownloadTask) {
        _state.update { current ->
            val updatedCurrent = if (current.currentTask?.id == updated.id) {
                updated
            } else {
                current.currentTask
            }
            val updatedQueue = if (current.queue.any { it.id == updated.id }) {
                current.queue.map { item -> if (item.id == updated.id) updated else item }
            } else {
                current.queue
            }
            current.copy(
                currentTask = updatedCurrent,
                queue = updatedQueue
            )
        }
    }

    /**
     * Oynatma ve duraklatma arasında geçiş yapar. Parça sona erdiyse baştan başlatır.
     */
    fun togglePlayPause() {
        mainScope.launch {
            val exo = player ?: return@launch
            if (exo.isPlaying) {
                exo.pause()
            } else {
                if (exo.playbackState == Player.STATE_ENDED) {
                    exo.seekTo(0L)
                }
                exo.play()
            }
        }
    }

    /**
     * Oynatma konumunu milisaniye cinsinden belirtilen noktaya sarar.
     */
    fun seekTo(positionMs: Long) {
        mainScope.launch {
            val exo = player ?: return@launch
            val safePos = positionMs.coerceAtLeast(0L)
            exo.seekTo(safePos)
            _state.update { it.copy(positionMs = safePos) }
        }
    }

    /**
     * Oynatmayı yalnızca duraklatır (ör. dahili video oynatıcı açıldığında).
     */
    fun pause() {
        mainScope.launch {
            saveCurrentPositionInternal()
            player?.pause()
        }
    }

    /**
     * Oynatmayı durdurur ve mini çalar durumunu sıfırlar.
     */
    fun stop() {
        mainScope.launch {
            saveCurrentPositionInternal()
            stopProgressTicker()
            player?.stop()
            player?.clearMediaItems()
            val preservedPreset = _state.value.equalizerPreset
            val preservedShuffle = _state.value.shuffleEnabled
            val preservedRepeat = _state.value.repeatMode
            _state.value = MiniPlayerState(
                shuffleEnabled = preservedShuffle,
                repeatMode = preservedRepeat,
                equalizerPreset = preservedPreset
            )
        }
    }

    /**
     * ExoPlayer ve donanım ses efekti kaynaklarını tamamen serbest bırakır.
     */
    fun release() {
        mainScope.launch {
            saveCurrentPositionInternal()
            stopProgressTicker()
            releaseAudioEffects()
            player?.removeListener(playerListener)
            player?.release()
            player = null
            _state.value = MiniPlayerState()
            mainScope.cancel()
        }
    }

    @OptIn(UnstableApi::class)
    private fun startTaskInternal(
        task: DownloadTask,
        queue: List<DownloadTask>,
        index: Int,
        startPositionMs: Long,
        isBackgroundVideoAudio: Boolean
    ) {
        val uri = MediaUriHelper.resolveTaskUri(context, task) ?: return
        val exo = getOrCreatePlayer()
        val prev = _state.value

        _state.value = prev.copy(
            currentTask = task,
            isPlaying = true,
            positionMs = startPositionMs,
            durationMs = 0L,
            queue = queue,
            currentIndex = index,
            isBackgroundVideoAudio = isBackgroundVideoAudio
        )

        exo.setMediaItem(MediaItem.fromUri(uri))
        exo.prepare()
        if (startPositionMs > 0L) {
            exo.seekTo(startPositionMs)
        }
        exo.play()

        val sessionId = exo.audioSessionId
        if (sessionId != C.AUDIO_SESSION_ID_UNSET && sessionId != 0) {
            attachAndApplyAudioEffects(sessionId, prev.equalizerPreset)
        }
    }

    private fun handlePlaybackEnded() {
        val current = _state.value
        val finishedTask = current.currentTask
        if (finishedTask != null) {
            onSavePosition?.invoke(finishedTask.id, 0L)
        }

        when (current.repeatMode) {
            REPEAT_MODE_ONE -> {
                val exo = player ?: return
                exo.seekTo(0L)
                exo.play()
                _state.update { it.copy(isPlaying = true, positionMs = 0L) }
            }
            else -> {
                val hasAdvanced = playNextInternal(fromEnded = true)
                if (!hasAdvanced) {
                    _state.update { state ->
                        state.copy(
                            isPlaying = false,
                            positionMs = state.durationMs
                        )
                    }
                }
            }
        }
    }

    private fun playNextInternal(fromEnded: Boolean): Boolean {
        val currentState = _state.value
        val queue = currentState.queue
        if (queue.isEmpty()) return false

        if (!fromEnded) {
            saveCurrentPositionInternal()
        }

        val nextIndex = when {
            currentState.shuffleEnabled && queue.size > 1 -> {
                pickRandomIndex(queue.size, currentState.currentIndex)
            }
            currentState.currentIndex in 0 until queue.lastIndex -> {
                currentState.currentIndex + 1
            }
            currentState.repeatMode == REPEAT_MODE_ALL -> {
                0
            }
            !fromEnded && queue.size == 1 -> {
                0
            }
            else -> -1
        }

        if (nextIndex !in queue.indices) return false

        startTaskInternal(
            task = queue[nextIndex],
            queue = queue,
            index = nextIndex,
            startPositionMs = 0L,
            isBackgroundVideoAudio = currentState.isBackgroundVideoAudio
        )
        return true
    }

    private fun pickRandomIndex(size: Int, excludeIndex: Int): Int {
        if (size <= 1) return 0
        var candidate = Random.nextInt(size)
        if (candidate == excludeIndex) {
            candidate = (candidate + 1) % size
        }
        return candidate
    }

    private fun saveCurrentPositionInternal(explicitPosMs: Long? = null) {
        val task = _state.value.currentTask ?: return
        val pos = (explicitPosMs ?: player?.currentPosition ?: _state.value.positionMs)
            .coerceAtLeast(0L)
        onSavePosition?.invoke(task.id, pos)
    }

    private fun attachAndApplyAudioEffects(audioSessionId: Int, preset: EqualizerPreset) {
        if (audioSessionId == C.AUDIO_SESSION_ID_UNSET || audioSessionId == 0) return

        runCatching {
            if (boundAudioSessionId != audioSessionId || equalizer == null) {
                releaseAudioEffects()
                equalizer = Equalizer(0, audioSessionId).apply { enabled = true }
                bassBoost = runCatching {
                    BassBoost(0, audioSessionId).apply { enabled = true }
                }.getOrNull()
                boundAudioSessionId = audioSessionId
            }

            equalizer?.let { eq ->
                val bandCount = eq.numberOfBands.toInt().coerceAtLeast(0)
                val range = eq.bandLevelRange
                val minLevel = range.getOrNull(0) ?: (-1500).toShort()
                val maxLevel = range.getOrNull(1) ?: 1500.toShort()
                val targetLevels = preset.bandLevelsMb

                for (band in 0 until bandCount) {
                    val mappedIdx = if (targetLevels.isNotEmpty()) {
                        ((band * targetLevels.size) / bandCount).coerceIn(0, targetLevels.lastIndex)
                    } else {
                        0
                    }
                    val desired = targetLevels.getOrElse(mappedIdx) { 0.toShort() }
                    val clamped = desired.coerceIn(minLevel, maxLevel)
                    eq.setBandLevel(band.toShort(), clamped)
                }
                eq.enabled = true
            }

            bassBoost?.let { boost ->
                if (boost.strengthSupported) {
                    val strength: Short = when (preset) {
                        EqualizerPreset.BASS_BOOST -> 700
                        EqualizerPreset.ROCK -> 350
                        EqualizerPreset.TREBLE -> 100
                        EqualizerPreset.VOCAL -> 0
                        EqualizerPreset.FLAT -> 0
                    }
                    boost.setStrength(strength)
                    boost.enabled = strength > 0
                }
            }
        }
    }

    private fun releaseAudioEffects() {
        runCatching { equalizer?.release() }
        equalizer = null
        runCatching { bassBoost?.release() }
        bassBoost = null
        boundAudioSessionId = C.AUDIO_SESSION_ID_UNSET
    }

    private fun getOrCreatePlayer(): ExoPlayer {
        val existing = player
        if (existing != null) return existing
        return ExoPlayer.Builder(context.applicationContext)
            .build()
            .also { created ->
                created.addListener(playerListener)
                player = created
            }
    }

    private fun startProgressTicker() {
        if (progressJob?.isActive == true) return
        lastSavedTickMs = System.currentTimeMillis()
        progressJob = mainScope.launch {
            while (isActive) {
                val exo = player ?: break
                val pos = exo.currentPosition.coerceAtLeast(0L)
                val dur = resolveValidDuration(exo.duration)
                _state.update { current ->
                    current.copy(
                        positionMs = pos,
                        durationMs = dur ?: current.durationMs
                    )
                }
                val now = System.currentTimeMillis()
                if (now - lastSavedTickMs >= PERIODIC_SAVE_INTERVAL_MS) {
                    lastSavedTickMs = now
                    saveCurrentPositionInternal(pos)
                }
                delay(PROGRESS_UPDATE_INTERVAL_MS)
            }
        }
    }

    private fun stopProgressTicker() {
        progressJob?.cancel()
        progressJob = null
    }

    private fun resolveValidDuration(rawDuration: Long?): Long? {
        return rawDuration?.takeIf { it > 0L && it != C.TIME_UNSET }
    }

    companion object {
        const val REPEAT_MODE_OFF = 0
        const val REPEAT_MODE_ALL = 1
        const val REPEAT_MODE_ONE = 2

        private const val PROGRESS_UPDATE_INTERVAL_MS = 350L
        private const val PERIODIC_SAVE_INTERVAL_MS = 5_000L
        private const val PREVIOUS_RESTART_THRESHOLD_MS = 3_000L
        private const val RESUME_THRESHOLD_MS = 2_000L
    }
}
