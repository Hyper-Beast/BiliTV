package com.bili.tv.bili_tv_app

import android.content.Intent
import android.media.MediaCodecList
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.io.File

class MainActivity : FlutterActivity() {
    private val UPDATE_CHANNEL = "com.bili.tv/update"
    private val CODEC_CHANNEL = "com.bili.tv/codec"
    private val FLOATING_CHANNEL = "com.bili.tv/floating_player"

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        
        // 更新安装 Channel
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, UPDATE_CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "installApk" -> {
                    val path = call.argument<String>("path")
                    if (path != null) {
                        try {
                            installApk(path)
                            result.success(true)
                        } catch (e: Exception) {
                            result.error("INSTALL_ERROR", e.message, null)
                        }
                    } else {
                        result.error("INVALID_PATH", "APK path is null", null)
                    }
                }
                else -> result.notImplemented()
            }
        }
        
        // 编码器检测 Channel
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CODEC_CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "getHardwareDecoders" -> {
                    try {
                        val hwCodecs = getHardwareDecoders()
                        result.success(hwCodecs)
                    } catch (e: Exception) {
                        result.error("CODEC_ERROR", e.message, null)
                    }
                }
                else -> result.notImplemented()
            }
        }

        // 悬浮小窗播放器 Channel
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, FLOATING_CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "hasOverlayPermission" -> {
                    val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                        Settings.canDrawOverlays(this)
                    result.success(granted)
                }
                "requestOverlayPermission" -> {
                    result.success(requestOverlayPermission())
                }
                "startFloatingPlayer" -> {
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                            result.success(mapOf("started" to false, "reason" to "NO_PERMISSION"))
                            return@setMethodCallHandler
                        }
                        val intent = Intent(this, FloatingPlayerService::class.java).apply {
                            action = FloatingPlayerService.ACTION_START
                            putExtra(FloatingPlayerService.EXTRA_URL, call.argument<String>("url"))
                            putExtra(FloatingPlayerService.EXTRA_MPD_CONTENT, call.argument<String>("mpdContent"))
                            putExtra(FloatingPlayerService.EXTRA_POSITION_MS, (call.argument<Number>("positionMs")?.toLong() ?: 0L))
                            putExtra(FloatingPlayerService.EXTRA_PLAYBACK_SPEED, (call.argument<Number>("playbackSpeed")?.toFloat() ?: 1.0f))
                            putExtra(FloatingPlayerService.EXTRA_TITLE, call.argument<String>("title") ?: "")
                            putExtra(FloatingPlayerService.EXTRA_BVID, call.argument<String>("bvid"))
                            putExtra(FloatingPlayerService.EXTRA_CID, call.argument<Int>("cid") ?: 0)
                            putExtra(FloatingPlayerService.EXTRA_ROOM_ID, call.argument<Int>("roomId") ?: 0)
                            putExtra(FloatingPlayerService.EXTRA_IS_LIVE, call.argument<Boolean>("isLive") ?: false)
                            putExtra(FloatingPlayerService.EXTRA_CORNER, call.argument<String>("corner") ?: FloatingPlayerService.CORNER_BOTTOM_RIGHT)
                            putExtra(FloatingPlayerService.EXTRA_SIZE, call.argument<String>("size") ?: FloatingPlayerService.SIZE_MEDIUM)
                            putExtra(FloatingPlayerService.EXTRA_MUTED, call.argument<Boolean>("muted") ?: false)
                            putExtra(FloatingPlayerService.EXTRA_SOFTWARE_DECODE, call.argument<Boolean>("softwareDecode") ?: true)

                            val headers = call.argument<Map<String, String>>("headers")
                            if (headers != null && headers.isNotEmpty()) {
                                putStringArrayListExtra(FloatingPlayerService.EXTRA_HEADERS_KEYS, ArrayList(headers.keys))
                                putStringArrayListExtra(FloatingPlayerService.EXTRA_HEADERS_VALUES, ArrayList(headers.values))
                            }
                        }

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(intent)
                        } else {
                            startService(intent)
                        }

                        val moveToBack = call.argument<Boolean>("moveToBack") ?: true
                        if (moveToBack) {
                            moveTaskToBack(true)
                        }
                        result.success(mapOf("started" to true))
                    } catch (e: Exception) {
                        result.error("FLOATING_START_ERROR", e.message, null)
                    }
                }
                "stopFloatingPlayer" -> {
                    val pos = FloatingPlayerService.stopAndGetPositionMs(this)
                    result.success(mapOf("stopped" to true, "positionMs" to pos))
                }
                "getFloatingState" -> {
                    result.success(FloatingPlayerService.getStateMap())
                }
                "updateFloatingConfig" -> {
                    if (FloatingPlayerService.isRunning) {
                        val intent = Intent(this, FloatingPlayerService::class.java).apply {
                            action = FloatingPlayerService.ACTION_UPDATE_CONFIG
                            call.argument<String>("corner")?.let { putExtra(FloatingPlayerService.EXTRA_CORNER, it) }
                            call.argument<String>("size")?.let { putExtra(FloatingPlayerService.EXTRA_SIZE, it) }
                            call.argument<Boolean>("muted")?.let { putExtra(FloatingPlayerService.EXTRA_MUTED, it) }
                        }
                        startService(intent)
                    }
                    result.success(true)
                }
                else -> result.notImplemented()
            }
        }
    }

    private fun requestOverlayPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        return try {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }
    
    // 获取硬件解码器支持的格式
    private fun getHardwareDecoders(): List<String> {
        val supportedFormats = mutableSetOf<String>()
        val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
        
        for (info in codecList.codecInfos) {
            // 只检查解码器，跳过编码器
            if (info.isEncoder) continue
            
            // 检查是否是硬件解码器 (不包含 google/software)
            val name = info.name.lowercase()
            val isHardware = !name.contains("google") && 
                            !name.contains("software") &&
                            !name.contains("sw") &&
                            !name.startsWith("c2.android")
            
            if (isHardware) {
                for (type in info.supportedTypes) {
                    when {
                        type.equals("video/avc", ignoreCase = true) -> supportedFormats.add("avc")
                        type.equals("video/hevc", ignoreCase = true) -> supportedFormats.add("hevc")
                        type.equals("video/av01", ignoreCase = true) -> supportedFormats.add("av1")
                        type.equals("video/x-vnd.on2.vp9", ignoreCase = true) -> supportedFormats.add("vp9")
                    }
                }
            }
        }
        
        return supportedFormats.toList()
    }

    private fun installApk(path: String) {
        val file = File(path)
        if (!file.exists()) {
            throw Exception("APK file not found: $path")
        }

        val intent = Intent(Intent.ACTION_VIEW)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // Android 7.0+ 使用 FileProvider
            val uri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                file
            )
            intent.setDataAndType(uri, "application/vnd.android.package-archive")
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else {
            // 旧版本直接使用文件路径
            intent.setDataAndType(
                android.net.Uri.fromFile(file),
                "application/vnd.android.package-archive"
            )
        }

        startActivity(intent)
    }
}
