package com.ulgentube.app.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.ulgentube.app.domain.model.DownloadTask
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

/**
 * Dahili mini müzik çaların anlık oynatma durumu.
 */
data class MiniPlayerState(
    val currentTask: DownloadTask? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L
)

/**
 * AndroidX Media3 [ExoPlayer] tabanlı hafif dahili mini müzik çalar yöneticisi.
 * Tüm ExoPlayer çağrıları güvenli biçimde `Dispatchers.Main` üzerinde yürütülür.
 */
class MiniAudioPlayerManager(private val context: Context) {

    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(MiniPlayerState())
    val state: StateFlow<MiniPlayerState> = _state.asStateFlow()

    private var player: ExoPlayer? = null
    private var progressJob: Job? = null

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.update { current ->
                current.copy(
                    isPlaying = isPlaying,
                    positionMs = player?.currentPosition?.coerceAtLeast(0L) ?: current.positionMs,
                    durationMs = resolveValidDuration(player?.duration) ?: current.durationMs
                )
            }
            if (isPlaying) {
                startProgressTicker()
            } else {
                stopProgressTicker()
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
                _state.update { current ->
                    current.copy(
                        isPlaying = false,
                        positionMs = current.durationMs
                    )
                }
            }
        }
    }

    /**
     * Verilen indirilen medya görevini güvenli URI üzerinden oynatmaya başlar.
     */
    fun play(task: DownloadTask) {
        mainScope.launch {
            val uri = MediaUriHelper.resolveTaskUri(context, task) ?: return@launch
            val exo = getOrCreatePlayer()
            _state.value = MiniPlayerState(
                currentTask = task,
                isPlaying = true,
                positionMs = 0L,
                durationMs = 0L
            )
            exo.setMediaItem(MediaItem.fromUri(uri))
            exo.prepare()
            exo.play()
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
     * Oynatmayı durdurur ve mini çalar durumunu sıfırlar.
     */
    fun stop() {
        mainScope.launch {
            stopProgressTicker()
            player?.stop()
            player?.clearMediaItems()
            _state.value = MiniPlayerState()
        }
    }

    /**
     * ExoPlayer kaynaklarını tamamen serbest bırakır.
     */
    fun release() {
        mainScope.launch {
            stopProgressTicker()
            player?.removeListener(playerListener)
            player?.release()
            player = null
            _state.value = MiniPlayerState()
            mainScope.cancel()
        }
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
                delay(PROGRESS_UPDATE_INTERVAL_MS)
            }
        }
    }

    private fun stopProgressTicker() {
        progressJob?.cancel()
        progressJob = null
    }

    private fun resolveValidDuration(rawDuration: Long?): Long? {
        return rawDuration?.takeIf { it > 0L && it != androidx.media3.common.C.TIME_UNSET }
    }

    companion object {
        private const val PROGRESS_UPDATE_INTERVAL_MS = 500L
    }
}
