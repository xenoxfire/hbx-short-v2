import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:firebase_core/firebase_core.dart';
import 'package:firebase_messaging/firebase_messaging.dart';
import 'package:flutter_local_notifications/flutter_local_notifications.dart';
import 'package:webview_flutter/webview_flutter.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:shared_preferences/shared_preferences.dart';

const String kWebAppUrl = 'https://xenox-short-production.up.railway.app';
const String kOverlayChannel = 'com.hbx.shortapp/overlay';
const String kBridgeChannel  = 'com.hbx.shortapp/bridge';

final FlutterLocalNotificationsPlugin _notifPlugin = FlutterLocalNotificationsPlugin();

@pragma('vm:entry-point')
Future<void> _firebaseBackgroundHandler(RemoteMessage message) async {
  await Firebase.initializeApp();
  _showLocalNotification(message.notification?.title ?? 'HBX Short',
      message.notification?.body ?? '');
}

Future<void> _showLocalNotification(String title, String body) async {
  const android = AndroidNotificationDetails(
    'hbx_high_priority', 'HBX Notifications',
    channelDescription: 'HBX Short push notifications',
    importance: Importance.max,
    priority: Priority.high,
    playSound: true,
    enableVibration: true,
    fullScreenIntent: false,
    styleInformation: BigTextStyleInformation(''),
  );
  await _notifPlugin.show(
    DateTime.now().millisecondsSinceEpoch ~/ 1000,
    title, body,
    const NotificationDetails(android: android),
  );
}

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await Firebase.initializeApp();
  FirebaseMessaging.onBackgroundMessage(_firebaseBackgroundHandler);

  // Notification channel setup
  const channel = AndroidNotificationChannel(
    'hbx_high_priority', 'HBX Notifications',
    description: 'HBX Short push notifications',
    importance: Importance.max,
    playSound: true,
    enableVibration: true,
  );
  await _notifPlugin.resolvePlatformSpecificImplementation<
      AndroidFlutterLocalNotificationsPlugin>()?.createNotificationChannel(channel);

  const initSettings = InitializationSettings(
    android: AndroidInitializationSettings('@mipmap/ic_launcher'),
  );
  await _notifPlugin.initialize(initSettings);

  // Request notification permission
  await FirebaseMessaging.instance.requestPermission(
    alert: true, badge: true, sound: true,
  );

  SystemChrome.setSystemUIOverlayStyle(const SystemUiOverlayStyle(
    statusBarColor: Colors.transparent,
    statusBarIconBrightness: Brightness.light,
  ));

  runApp(const HBXShortApp());
}

class HBXShortApp extends StatelessWidget {
  const HBXShortApp({super.key});
  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'HBX Short',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        colorScheme: const ColorScheme.dark(),
        scaffoldBackgroundColor: Colors.black,
        useMaterial3: true,
      ),
      home: const SplashScreen(),
    );
  }
}

// ── Splash Screen ────────────────────────────────────────────────
class SplashScreen extends StatefulWidget {
  const SplashScreen({super.key});
  @override
  State<SplashScreen> createState() => _SplashScreenState();
}

