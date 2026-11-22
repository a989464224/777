package cn.edu.sicnu.cs.stu.yuxingcai.a777

import android.Manifest
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.MediaStore
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : AppCompatActivity() {

    private companion object {
        const val REQUEST_CODE_PERMISSION = 100
    }

    private lateinit var tvTitle: TextView
    private lateinit var tvArtist: TextView
    private lateinit var tvCurrent: TextView
    private lateinit var tvTotal: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var lvMusic: ListView
    private lateinit var btnPrev: Button
    private lateinit var btnPlayPause: Button
    private lateinit var btnStop: Button
    private lateinit var btnNext: Button

    private val musicList = mutableListOf<Music>()
    private var musicService: MusicService? = null
    private var bound = false

    // 进度更新线程相关
    @Volatile
    private var updating = false
    private var progressThread: Thread? = null
    private var userSeeking = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? MusicService.MusicBinder ?: return
            musicService = binder.getService()
            bound = true
            musicService?.setMusicList(musicList)
            refreshCurrentInfo()
            updatePlayPauseButton()
            startProgressUpdater()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            musicService = null
            bound = false
            stopProgressUpdater()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val pad = dp(16)
            v.setPadding(bars.left + pad, bars.top + pad, bars.right + pad, bars.bottom + pad)
            insets
        }

        initViews()
        setupControls()
        checkPermissions()
    }

    override fun onStart() {
        super.onStart()
        // 启动并绑定服务，保证 Activity 退出后音乐仍可继续播放
        val intent = Intent(this, MusicService::class.java)
        startService(intent)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        stopProgressUpdater()
        if (bound) {
            unbindService(connection)
            bound = false
        }
    }

    private fun initViews() {
        tvTitle = findViewById(R.id.tvTitle)
        tvArtist = findViewById(R.id.tvArtist)
        tvCurrent = findViewById(R.id.tvCurrent)
        tvTotal = findViewById(R.id.tvTotal)
        seekBar = findViewById(R.id.seekBar)
        lvMusic = findViewById(R.id.lvMusic)
        btnPrev = findViewById(R.id.btnPrev)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        btnStop = findViewById(R.id.btnStop)
        btnNext = findViewById(R.id.btnNext)
    }

    private fun setupControls() {
        // 点击列表项播放对应歌曲
        lvMusic.setOnItemClickListener { _, _, position, _ ->
            musicService?.playMusic(position)
            refreshCurrentInfo()
            updatePlayPauseButton()
        }

        // 播放 / 暂停
        btnPlayPause.setOnClickListener {
            val svc = musicService ?: return@setOnClickListener
            if (svc.isPlaying()) {
                svc.pause()
            } else {
                svc.play()
            }
            updatePlayPauseButton()
        }

        // 停止
        btnStop.setOnClickListener {
            musicService?.stop()
            tvTitle.text = getString(R.string.not_playing)
            tvArtist.text = ""
            resetProgress()
            updatePlayPauseButton()
        }

        // 上一首
        btnPrev.setOnClickListener {
            musicService?.playPrevious()
            refreshCurrentInfo()
            updatePlayPauseButton()
        }

        // 下一首
        btnNext.setOnClickListener {
            musicService?.playNext()
            refreshCurrentInfo()
            updatePlayPauseButton()
        }

        // SeekBar：拖动时调整播放进度
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    tvCurrent.text = formatTime(progress)
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {
                userSeeking = true
            }

            override fun onStopTrackingTouch(sb: SeekBar?) {
                userSeeking = false
                musicService?.seekTo(sb?.progress ?: 0)
            }
        })
    }

    // ==================== 权限 ====================

    private fun checkPermissions() {
        val need = mutableListOf<String>()
        // 读取音乐文件权限：Android 13(API 33)+ 用 READ_MEDIA_AUDIO
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                need.add(Manifest.permission.READ_MEDIA_AUDIO)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                need.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
        // Android 13+ 发通知也需要运行时权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            need.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (need.isEmpty()) {
            loadMusicList()
        } else {
            ActivityCompat.requestPermissions(this, need.toTypedArray(), REQUEST_CODE_PERMISSION)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSION) {
            val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    this, Manifest.permission.READ_MEDIA_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                ContextCompat.checkSelfPermission(
                    this, Manifest.permission.READ_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED
            }
            if (granted) {
                loadMusicList()
            } else {
                Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_LONG).show()
            }
        }
    }

    // ==================== 通过 ContentResolver 加载音乐列表 ====================

    private fun loadMusicList() {
        val list = mutableListOf<Music>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION
        )
        contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection, null, null, null
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val title = cursor.getString(titleCol) ?: getString(R.string.unknown_title)
                val artist = cursor.getString(artistCol) ?: getString(R.string.unknown_artist)
                val duration = cursor.getLong(durCol)
                val contentUri = ContentUris.withAppendedId(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id
                )
                list.add(Music(id, title, artist, duration, contentUri.toString()))
            }
        }

        musicList.clear()
        musicList.addAll(list)
        val titles = list.map { "${it.title} - ${it.artist}" }
        lvMusic.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, titles)
        musicService?.setMusicList(list)

        if (list.isEmpty()) {
            Toast.makeText(this, R.string.no_music, Toast.LENGTH_LONG).show()
        }
    }

    // ==================== 多线程：实时刷新播放进度 ====================

    private fun startProgressUpdater() {
        if (updating) return
        updating = true
        progressThread = Thread {
            while (updating) {
                musicService?.let { svc ->
                    val position = svc.getCurrentPosition()
                    val duration = svc.getDuration()
                    runOnUiThread { updateSeekBar(position, duration) }
                }
                try {
                    Thread.sleep(500)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }
        progressThread?.start()
    }

    private fun stopProgressUpdater() {
        updating = false
        progressThread?.interrupt()
        progressThread = null
    }

    private fun updateSeekBar(position: Int, duration: Int) {
        if (!userSeeking) {
            seekBar.max = duration
            seekBar.progress = position
        }
        tvCurrent.text = formatTime(position)
        tvTotal.text = formatTime(duration)
    }

    private fun resetProgress() {
        seekBar.progress = 0
        seekBar.max = 0
        tvCurrent.text = formatTime(0)
        tvTotal.text = formatTime(0)
    }

    private fun refreshCurrentInfo() {
        val music = musicService?.getCurrentMusic()
        tvTitle.text = music?.title ?: getString(R.string.not_playing)
        tvArtist.text = music?.artist ?: ""
    }

    private fun updatePlayPauseButton() {
        btnPlayPause.text = if (musicService?.isPlaying() == true) {
            getString(R.string.pause)
        } else {
            getString(R.string.play)
        }
    }

    private fun formatTime(ms: Int): String {
        val totalSeconds = ms / 1000
        val min = totalSeconds / 60
        val sec = totalSeconds % 60
        return String.format("%02d:%02d", min, sec)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
