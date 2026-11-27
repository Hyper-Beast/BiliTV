package com.bili.tv.bili_tv_app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.DisplayMetrics
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 原生悬浮小窗播放服务（基于 TYPE_APPLICATION_OVERLAY + TextureView + Media3 ExoPlayer）
 *
 * 专为 Android TV（特别是 Sony Z9F 等 HDMI Sideband 设备）优化：
 * 1. 使用 TextureView 走 GPU Client 合成层，可安全叠加在全屏 HDMI Sideband 图层之上；
 * 2. 默认优先软件解码器，避免与硬件视频管线/系统资源管理冲突；
 * 3. 关闭 ExoPlayer 的 AudioFocus 自动抢占与丢焦暂停（handleAudioFocus = false），
 *    切换到 HDMI 全屏时悬浮小窗保持连续播放，不会被系统强行暂停。
 */
@OptIn(UnstableApi::class)
class FloatingPlayerService : Service() {

    companion object {
        private const val TAG = "BiliTV-FloatingPlayer"
        private const val NOTIFICATION_CHANNEL_ID = "bilitv_floating_player"
        private const val NOTIFICATION_ID = 33221

        const val ACTION_START = "com.bili.tv.bili_tv_app.ACTION_FLOATING_START"
        const val ACTION_STOP = "com.bili.tv.bili_tv_app.ACTION_FLOATING_STOP"
        const val ACTION_UPDATE_CONFIG = "com.bili.tv.bili_tv_app.ACTION_FLOATING_UPDATE_CONFIG"
        const val ACTION_TOGGLE_PLAY = "com.bili.tv.bili_tv_app.ACTION_FLOATING_TOGGLE_PLAY"
        const val ACTION_TOGGLE_MUTE = "com.bili.tv.bili_tv_app.ACTION_FLOATING_TOGGLE_MUTE"
        const val ACTION_CYCLE_CORNER = "com.bili.tv.bili_tv_app.ACTION_FLOATING_CYCLE_CORNER"
        const val ACTION_CYCLE_SIZE = "com.bili.tv.bili_tv_app.ACTION_FLOATING_CYCLE_SIZE"
        const val ACTION_EXPAND = "com.bili.tv.bili_tv_app.ACTION_FLOATING_EXPAND"

        const val EXTRA_URL = "url"
        const val EXTRA_MPD_CONTENT = "mpdContent"
        const val EXTRA_HEADERS_KEYS = "headersKeys"
        const val EXTRA_HEADERS_VALUES = "headersValues"
        const val EXTRA_POSITION_MS = "positionMs"
        const val EXTRA_PLAYBACK_SPEED = "playbackSpeed"
        const val EXTRA_TITLE = "title"
        const val EXTRA_BVID = "bvid"
        const val EXTRA_CID = "cid"
        const val EXTRA_ROOM_ID = "roomId"
        const val EXTRA_IS_LIVE = "isLive"
        const val EXTRA_CORNER = "corner"
        const val EXTRA_SIZE = "size"
        const val EXTRA_MUTED = "muted"
        const val EXTRA_SOFTWARE_DECODE = "softwareDecode"

        const val CORNER_BOTTOM_RIGHT = "bottom_right"
        const val CORNER_TOP_RIGHT = "top_right"
        const val CORNER_BOTTOM_LEFT = "bottom_left"
        const val CORNER_TOP_LEFT = "top_left"

        const val SIZE_SMALL = "small"
        const val SIZE_MEDIUM = "medium"
        const val SIZE_LARGE = "large"

        @Volatile
        private var activeInstance: FloatingPlayerService? = null

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var currentBvid: String? = null
            private set

        @Volatile
        var currentCid: Int = 0
            private set

        @Volatile
        var currentRoomId: Int = 0
            private set

        @Volatile
        var currentIsLive: Boolean = false
            private set

        @Volatile
        var currentTitle: String = ""
            private set

        @Volatile
        var lastPositionMs: Long = 0L
            private set

        @Volatile
        var currentIsPlaying: Boolean = false
            private set

        @Volatile
        var currentIsMuted: Boolean = false
            private set

        @Volatile
        var currentDecoderName: String = ""
            private set

        @Volatile
        var currentSoftwareDecode: Boolean = true
            private set

        @Volatile
        var currentCorner: String = CORNER_BOTTOM_RIGHT
            private set

        @Volatile
        var currentSize: String = SIZE_MEDIUM
            private set

        @Volatile
        var lastError: String? = null
            private set

        fun getStateMap(): Map<String, Any?> {
            val instance = activeInstance
            val pos = instance?.readCurrentPositionMs() ?: lastPositionMs
            return mapOf(
                "isRunning" to isRunning,
                "bvid" to currentBvid,
                "cid" to currentCid,
                "roomId" to currentRoomId,
                "isLive" to currentIsLive,
                "title" to currentTitle,
                "positionMs" to pos,
                "isPlaying" to currentIsPlaying,
                "isMuted" to currentIsMuted,
                "decoderName" to currentDecoderName,
                "softwareDecode" to currentSoftwareDecode,
                "corner" to currentCorner,
                "size" to currentSize,
                "lastError" to lastError
            )
        }

        fun stopAndGetPositionMs(context: Context): Long {
            val instance = activeInstance
            val pos = instance?.readCurrentPositionMs() ?: lastPositionMs
            if (instance != null) {
                instance.stopSelfSafely()
            } else {
                context.stopService(Intent(context, FloatingPlayerService::class.java))
            }
            return pos
        }
    }