class _SplashScreenState extends State<SplashScreen>
    with SingleTickerProviderStateMixin {
  late AnimationController _ctrl;
  late Animation<double> _fade;

  @override
  void initState() {
    super.initState();
    _ctrl = AnimationController(vsync: this, duration: const Duration(milliseconds: 700));
    _fade = CurvedAnimation(parent: _ctrl, curve: Curves.easeIn);
    _ctrl.forward();
    Future.delayed(const Duration(milliseconds: 2000), () {
      if (mounted) {
        Navigator.of(context).pushReplacement(
          PageRouteBuilder(
            pageBuilder: (_, __, ___) => const WebAppScreen(),
            transitionsBuilder: (_, anim, __, child) =>
                FadeTransition(opacity: anim, child: child),
            transitionDuration: const Duration(milliseconds: 400),
          ),
        );
      }
    });
  }

  @override
  void dispose() { _ctrl.dispose(); super.dispose(); }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.black,
      body: Center(
        child: FadeTransition(
          opacity: _fade,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              ClipRRect(
                borderRadius: BorderRadius.circular(24),
                child: Image.asset('assets/launcher_icon.png',
                    width: 100, height: 100, fit: BoxFit.cover),
              ),
              const SizedBox(height: 20),
              const Text(
                'HBX Short',
                style: TextStyle(
                  color: Colors.white,
                  fontSize: 28,
                  fontWeight: FontWeight.w900,
                  letterSpacing: 1.5,
                ),
              ),
              const SizedBox(height: 8),
              Text(
                'Float Sheet Manager',
                style: TextStyle(
                  color: Colors.white.withOpacity(0.4),
                  fontSize: 13,
                  letterSpacing: 0.5,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

// ── Main WebView Screen ──────────────────────────────────────────
class WebAppScreen extends StatefulWidget {
  const WebAppScreen({super.key});
  @override
  State<WebAppScreen> createState() => _WebAppScreenState();
}

class _WebAppScreenState extends State<WebAppScreen> {
  late final WebViewController _webCtrl;
  final _overlayChannel = const MethodChannel(kOverlayChannel);
  bool _loading = true;

  // JS Bridge injection — creates window.XenoxAndroid in the WebView
  static const String _jsBridge = r'''
(function() {
  if (window.XenoxAndroid) return;
  window.XenoxAndroid = {
    _overlayGranted: false,
    _bubbleRunning: false,
    hasOverlayPermission: function() { return this._overlayGranted; },
    requestOverlayPermission: function() {
      XenoxBridge.postMessage(JSON.stringify({action:'requestOverlayPermission'}));
    },
    startBubble: function() {
      XenoxBridge.postMessage(JSON.stringify({action:'startBubble'}));
      return this._overlayGranted;
    },
    stopBubble: function() {
      XenoxBridge.postMessage(JSON.stringify({action:'stopBubble'}));
      this._bubbleRunning = false;
    },
    isBubbleRunning: function() { return this._bubbleRunning; },
    showHeadsUpNotification: function(title, msg) {
      XenoxBridge.postMessage(JSON.stringify({action:'showNotification',title:title,message:msg}));
    },
    readClipboard: function() {
      XenoxBridge.postMessage(JSON.stringify({action:'readClipboard'}));
      return null;
    },
    _updateState: function(granted, running) {
      this._overlayGranted = granted;
      this._bubbleRunning = running;
    }
  };
  console.log('[HBX] XenoxAndroid bridge injected');
})();
''';

  @override
  void initState() {
    super.initState();
    _initWebView();
    _initFcm();
  }

  void _initFcm() {
    FirebaseMessaging.onMessage.listen((RemoteMessage msg) {
      final notif = msg.notification;
      if (notif != null) {
        _showLocalNotification(notif.title ?? 'HBX Short', notif.body ?? '');
      }
    });
    FirebaseMessaging.instance.getToken().then((token) {
      if (token != null) {
        _webCtrl.runJavaScript(
          "window.dispatchEvent(new CustomEvent('fcmToken', {detail:'$token'}));");
      }
    });
  }

  void _initWebView() {
    _webCtrl = WebViewController()
      ..setJavaScriptMode(JavaScriptMode.unrestricted)
      ..setBackgroundColor(Colors.black)
      ..enableZoom(false)
      ..setUserAgent(
          'Mozilla/5.0 (Linux; Android 13; HBX Short) AppleWebKit/537.36 '
          '(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36 HBXShortApp/1.0')
      ..setNavigationDelegate(NavigationDelegate(
        onPageStarted: (_) => setState(() => _loading = true),
        onPageFinished: (_) async {
          await _webCtrl.runJavaScript(_jsBridge);
          await _updateBridgeState();
          setState(() => _loading = false);
        },
        onWebResourceError: (err) {
          debugPrint('[HBX WebView Error] ${err.description}');
        },
      ))
      ..addJavaScriptChannel('XenoxBridge',
          onMessageReceived: (JavaScriptMessage msg) {
        _handleBridgeMessage(msg.message);
      })
      ..loadRequest(Uri.parse(kWebAppUrl));
  }

  Future<void> _updateBridgeState() async {
    try {
      final granted = await _overlayChannel.invokeMethod<bool>('hasOverlayPermission') ?? false;
      final running = await _overlayChannel.invokeMethod<bool>('isBubbleRunning') ?? false;
      await _webCtrl.runJavaScript(
          'if(window.XenoxAndroid) window.XenoxAndroid._updateState($granted, $running);');
    } catch (_) {}
  }

  Future<void> _handleBridgeMessage(String raw) async {
    try {
      final map = _parseJson(raw);
      final action = map['action'] as String? ?? '';

      switch (action) {
        case 'requestOverlayPermission':
          await _overlayChannel.invokeMethod('requestOverlayPermission');
          await Future.delayed(const Duration(seconds: 1));
          await _updateBridgeState();

        case 'startBubble':
          // Read config from WebView localStorage and pass to service
          final configJson = await _webCtrl.runJavaScriptReturningResult(
              "localStorage.getItem('xenox_float_config') || '{}'");
          final colRowsJson = await _webCtrl.runJavaScriptReturningResult(
              "localStorage.getItem('xenox_col_rows') || '{}'");
          // Clean JS string quotes
          final config = _cleanJsString(configJson.toString());
          final colRows = _cleanJsString(colRowsJson.toString());

          final prefs = await SharedPreferences.getInstance();
          await prefs.setString('xenox_float_config', config);
          await prefs.setString('xenox_col_rows', colRows);

          await _overlayChannel.invokeMethod('startBubble', {
            'config': config,
          });
          await _updateBridgeState();

        case 'stopBubble':
          await _overlayChannel.invokeMethod('stopBubble');
          await _updateBridgeState();

        case 'showNotification':
          final title = map['title'] as String? ?? 'HBX Short';
          final body = map['message'] as String? ?? '';
          await _showLocalNotification(title, body);

        case 'readClipboard':
          final text = await _overlayChannel.invokeMethod<String>('readClipboard') ?? '';
          final escaped = text.replaceAll("'", r"\'").replaceAll('\n', r'\n');
          await _webCtrl.runJavaScript(
            "window.dispatchEvent(new CustomEvent('xenox:clipboard', {detail:'$escaped'}));");
      }
    } catch (e) {
      debugPrint('[HBX Bridge Error] $e');
    }
  }

  String _cleanJsString(String s) {
    if (s.startsWith('"') && s.endsWith('"')) {
      return s.substring(1, s.length - 1)
          .replaceAll(r'\"', '"')
          .replaceAll(r'\n', '\n');
    }
    return s;
  }

  Map<String, dynamic> _parseJson(String s) {
    try {
      // Simple manual parse for known structure
      final entries = <String, dynamic>{};
      final cleaned = s.trim();
      if (!cleaned.startsWith('{')) return {};
      final inner = cleaned.substring(1, cleaned.length - 1);
      final pairs = inner.split(',');
      for (final pair in pairs) {
        final idx = pair.indexOf(':');
        if (idx < 0) continue;
        final key = pair.substring(0, idx).trim().replaceAll('"', '').replaceAll("'", '');
        final val = pair.substring(idx + 1).trim().replaceAll('"', '').replaceAll("'", '');
        entries[key] = val;
      }
      return entries;
    } catch (_) { return {}; }
  }

  Future<bool> _onWillPop() async {
    if (await _webCtrl.canGoBack()) {
      _webCtrl.goBack();
      return false;
    }
    return true;
  }

  @override
  Widget build(BuildContext context) {
    return WillPopScope(
      onWillPop: _onWillPop,
      child: Scaffold(
        backgroundColor: Colors.black,
        body: Stack(
          children: [
            WebViewWidget(controller: _webCtrl),
            if (_loading)
              Container(
                color: Colors.black,
                child: Center(
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      ClipRRect(
                        borderRadius: BorderRadius.circular(18),
                        child: Image.asset('assets/launcher_icon.png',
                            width: 64, height: 64),
                      ),
                      const SizedBox(height: 16),
                      SizedBox(
                        width: 28, height: 28,
                        child: CircularProgressIndicator(
                          strokeWidth: 2.5,
                          valueColor: AlwaysStoppedAnimation<Color>(
                              Colors.blue.shade400),
                        ),
                      ),
                    ],
                  ),
                ),
              ),
          ],
        ),
      ),
    );
  }
}
