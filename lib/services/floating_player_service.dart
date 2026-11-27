import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

/// 悬浮小窗位置枚举
enum FloatingWindowCorner {
  bottomRight('bottom_right', '右下角'),
  topRight('top_right', '右上角'),
  bottomLeft('bottom_left', '左下角'),
  topLeft('top_left', '左上角');

  final String key;
  final String label;
  const FloatingWindowCorner(this.key, this.label);

  static FloatingWindowCorner fromKey(String? key) {
    for (final v in values) {
      if (v.key == key) return v;
    }
    return FloatingWindowCorner.bottomRight;
  }
}

/// 悬浮小窗尺寸枚举
enum FloatingWindowSize {
  small('small', '小'),
  medium('medium', '中'),
  large('large', '大');

  final String key;
  final String label;
  const FloatingWindowSize(this.key, this.label);

  static FloatingWindowSize fromKey(String? key) {
    for (final v in values) {
      if (v.key == key) return v;
    }
    return FloatingWindowSize.medium;
  }
}

/// 原生悬浮小窗播放器桥接服务
class FloatingPlayerService {
  static const _channel = MethodChannel('com.bili.tv/floating_player');

  /// 检查是否已授予悬浮窗权限 (SYSTEM_ALERT_WINDOW)
  static Future<bool> hasOverlayPermission() async {
    try {
      final result = await _channel.invokeMethod<bool>('hasOverlayPermission');
      return result ?? false;
    } catch (e) {
      debugPrint('FloatingPlayerService.hasOverlayPermission error: $e');
      return false;
    }
  }

  /// 打开系统悬浮窗授权页（部分 TV ROM 可能无此页面，返回 false）
  static Future<bool> requestOverlayPermission() async {
    try {
      final result = await _channel.invokeMethod<bool>(
        'requestOverlayPermission',
      );
      return result ?? false;
    } catch (e) {
      debugPrint('FloatingPlayerService.requestOverlayPermission error: $e');
      return false;
    }
  }

  /// 启动悬浮小窗播放
  static Future<Map<String, dynamic>> start({
    String? url,
    String? mpdContent,
    Map<String, String>? headers,
    int positionMs = 0,
    double playbackSpeed = 1.0,
    String title = '',
    String? bvid,
    int cid = 0,
    int roomId = 0,
    bool isLive = false,
    FloatingWindowCorner corner = FloatingWindowCorner.bottomRight,
    FloatingWindowSize size = FloatingWindowSize.medium,
    bool muted = false,
    bool softwareDecode = true,
    bool moveToBack = true,
  }) async {
    try {
      final result = await _channel.invokeMethod<Map>('startFloatingPlayer', {
        'url': url,
        'mpdContent': mpdContent,
        'headers': headers ?? const <String, String>{},
        'positionMs': positionMs,
        'playbackSpeed': playbackSpeed,
        'title': title,
        'bvid': bvid,
        'cid': cid,
        'roomId': roomId,
        'isLive': isLive,
        'corner': corner.key,
        'size': size.key,
        'muted': muted,
        'softwareDecode': softwareDecode,
        'moveToBack': moveToBack,
      });
      return Map<String, dynamic>.from(result ?? const {});
    } catch (e) {
      debugPrint('FloatingPlayerService.start error: $e');
      return {'started': false, 'reason': e.toString()};
    }
  }

  /// 停止悬浮小窗播放，返回最后播放进度 (ms)
  static Future<int> stop() async {
    try {
      final result = await _channel.invokeMethod<Map>('stopFloatingPlayer');
      final pos = result?['positionMs'];
      if (pos is int) return pos;
      if (pos is num) return pos.toInt();
      return 0;
    } catch (e) {
      debugPrint('FloatingPlayerService.stop error: $e');
      return 0;
    }
  }

  /// 查询悬浮小窗当前状态
  static Future<Map<String, dynamic>> getState() async {
    try {
      final result = await _channel.invokeMethod<Map>('getFloatingState');
      return Map<String, dynamic>.from(result ?? const {});
    } catch (e) {
      debugPrint('FloatingPlayerService.getState error: $e');
      return const {'isRunning': false};
    }
  }

  /// 实时更新运行中的悬浮小窗配置
  static Future<void> updateConfig({
    FloatingWindowCorner? corner,
    FloatingWindowSize? size,
    bool? muted,
  }) async {
    try {
      await _channel.invokeMethod('updateFloatingConfig', {
        if (corner != null) 'corner': corner.key,
        if (size != null) 'size': size.key,
        if (muted != null) 'muted': muted,
      });
    } catch (e) {
      debugPrint('FloatingPlayerService.updateConfig error: $e');
    }
  }
}
