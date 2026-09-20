package com.veilframe.app.media

import android.content.Context
import android.net.Uri
import android.util.Log
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Modernized video player controller powered by AndroidX Media3 (ExoPlayer).
 * Replaces legacy android.media.MediaPlayer, providing frame-accurate seeking,
 * hardware-accelerated playback on TextureView, seamless speed manipulation,
 * and robust timeline scrub synchronization for the Mobile Video Studio.
 */
@OptIn(UnstableApi::class)
class VideoPlayerController(
    private val context: Context,
    private val textureView: TextureView,
    private val scope: CoroutineScope
) {

    companion object {
        private const val TAG = "VeilFrame.VideoPlayer"
    }

    private var exoPlayer: ExoPlayer? = null
    private var tickerJob: Job? = null

    var isPlaying: Boolean = false
        private set

    var isMuted: Boolean = false
        private set

    var currentSpeed: Float = 1.0f
        private set

    var trimStartMs: Long = 0L
        private set

    var trimEndMs: Long = 0L
        private set

    var durationMs: Long = 0L
        private set

    var videoWidth: Int = 0
        private set

    var videoHeight: Int = 0
        private set

    var onPreparedListener: ((durationMs: Long, width: Int, height: Int) -> Unit)? = null
    var onProgressUpdate: ((positionMs: Long) -> Unit)? = null
    var onPlaybackStateChange: ((isPlaying: Boolean) -> Unit)? = null
    var onErrorListener: ((what: Int, extra: Int) -> Unit)? = null

    private fun getOrCreatePlayer(): ExoPlayer {
        exoPlayer?.let { return it }

        val player = ExoPlayer.Builder(context).build().apply {
            try {
                setVideoTextureView(textureView)
            } catch (e: Exception) {
                Log.w(TAG, "TextureView attachment notice: ${e.message}")
            }
            repeatMode = Player.REPEAT_MODE_OFF

            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_READY -> {
                            val dur = duration.coerceAtLeast(0L)
                            this@VideoPlayerController.durationMs = dur
                            if (this@VideoPlayerController.trimEndMs <= 0L || this@VideoPlayerController.trimEndMs > dur) {
                                this@VideoPlayerController.trimEndMs = dur
                            }
                            applySpeed(currentSpeed)
                            applyMute(isMuted)

                            this@VideoPlayerController.onPreparedListener?.invoke(
                                this@VideoPlayerController.durationMs,
                                this@VideoPlayerController.videoWidth,
                                this@VideoPlayerController.videoHeight
                            )
                        }
                        Player.STATE_ENDED -> {
                            pause()
                            seekTo(trimStartMs)
                        }
                        else -> {}
                    }
                }

                override fun onIsPlayingChanged(playing: Boolean) {
                    this@VideoPlayerController.isPlaying = playing
                    onPlaybackStateChange?.invoke(playing)
                    if (playing) {
                        startTicker()
                    } else {
                        stopTicker()
                    }
                }

                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    if (videoSize.width > 0 && videoSize.height > 0) {
                        this@VideoPlayerController.videoWidth = videoSize.width
                        this@VideoPlayerController.videoHeight = videoSize.height
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    Log.e(TAG, "ExoPlayer playback error: ${error.errorCodeName} (${error.errorCode})", error)
                    onErrorListener?.invoke(error.errorCode, 0)
                }
            })
        }
        exoPlayer = player
        return player
    }

    fun setDataSource(uri: Uri) {
        pause()
        try {
            try {
                textureView.visibility = android.view.View.VISIBLE
                textureView.alpha = 1f
            } catch (_: Exception) {}
            val player = getOrCreatePlayer()
            val mediaItem = MediaItem.fromUri(uri)
            player.setMediaItem(mediaItem)
            player.prepare()
        } catch (e: Exception) {
            Log.e(TAG, "Failed setting media item: ${e.message}", e)
            onErrorListener?.invoke(0, 0)
        }
    }

    /**
     * Fully releases player and detaches/clears TextureView surface to eliminate phantom frame persistence.
     */
    fun clearMedia() {
        pause()
        stopTicker()
        releasePlayer()
        durationMs = 0L
        videoWidth = 0
        videoHeight = 0
        trimStartMs = 0L
        trimEndMs = 0L
        try {
            textureView.visibility = android.view.View.GONE
            textureView.alpha = 0f
        } catch (_: Exception) {}
    }

    fun play() {
        val player = exoPlayer ?: return
        try {
            val currentPos = player.currentPosition
            if (currentPos < trimStartMs || (trimEndMs > trimStartMs && currentPos >= trimEndMs)) {
                player.seekTo(trimStartMs)
            }
            player.play()
        } catch (e: Exception) {
            Log.w(TAG, "Error starting playback: ${e.message}")
        }
    }

    fun pause() {
        stopTicker()
        try {
            exoPlayer?.pause()
        } catch (_: Exception) {}
        isPlaying = false
        onPlaybackStateChange?.invoke(false)
    }

    fun togglePlayPause() {
        if (isPlaying) pause() else play()
    }

    fun replay() {
        pause()
        seekTo(trimStartMs)
        play()
    }

    fun seekTo(positionMs: Long) {
        val minPos = trimStartMs
        val maxPos = if (trimEndMs > trimStartMs) trimEndMs else durationMs.coerceAtLeast(0L)
        val target = positionMs.coerceIn(minPos, maxPos)
        try {
            exoPlayer?.seekTo(target)
            onProgressUpdate?.invoke(target)
        } catch (e: Exception) {
            Log.w(TAG, "Seek failed: ${e.message}")
        }
    }

    fun setSpeed(speed: Float) {
        currentSpeed = speed.coerceIn(0.25f, 4.0f)
        applySpeed(currentSpeed)
    }

    private fun applySpeed(speed: Float) {
        try {
            exoPlayer?.playbackParameters = PlaybackParameters(speed)
        } catch (e: Exception) {
            Log.w(TAG, "Failed applying playback speed: ${e.message}")
        }
    }

    fun setMute(mute: Boolean) {
        isMuted = mute
        applyMute(mute)
    }

    private fun applyMute(mute: Boolean) {
        try {
            exoPlayer?.volume = if (mute) 0f else 1f
        } catch (_: Exception) {}
    }

    fun setTrimBounds(startMs: Long, endMs: Long) {
        trimStartMs = startMs.coerceAtLeast(0L)
        trimEndMs = if (endMs > 0L) endMs else durationMs

        val curPos = currentPosition
        if (curPos < trimStartMs || (trimEndMs > trimStartMs && curPos >= trimEndMs)) {
            seekTo(trimStartMs)
        }
    }

    val currentPosition: Long
        get() = try {
            exoPlayer?.currentPosition ?: 0L
        } catch (_: Exception) {
            0L
        }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch(Dispatchers.Main) {
            while (isActive && isPlaying) {
                val pos = currentPosition
                if (trimEndMs > trimStartMs && pos >= trimEndMs) {
                    seekTo(trimStartMs)
                } else if (pos < trimStartMs) {
                    seekTo(trimStartMs)
                } else {
                    onProgressUpdate?.invoke(pos)
                }
                delay(50)
            }
        }
    }

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }

    private fun releasePlayer() {
        try {
            exoPlayer?.stop()
            exoPlayer?.clearMediaItems()
            exoPlayer?.release()
        } catch (_: Exception) {}
        exoPlayer = null
    }

    fun release() {
        stopTicker()
        releasePlayer()
    }
}
