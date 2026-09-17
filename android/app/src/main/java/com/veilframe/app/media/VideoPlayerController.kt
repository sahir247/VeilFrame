package com.veilframe.app.media

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.Surface
import android.view.TextureView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Dedicated video player controller managing MediaPlayer directly on a TextureView.
 * Provides precise playback speed control, trim-boundary enforcement, continuous scrubber
 * synchronization, seeking, and mute state without VideoView limitations.
 */
class VideoPlayerController(
    private val context: Context,
    private val textureView: TextureView,
    private val scope: CoroutineScope
) : TextureView.SurfaceTextureListener {

    companion object {
        private const val TAG = "VeilFrame.VideoPlayer"
    }

    private var mediaPlayer: MediaPlayer? = null
    private var surface: Surface? = null
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

    private var pendingUri: Uri? = null

    init {
        textureView.surfaceTextureListener = this
        if (textureView.isAvailable) {
            val st = textureView.surfaceTexture
            if (st != null) {
                surface = Surface(st)
            }
        }
    }

    override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        surface?.release()
        surface = Surface(surfaceTexture)
        mediaPlayer?.setSurface(surface)
        pendingUri?.let { uri ->
            pendingUri = null
            setDataSource(uri)
        }
    }

    override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) {}

    override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
        mediaPlayer?.setSurface(null)
        surface?.release()
        surface = null
        return true
    }

    override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {}

    fun setDataSource(uri: Uri) {
        if (surface == null) {
            pendingUri = uri
            return
        }

        pause()
        releasePlayer()

        try {
            mediaPlayer = MediaPlayer().apply {
                setSurface(this@VideoPlayerController.surface)
                setDataSource(context, uri)

                setOnPreparedListener { mp ->
                    durationMs = mp.duration.toLong()
                    videoWidth = mp.videoWidth
                    videoHeight = mp.videoHeight
                    if (trimEndMs <= 0L || trimEndMs > durationMs) {
                        trimEndMs = durationMs
                    }

                    applySpeed(currentSpeed)
                    applyMute(isMuted)

                    onPreparedListener?.invoke(durationMs, videoWidth, videoHeight)
                }

                setOnCompletionListener {
                    pause()
                    seekTo(trimStartMs)
                }

                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer error: what=$what, extra=$extra")
                    onErrorListener?.invoke(what, extra)
                    true
                }

                prepareAsync()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed setting data source: ${e.message}", e)
            onErrorListener?.invoke(0, 0)
        }
    }

    fun play() {
        val player = mediaPlayer ?: return
        try {
            val currentPos = player.currentPosition.toLong()
            if (currentPos < trimStartMs || currentPos >= trimEndMs) {
                player.seekTo(trimStartMs.toInt())
            }
            player.start()
            isPlaying = true
            onPlaybackStateChange?.invoke(true)
            startTicker()
        } catch (e: Exception) {
            Log.w(TAG, "Error starting playback: ${e.message}")
        }
    }

    fun pause() {
        stopTicker()
        try {
            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.pause()
            }
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
        val target = positionMs.coerceIn(0L, durationMs)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                mediaPlayer?.seekTo(target, MediaPlayer.SEEK_CLOSEST)
            } else {
                mediaPlayer?.seekTo(target.toInt())
            }
            onProgressUpdate?.invoke(target)
        } catch (e: Exception) {
            Log.w(TAG, "Seek failed: ${e.message}")
        }
    }

    fun setSpeed(speed: Float) {
        currentSpeed = speed
        applySpeed(speed)
    }

    private fun applySpeed(speed: Float) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val player = mediaPlayer ?: return
                val params = player.playbackParams ?: PlaybackParams()
                params.speed = speed
                player.playbackParams = params
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed applying playback speed: ${e.message}")
        }
    }

    fun setMute(mute: Boolean) {
        isMuted = mute
        applyMute(mute)
    }

    private fun applyMute(mute: Boolean) {
        val vol = if (mute) 0f else 1f
        try {
            mediaPlayer?.setVolume(vol, vol)
        } catch (_: Exception) {}
    }

    fun setTrimBounds(startMs: Long, endMs: Long) {
        trimStartMs = startMs.coerceAtLeast(0L)
        trimEndMs = if (endMs > 0L) endMs else durationMs

        val curPos = currentPosition
        if (curPos < trimStartMs || curPos > trimEndMs) {
            seekTo(trimStartMs)
        }
    }

    val currentPosition: Long
        get() = try {
            mediaPlayer?.currentPosition?.toLong() ?: 0L
        } catch (_: Exception) {
            0L
        }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch(Dispatchers.Main) {
            while (isActive && isPlaying) {
                val pos = currentPosition
                if (pos >= trimEndMs && trimEndMs > trimStartMs) {
                    pause()
                    seekTo(trimStartMs)
                    break
                }
                onProgressUpdate?.invoke(pos)
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
            mediaPlayer?.stop()
            mediaPlayer?.reset()
            mediaPlayer?.release()
        } catch (_: Exception) {}
        mediaPlayer = null
    }

    fun release() {
        stopTicker()
        releasePlayer()
        surface?.release()
        surface = null
    }
}