    private var windowManager: WindowManager? = null
    private var rootContainer: FrameLayout? = null
    private var textureView: TextureView? = null
    private var loadingSpinner: ProgressBar? = null
    private var statusBadge: TextView? = null
    private var errorText: TextView? = null
    private var controlBar: LinearLayout? = null
    private var playPauseBtn: TextView? = null
    private var muteBtn: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private var exoPlayer: ExoPlayer? = null
    private var mpdTempFile: File? = null
    private var videoAspectRatio: Float = 16f / 9f
    private var hasRetriedWithSoftware: Boolean = false
    private var currentPlayUrl: String? = null
    private var currentMpdContent: String? = null
    private var currentHeaders: Map<String, String> = emptyMap()
    private var currentPlaybackSpeed: Float = 1.0f

    private val mainHandler = Handler(Looper.getMainLooper())
    private val hideControlsRunnable = Runnable {
        controlBar?.visibility = View.GONE
        statusBadge?.visibility = View.GONE
    }
    private val positionPoller = object : Runnable {
        override fun run() {
            val p = exoPlayer
            if (p != null) {
                lastPositionMs = p.currentPosition.coerceAtLeast(0L)
                currentIsPlaying = p.isPlaying
            }
            if (isRunning) {
                mainHandler.postDelayed(this, 1000L)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForegroundNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        Log.i(TAG, "onStartCommand action=$action")

        when (action) {
            ACTION_STOP -> {
                stopSelfSafely()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_PLAY -> {
                togglePlayPause()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_MUTE -> {
                toggleMute()
                return START_NOT_STICKY
            }
            ACTION_CYCLE_CORNER -> {
                cycleCorner()
                return START_NOT_STICKY
            }
            ACTION_CYCLE_SIZE -> {
                cycleSize()
                return START_NOT_STICKY
            }
            ACTION_EXPAND -> {
                expandToApp()
                return START_NOT_STICKY
            }
            ACTION_UPDATE_CONFIG -> {
                intent?.getStringExtra(EXTRA_CORNER)?.let { currentCorner = it }
                intent?.getStringExtra(EXTRA_SIZE)?.let { currentSize = it }
                if (intent?.hasExtra(EXTRA_MUTED) == true) {
                    applyMute(intent.getBooleanExtra(EXTRA_MUTED, currentIsMuted))
                }
                applyWindowLayout()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                    Log.e(TAG, "Missing SYSTEM_ALERT_WINDOW permission")
                    lastError = "未授予悬浮窗权限"
                    stopSelfSafely()
                    return START_NOT_STICKY
                }
                handleStartPlayback(intent)
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private fun handleStartPlayback(intent: Intent?) {
        if (intent == null) {
            stopSelfSafely()
            return
        }

        val url = intent.getStringExtra(EXTRA_URL)
        val mpdContent = intent.getStringExtra(EXTRA_MPD_CONTENT)
        if (url.isNullOrEmpty() && mpdContent.isNullOrEmpty()) {
            Log.e(TAG, "Both url and mpdContent are empty")
            lastError = "播放地址为空"
            stopSelfSafely()
            return
        }

        val keys = intent.getStringArrayListExtra(EXTRA_HEADERS_KEYS) ?: arrayListOf()
        val values = intent.getStringArrayListExtra(EXTRA_HEADERS_VALUES) ?: arrayListOf()
        val headers = mutableMapOf<String, String>()
        for (i in 0 until minOf(keys.size, values.size)) {
            headers[keys[i]] = values[i]
        }

        currentPlayUrl = url
        currentMpdContent = mpdContent
        currentHeaders = headers
        currentBvid = intent.getStringExtra(EXTRA_BVID)
        currentCid = intent.getIntExtra(EXTRA_CID, 0)
        currentRoomId = intent.getIntExtra(EXTRA_ROOM_ID, 0)
        currentIsLive = intent.getBooleanExtra(EXTRA_IS_LIVE, false)
        currentTitle = intent.getStringExtra(EXTRA_TITLE) ?: ""
        lastPositionMs = intent.getLongExtra(EXTRA_POSITION_MS, 0L).coerceAtLeast(0L)
        currentPlaybackSpeed = intent.getFloatExtra(EXTRA_PLAYBACK_SPEED, 1.0f).coerceIn(0.5f, 2.0f)
        currentCorner = intent.getStringExtra(EXTRA_CORNER) ?: CORNER_BOTTOM_RIGHT
        currentSize = intent.getStringExtra(EXTRA_SIZE) ?: SIZE_MEDIUM
        currentIsMuted = intent.getBooleanExtra(EXTRA_MUTED, false)
        currentSoftwareDecode = intent.getBooleanExtra(EXTRA_SOFTWARE_DECODE, true)
        currentDecoderName = ""
        lastError = null
        hasRetriedWithSoftware = false
        videoAspectRatio = 16f / 9f

        ensureOverlayView()
        applyWindowLayout()
        startExoPlayer(forceSoftware = currentSoftwareDecode, startPositionMs = lastPositionMs)

        isRunning = true
        mainHandler.removeCallbacks(positionPoller)
        mainHandler.postDelayed(positionPoller, 1000L)
    }

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "BiliTV 悬浮小窗播放",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持悬浮小窗视频在后台与其他界面之上持续播放"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }

        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val piFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val contentIntent = PendingIntent.getActivity(this, 0, openIntent, piFlags)

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        val notification = builder
            .setContentTitle("BiliTV 悬浮小窗播放中")
            .setContentText("点击返回 BiliTV 全屏")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun ensureOverlayView() {
        if (rootContainer != null) {
            errorText?.visibility = View.GONE
            loadingSpinner?.visibility = View.VISIBLE
            return
        }

        val cornerRadiusPx = dpToPx(10f).toFloat()

        val container = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, cornerRadiusPx)
                }
            }
            clipToOutline = true
        }

        // TextureView：走 GPU Client 合成层，完美叠加在全屏 HDMI Sideband 图层之上
        val tv = TextureView(this)
        container.addView(
            tv,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        )
        textureView = tv

        // 细边框装饰层，方便在暗色画面上分辨小窗边界
        val borderView = View(this).apply {
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(dpToPx(1.5f), Color.argb(140, 255, 255, 255))
                cornerRadius = cornerRadiusPx
            }
        }
        container.addView(
            borderView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        // 加载指示器
        val spinner = ProgressBar(this).apply {
            isIndeterminate = true
            visibility = View.VISIBLE
        }
        container.addView(
            spinner,
            FrameLayout.LayoutParams(dpToPx(32f), dpToPx(32f), Gravity.CENTER)
        )
        loadingSpinner = spinner

        // 错误提示
        val errTv = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            setPadding(dpToPx(10f), dpToPx(8f), dpToPx(10f), dpToPx(8f))
            setBackgroundColor(Color.argb(190, 0, 0, 0))
            visibility = View.GONE
        }
        container.addView(
            errTv,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )
        errorText = errTv

        // 顶部状态/标题小条（仅操作时短暂显示）
        val badge = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dpToPx(8f), dpToPx(4f), dpToPx(8f), dpToPx(4f))
            setBackgroundColor(Color.argb(170, 0, 0, 0))
            visibility = View.GONE
        }
        container.addView(
            badge,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
        )
        statusBadge = badge

