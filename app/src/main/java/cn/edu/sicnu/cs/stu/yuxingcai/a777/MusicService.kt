package cn.edu.sicnu.cs.stu.yuxingcai.a777

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * 音乐播放服务：
 * 所有与音乐播放相关的功能（播放、暂停、停止、上一首、下一首等）都在本服务中实现。
 * Activity 通过绑定本服务获取 Binder，从而调用这些方法并获取歌曲信息、播放进度。
 */
class MusicService : Service() {

    companion object {
        private const val CHANNEL_ID = "music_playback_channel"
        private const val NOTIFICATION_ID = 1
    }

    /**
     * Binder：Activity 通过它拿到服务实例
     */
    inner class MusicBinder : Binder() {
        fun getService(): MusicService = this@MusicService
    }

    private val binder = MusicBinder()
    private var mediaPlayer: MediaPlayer? = null
    private val musicList = mutableListOf<Music>()
    private var currentIndex = -1
    private var paused = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_NOT_STICKY
    }

    // ==================== 供 Activity 通过 Binder 调用的方法 ====================

    /** 设置播放列表（由 Activity 通过 ContentResolver 查询后传入） */
    fun setMusicList(list: List<Music>) {
        musicList.clear()
        musicList.addAll(list)
    }

    /** 播放指定位置（下标）的歌曲 */
    fun playMusic(index: Int) {
        if (index !in musicList.indices) return
        currentIndex = index
        paused = false
        playCurrent()
    }

    /** 继续播放（若处于暂停状态则恢复，否则从头播放当前歌曲） */
    fun play() {
        if (currentIndex !in musicList.indices) return
        val player = mediaPlayer
        if (player != null && paused) {
            player.start()
            paused = false
        } else if (player == null) {
            paused = false
            playCurrent()
        }
    }

    /** 暂停播放 */
    fun pause() {
        mediaPlayer?.let {
            if (it.isPlaying) {
                it.pause()
                paused = true
            }
        }
    }

    /** 停止播放 */
    fun stop() {
        releasePlayer()
        paused = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIFICATION_ID)
    }

    /** 下一首 */
    fun playNext() {
        if (musicList.isEmpty()) return
        currentIndex = (currentIndex + 1) % musicList.size
        paused = false
        playCurrent()
    }

    /** 上一首 */
    fun playPrevious() {
        if (musicList.isEmpty()) return
        currentIndex = if (currentIndex - 1 < 0) musicList.size - 1 else currentIndex - 1
        paused = false
        playCurrent()
    }

    /** 拖动进度条时调整播放进度 */
    fun seekTo(position: Int) {
        mediaPlayer?.seekTo(position)
    }

    /** 获取当前播放进度（毫秒） */
    fun getCurrentPosition(): Int = mediaPlayer?.currentPosition ?: 0

    /** 获取当前歌曲总时长（毫秒） */
    fun getDuration(): Int = mediaPlayer?.duration ?: 0

    /** 是否正在播放 */
    fun isPlaying(): Boolean = mediaPlayer?.isPlaying ?: false

    /** 获取当前歌曲信息 */
    fun getCurrentMusic(): Music? =
        if (currentIndex in musicList.indices) musicList[currentIndex] else null

    // ==================== 内部实现 ====================

    /** 真正加载并播放当前歌曲 */
    private fun playCurrent() {
        val music = getCurrentMusic() ?: return
        releasePlayer()
        val player = MediaPlayer()
        mediaPlayer = player
        try {
            player.setDataSource(this, Uri.parse(music.uri))
            player.setOnPreparedListener {
                it.start()
                paused = false
            }
            player.setOnCompletionListener {
                playNext() // 播放完自动切到下一首
            }
            player.setOnErrorListener { _, _, _ ->
                releasePlayer()
                true
            }
            player.prepareAsync()
        } catch (e: Exception) {
            releasePlayer()
        }
        // 新歌曲开始播放时，用 Notification 在通知栏显示歌曲名
        showNotification(music)
    }

    /** 释放 MediaPlayer 资源 */
    private fun releasePlayer() {
        mediaPlayer?.release()
        mediaPlayer = null
    }

    /** 显示/更新通知，通知栏中展示当前歌曲名 */
    private fun showNotification(music: Music) {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentTitle(music.title)
            .setContentText(music.artist)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    /** 创建通知渠道（Android 8.0 及以上需要） */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "音乐播放",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "音乐播放通知"
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        releasePlayer()
        super.onDestroy()
    }
}