        // 底部触摸/鼠标控制条（默认隐藏，点击小窗后显示 4 秒）
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dpToPx(4f), dpToPx(4f), dpToPx(4f), dpToPx(4f))
            setBackgroundColor(Color.argb(185, 18, 18, 18))
            visibility = View.GONE
        }

        val btnPlay = createMiniControlButton("暂停") { togglePlayPause() }
        val btnMute = createMiniControlButton(if (currentIsMuted) "静音" else "声音") { toggleMute() }
        val btnCorner = createMiniControlButton("换角") { cycleCorner() }
        val btnExpand = createMiniControlButton("全屏") { expandToApp() }
        val btnClose = createMiniControlButton("关闭") { stopSelfSafely() }

        playPauseBtn = btnPlay
        muteBtn = btnMute

        bar.addView(btnPlay)
        bar.addView(btnMute)
        bar.addView(btnCorner)
        bar.addView(btnExpand)
        bar.addView(btnClose)

        container.addView(
            bar,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        )
        controlBar = bar

        // 支持鼠标点击显示控制栏及拖拽移动
        setupTouchAndDrag(container)

        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val (w, h) = computeWindowSize()
        val params = WindowManager.LayoutParams(
            w,
            h,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )
        applyCornerGravity(params)

        layoutParams = params
        rootContainer = container
        windowManager?.addView(container, params)
    }

    private fun createMiniControlButton(label: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            gravity = Gravity.CENTER
            setPadding(dpToPx(6f), dpToPx(4f), dpToPx(6f), dpToPx(4f))
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
            setOnClickListener {
                onClick()
                scheduleHideControls()
            }
        }
    }

    private fun setupTouchAndDrag(container: FrameLayout) {
        var downRawX = 0f
        var downRawY = 0f
        var initialX = 0
        var initialY = 0
        var isDragging = false

        container.setOnTouchListener { _, event ->
            val params = layoutParams ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    initialX = params.x
                    initialY = params.y
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).roundToInt()
                    val dy = (event.rawY - downRawY).roundToInt()
                    if (!isDragging && (abs(dx) > 12 || abs(dy) > 12)) {
                        isDragging = true
                    }
                    if (isDragging) {
                        val isRight = (params.gravity and Gravity.END) == Gravity.END
                        val isBottom = (params.gravity and Gravity.BOTTOM) == Gravity.BOTTOM
                        params.x = (initialX + if (isRight) -dx else dx).coerceAtLeast(0)
                        params.y = (initialY + if (isBottom) -dy else dy).coerceAtLeast(0)
                        try {
                            windowManager?.updateViewLayout(container, params)
                        } catch (_: Exception) {
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        toggleMiniControls()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun toggleMiniControls() {
        val bar = controlBar ?: return
        if (bar.visibility == View.VISIBLE) {
            bar.visibility = View.GONE
            statusBadge?.visibility = View.GONE
            mainHandler.removeCallbacks(hideControlsRunnable)
        } else {
            updateControlLabels()
            bar.visibility = View.VISIBLE
            statusBadge?.apply {
                text = buildBadgeText()
                visibility = View.VISIBLE
            }
            scheduleHideControls()
        }
    }

    private fun showBriefBadge(msg: String) {
        statusBadge?.apply {
            text = msg
            visibility = View.VISIBLE
        }
        scheduleHideControls(2500L)
    }

    private fun scheduleHideControls(delayMs: Long = 4000L) {
        mainHandler.removeCallbacks(hideControlsRunnable)
        mainHandler.postDelayed(hideControlsRunnable, delayMs)
    }

    private fun buildBadgeText(): String {
        val mode = if (currentSoftwareDecode) "软解" else "自动"
        val title = if (currentTitle.isNotEmpty()) currentTitle else "BiliTV 小窗"
        return "[$mode] $title"
    }

    private fun updateControlLabels() {
        playPauseBtn?.text = if (exoPlayer?.isPlaying == true) "暂停" else "播放"
        muteBtn?.text = if (currentIsMuted) "已静音" else "声音"
    }

    private fun startExoPlayer(forceSoftware: Boolean, startPositionMs: Long) {
        releasePlayerOnly()

        loadingSpinner?.visibility = View.VISIBLE
        errorText?.visibility = View.GONE

        val selector = if (forceSoftware) {
            SoftwarePreferredCodecSelector()
        } else {
            MediaCodecSelector.DEFAULT
        }

        val renderersFactory = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
            .setEnableDecoderFallback(true)
            .setMediaCodecSelector(selector)

        val userAgent = currentHeaders["User-Agent"]
            ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36"

        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
            .setDefaultRequestProperties(currentHeaders)

        val dataSourceFactory = DefaultDataSource.Factory(this, httpFactory)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)

        val player = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()

        // 关键：handleAudioFocus = false，防止切到 HDMI 全屏时因丢焦导致小窗视频自动暂停！
        val audioAttrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
        player.setAudioAttributes(audioAttrs, /* handleAudioFocus= */ false)
        player.volume = if (currentIsMuted) 0f else 1f
        player.playbackParameters = PlaybackParameters(currentPlaybackSpeed)

        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long
            ) {
                currentDecoderName = decoderName
                Log.i(TAG, "Video decoder initialized: $decoderName (forceSoftware=$forceSoftware)")
            }
        })

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> loadingSpinner?.visibility = View.VISIBLE
                    Player.STATE_READY -> {
                        loadingSpinner?.visibility = View.GONE
                        errorText?.visibility = View.GONE
                        updateControlLabels()
                    }
                    Player.STATE_ENDED -> {
                        loadingSpinner?.visibility = View.GONE
                        currentIsPlaying = false
                        updateControlLabels()
                    }
                    else -> {}
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                currentIsPlaying = isPlaying
                updateControlLabels()
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    val ratio = (videoSize.width.toFloat() * videoSize.pixelWidthHeightRatio) / videoSize.height.toFloat()
                    if (ratio in 0.5f..2.5f && abs(ratio - videoAspectRatio) > 0.05f) {
                        videoAspectRatio = ratio
                        applyWindowLayout()
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "ExoPlayer error (forceSoftware=$forceSoftware): ${error.errorCodeName} - ${error.message}", error)
                if (!forceSoftware && !hasRetriedWithSoftware) {
                    hasRetriedWithSoftware = true
                    currentSoftwareDecode = true
                    val resumePos = player.currentPosition.coerceAtLeast(startPositionMs)
                    Log.w(TAG, "Retrying playback with software decoder at ${resumePos}ms")
                    showBriefBadge("硬解失败，已切换软解")
                    mainHandler.post {
                        startExoPlayer(forceSoftware = true, startPositionMs = resumePos)
                    }
                } else {
                    lastError = "${error.errorCodeName}: ${error.localizedMessage ?: "播放失败"}"
                    loadingSpinner?.visibility = View.GONE
                    errorText?.apply {
                        text = "小窗播放失败\n${error.errorCodeName}"
                        visibility = View.VISIBLE
                    }
                }
            }
        })

        textureView?.let { player.setVideoTextureView(it) }

        val mediaItem = buildMediaItem()
        if (mediaItem == null) {
            lastError = "无法构造播放源"
            stopSelfSafely()
            return
        }

        player.setMediaItem(mediaItem)
        if (!currentIsLive && startPositionMs > 0L) {
            player.seekTo(startPositionMs)
        }
        player.playWhenReady = true
        player.prepare()
        exoPlayer = player
        showBriefBadge(buildBadgeText())
    }

    private fun buildMediaItem(): MediaItem? {
        val mpd = currentMpdContent
        if (!mpd.isNullOrEmpty()) {
            return try {
                val file = File(cacheDir, "floating_video.mpd")
                file.writeText(mpd, Charsets.UTF_8)
                mpdTempFile = file
                MediaItem.Builder()
                    .setUri(Uri.fromFile(file))
                    .setMimeType(MimeTypes.APPLICATION_MPD)
                    .build()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write MPD cache file", e)
                null
            }
        }

        val url = currentPlayUrl ?: return null
        val builder = MediaItem.Builder().setUri(Uri.parse(url))
        val lower = url.lowercase()
        when {
            lower.contains(".mpd") -> builder.setMimeType(MimeTypes.APPLICATION_MPD)
            lower.contains(".m3u8") -> builder.setMimeType(MimeTypes.APPLICATION_M3U8)
        }
        return builder.build()
    }

    private fun togglePlayPause() {
        val p = exoPlayer ?: return
        if (p.isPlaying) {
            p.pause()
            showBriefBadge("小窗已暂停")
        } else {
            p.play()
            showBriefBadge("小窗继续播放")
        }
        updateControlLabels()
    }

    private fun toggleMute() {
        applyMute(!currentIsMuted)
        showBriefBadge(if (currentIsMuted) "小窗已静音" else "小窗已恢复声音")
    }

    private fun applyMute(muted: Boolean) {
        currentIsMuted = muted
        exoPlayer?.volume = if (muted) 0f else 1f
        updateControlLabels()
    }

    private fun cycleCorner() {
        currentCorner = when (currentCorner) {
            CORNER_BOTTOM_RIGHT -> CORNER_TOP_RIGHT
            CORNER_TOP_RIGHT -> CORNER_TOP_LEFT
            CORNER_TOP_LEFT -> CORNER_BOTTOM_LEFT
            else -> CORNER_BOTTOM_RIGHT
        }
        applyWindowLayout()
        val label = when (currentCorner) {
            CORNER_TOP_RIGHT -> "右上角"
            CORNER_TOP_LEFT -> "左上角"
            CORNER_BOTTOM_LEFT -> "左下角"
            else -> "右下角"
        }
        showBriefBadge("位置：$label")
    }

    private fun cycleSize() {
        currentSize = when (currentSize) {
            SIZE_SMALL -> SIZE_MEDIUM
            SIZE_MEDIUM -> SIZE_LARGE
            else -> SIZE_SMALL
        }
        applyWindowLayout()
        val label = when (currentSize) {
            SIZE_SMALL -> "小"
            SIZE_LARGE -> "大"
            else -> "中"
        }
        showBriefBadge("尺寸：$label")
    }

    private fun expandToApp() {
        lastPositionMs = readCurrentPositionMs()
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        }
        startActivity(intent)
    }

    private fun readCurrentPositionMs(): Long {
        val p = exoPlayer ?: return lastPositionMs
        return if (Looper.myLooper() == Looper.getMainLooper()) {
            p.currentPosition.coerceAtLeast(0L)
        } else {
            lastPositionMs
        }
    }

    private fun computeWindowSize(): Pair<Int, Int> {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager?.defaultDisplay?.getRealMetrics(metrics)
        val screenWidth = if (metrics.widthPixels > 0) metrics.widthPixels else 1920
        val screenHeight = if (metrics.heightPixels > 0) metrics.heightPixels else 1080

        val widthFraction = when (currentSize) {
            SIZE_SMALL -> 0.24f
            SIZE_LARGE -> 0.38f
            else -> 0.30f
        }
        var width = (screenWidth * widthFraction).roundToInt()
        var height = (width / videoAspectRatio).roundToInt()

        val maxHeight = (screenHeight * 0.45f).roundToInt()
        if (height > maxHeight) {
            height = maxHeight
            width = (height * videoAspectRatio).roundToInt()
        }
        return width to height
    }

    private fun applyCornerGravity(params: WindowManager.LayoutParams) {
        val margin = dpToPx(28f)
        params.gravity = when (currentCorner) {
            CORNER_TOP_RIGHT -> Gravity.TOP or Gravity.END
            CORNER_TOP_LEFT -> Gravity.TOP or Gravity.START
            CORNER_BOTTOM_LEFT -> Gravity.BOTTOM or Gravity.START
            else -> Gravity.BOTTOM or Gravity.END
        }
        params.x = margin
        params.y = margin
    }

    private fun applyWindowLayout() {
        val container = rootContainer ?: return
        val params = layoutParams ?: return
        val (w, h) = computeWindowSize()
        params.width = w
        params.height = h
        applyCornerGravity(params)
        try {
            windowManager?.updateViewLayout(container, params)
        } catch (e: Exception) {
            Log.w(TAG, "updateViewLayout failed: ${e.message}")
        }
    }

    private fun dpToPx(dp: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            resources.displayMetrics
        ).roundToInt()
    }

    private fun releasePlayerOnly() {
        exoPlayer?.let { p ->
            lastPositionMs = p.currentPosition.coerceAtLeast(0L)
            p.clearVideoTextureView(textureView)
            p.release()
        }
        exoPlayer = null
    }

    private fun stopSelfSafely() {
        mainHandler.removeCallbacks(hideControlsRunnable)
        mainHandler.removeCallbacks(positionPoller)
        releasePlayerOnly()

        rootContainer?.let { view ->
            try {
                windowManager?.removeViewImmediate(view)
            } catch (_: Exception) {
            }
        }
        rootContainer = null
        textureView = null
        loadingSpinner = null
        statusBadge = null
        errorText = null
        controlBar = null
        playPauseBtn = null
        muteBtn = null
        layoutParams = null

        try {
            mpdTempFile?.delete()
        } catch (_: Exception) {
        }
        mpdTempFile = null

        isRunning = false
        currentIsPlaying = false
        activeInstance = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onDestroy() {
        stopSelfSafely()
        super.onDestroy()
    }

    /**
     * 软解优先的 MediaCodecSelector：
     * 对 video 媒体类型优先把软件解码器（OMX.google / c2.android / !hardwareAccelerated）
     * 排在最前面，硬解排在后面作为兜底，从而在 Sony Z9F 等电视上安全与 HDMI 全屏 Sideband 共存。
     */
    private class SoftwarePreferredCodecSelector : MediaCodecSelector {
        override fun getDecoderInfos(
            mimeType: String,
            requiresSecureDecoder: Boolean,
            requiresTunnelingDecoder: Boolean
        ): MutableList<MediaCodecInfo> {
            val all = MediaCodecUtil.getDecoderInfos(
                mimeType,
                requiresSecureDecoder,
                requiresTunnelingDecoder
            )
            if (!mimeType.startsWith("video/") || all.size <= 1) {
                return all.toMutableList()
            }
            val software = mutableListOf<MediaCodecInfo>()
            val hardware = mutableListOf<MediaCodecInfo>()
            for (info in all) {
                val lower = info.name.lowercase()
                val isSw = !info.hardwareAccelerated ||
                    lower.startsWith("omx.google.") ||
                    lower.startsWith("c2.android.") ||
                    lower.contains(".sw.") ||
                    lower.contains("software")
                if (isSw) {
                    software.add(info)
                } else {
                    hardware.add(info)
                }
            }
            val ordered = mutableListOf<MediaCodecInfo>()
            ordered.addAll(software)
            ordered.addAll(hardware)
            Log.i(
                TAG,
                "Codec order for $mimeType: ${ordered.joinToString { it.name }}"
            )
            return ordered
        }
    }
}
