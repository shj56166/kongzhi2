package com.kongzhi2.myapplication // 确保包名和你的项目一致

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowInsetsController
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.activity.OnBackPressedCallback

class MainActivity : AppCompatActivity() {
    companion object {
        private const val PREFS_NAME = "TimerPrefs"
        private const val PREFS_KEY_AUTO_CONNECT = "AutoConnectEnabled"
    }

    private lateinit var webView: WebView
    private var bluetoothService: BluetoothService? = null
    private var isBound = false

    private lateinit var requestPermissionsLauncher: ActivityResultLauncher<Array<String>>

    private lateinit var localBroadcastManager: LocalBroadcastManager

    private val bluetoothEnableLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            Log.d("MainActivity", "Bluetooth has been enabled by user.")
            checkAndRequestRuntimePermissions()
        } else {
            Log.w("MainActivity", "User denied enabling Bluetooth.")
            Toast.makeText(this, "需要开启蓝牙才能搜索设备", Toast.LENGTH_LONG).show()
        }
    }


    // 服务连接对象
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as BluetoothService.LocalBinder
            bluetoothService = binder.getService()
            isBound = true
            Log.d("MainActivity", "BluetoothService connected.")
            bluetoothService?.requestFullStatusUpdate()
            tryAutoConnectLastDevice()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            bluetoothService = null
            Log.d("MainActivity", "BluetoothService disconnected.")
        }
    }

    // 广播接收器
    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothService.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothService.EXTRA_STATE, BluetoothService.STATE_DISCONNECTED)
                    val deviceName = intent.getStringExtra(BluetoothService.EXTRA_DEVICE_NAME) ?: "未连接"
                    // 直接从广播中获取地址，如果广播中没有，则为空字符串
                    val deviceAddress = intent.getStringExtra(BluetoothService.EXTRA_DEVICE_ADDRESS) ?: ""
                    runJs("javascript:window.onConnectionStateChange($state, '$deviceName', '$deviceAddress');")
                }
                BluetoothService.ACTION_DATA_RECEIVED -> {
                    val data = intent.getStringExtra(BluetoothService.EXTRA_DATA)
                    data?.let { sendJsData(it) }
                }
                BluetoothService.ACTION_TIMER_UPDATE -> {
                    val timersJson = intent.getStringExtra(BluetoothService.EXTRA_TIMERS_JSON)
                    timersJson?.let { sendJsTimerUpdate(it) }
                }
                BluetoothService.ACTION_ERROR -> {
                    val errorMessage = intent.getStringExtra(BluetoothService.EXTRA_ERROR_MESSAGE) ?: "未知错误"
                    sendJsError(errorMessage)
                }
                BluetoothService.ACTION_DEVICE_FOUND -> {
                    val deviceName = intent.getStringExtra(BluetoothService.EXTRA_DEVICE_NAME) ?: "未知设备"
                    val deviceAddress = intent.getStringExtra(BluetoothService.EXTRA_DEVICE_ADDRESS) ?: return
                    val deviceType = intent.getIntExtra(BluetoothService.EXTRA_DEVICE_TYPE, 0)
                    sendJsDeviceFound(deviceName, deviceAddress, deviceType)
                }
                // [新增] 处理新的广播
                BluetoothService.ACTION_DISCOVERY_STARTED -> {
                    Log.d("MainActivity", "Received DISCOVERY_STARTED, calling onDiscoveryStarted()")
                    runJs("javascript:window.onDiscoveryStarted();")
                }
                BluetoothService.ACTION_DISCOVERY_FINISHED -> {
                    Log.d("MainActivity", "Received DISCOVERY_FINISHED, calling onDiscoveryFinished()")
                    runJs("javascript:window.onDiscoveryFinished();")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // [升级] 返回键拦截逻辑
        val onBackPressedCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // 优先级最高：检查WebView自身是否可以返回（例如加载了外部网页）
                if (webView.canGoBack()) {
                    webView.goBack() // 如果可以，就执行WebView的返回操作
                } else {
                    // 如果WebView不能返回，再询问JS是否有内部页面或弹窗需要处理
                    webView.evaluateJavascript("window.handleAndroidBackButton();") { result ->
                        if (result != "true") {
                            finish() // 如果JS说没啥可处理的，就退出App
                        }
                    }
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, onBackPressedCallback)

        localBroadcastManager = LocalBroadcastManager.getInstance(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.setSystemBarsAppearance(
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
            )
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = window.decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }

        setupPermissionsLauncher()
        setupWebView()
        registerAppReceiver()
    }

    override fun onResume() {
        super.onResume()
        // 当用户从其他页面（如系统设置页）返回App时，通知JS刷新权限弹窗
        runJs("javascript:if(document.getElementById('permissionModal').classList.contains('show')) { window.onPermissionRequestCompleted(); }")
    }

    // [最终修复] 权限回调函数
    private fun setupPermissionsLauncher() {
        requestPermissionsLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            // 步骤 1：无论用户是同意还是拒绝，都先通知前端刷新UI，让用户看到状态变化
            Log.d("MainActivity", "Permission result received. Notifying JS to refresh UI.")
            runJs("javascript:window.onPermissionRequestCompleted();")

            // 步骤 2：检查核心的蓝牙相关权限是否真的被授予了
            val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                // 对于旧版安卓，ACCESS_FINE_LOCATION 是扫描的核心
                listOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }

            val allRequiredGranted = requiredPermissions.all { permissions[it] == true }

            // 步骤 3：如果核心权限被授予，则立即启动并绑定服务
            if (allRequiredGranted) {
                Log.d("MainActivity", "Core permissions have been granted. Starting and binding service now.")
                // 这是之前流程中所缺失的关键一步！
                startAndBindService()
            } else {
                Log.w("MainActivity", "Core permissions were not granted after request.")
            }
        }
    }

    private fun startPermissionCheckFlow() {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter = bluetoothManager.adapter

        if (bluetoothAdapter == null) {
            Toast.makeText(this, "该设备不支持蓝牙", Toast.LENGTH_LONG).show()
            return
        }

        if (!bluetoothAdapter.isEnabled) {
            Log.d("MainActivity", "Bluetooth is disabled. Requesting to enable.")
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            bluetoothEnableLauncher.launch(enableBtIntent)
        } else {
            Log.d("MainActivity", "Bluetooth is already enabled. Checking runtime permissions.")
            checkAndRequestRuntimePermissions()
        }
    }

    private fun checkAndRequestRuntimePermissions() {
        // [核心修复] 根据安卓版本动态构建所需权限列表
        val requiredPermissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12 (API 31) 及以上版本
            requiredPermissions.add(Manifest.permission.BLUETOOTH_SCAN)
            requiredPermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            requiredPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            // Android 11 (API 30) 及以下版本
            requiredPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        val permissionsToRequest = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (permissionsToRequest.isEmpty()) {
            Log.d("MainActivity", "All required permissions are already granted.")
            if (!isLocationEnabled()) {
                // 即使权限足够，在旧版本安卓上仍需检查定位服务是否开启
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                    showLocationDisabledDialog()
                } else {
                    startAndBindService()
                }
            } else {
                startAndBindService()
            }
        } else {
            Log.d("MainActivity", "Requesting missing permissions: ${permissionsToRequest.joinToString()}")
            requestPermissionsLauncher.launch(permissionsToRequest)
        }
    }

    private fun isLocationEnabled(): Boolean {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    private fun showLocationDisabledDialog() {
        AlertDialog.Builder(this)
            .setTitle("需要开启定位服务")
            .setMessage("为了扫描到蓝牙设备，安卓系统要求应用开启位置信息服务。请在系统设置中开启它。")
            .setPositiveButton("去设置") { _, _ ->
                val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                startActivity(intent)
            }
            .setNegativeButton("取消") { dialog, _ ->
                dialog.dismiss()
                Toast.makeText(this, "未开启定位服务，可能无法发现设备", Toast.LENGTH_LONG).show()
            }
            .show()
    }


    private fun showPermissionDeniedDialog() {
        AlertDialog.Builder(this)
            .setTitle("权限被拒绝")
            .setMessage("应用需要'定位'和'附近的设备'权限才能扫描和连接蓝牙设备。请在系统设置中手动开启。")
            .setPositiveButton("去设置") { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                val uri = Uri.fromParts("package", packageName, null)
                intent.data = uri
                startActivity(intent)
            }
            .setNegativeButton("取消", null)
            .show()
    }


    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun setupWebView() {
        // [核心调试步骤 1] 开启 WebView 远程调试
        WebView.setWebContentsDebuggingEnabled(true)

        webView = findViewById(R.id.webView)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true

        // [核心调试步骤 2] 确保文件访问权限开启
        webView.settings.allowFileAccess = true

        // --- WebViewAssetLoader 的代码保持不变 ---
        // MainActivity.kt -> setupWebView()
        val assetLoader = WebViewAssetLoader.Builder()
            .setDomain("appassets.androidplatform.net")
            // [修复] 将路径处理器改为根路径 "/"
            .addPathHandler("/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView.webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                return assetLoader.shouldInterceptRequest(request.url)
            }
        }

        // --- webChromeClient 和 addJavascriptInterface 保持不变 ---
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                Log.d("WebViewConsole", "${consoleMessage.message()} -- From line ${consoleMessage.lineNumber()} of ${consoleMessage.sourceId()}")
                return true
            }
        }
        webView.addJavascriptInterface(WebAppInterface(), "BTBridge")

        webView.loadUrl("https://appassets.androidplatform.net/index.html")
    }

    private fun startAndBindService() {
        if (isBound) return
        val serviceIntent = Intent(this, BluetoothService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun isAutoConnectEnabled(): Boolean {
        return bluetoothService?.isAutoConnectEnabled()
            ?: getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(PREFS_KEY_AUTO_CONNECT, false)
    }

    private fun setAutoConnectEnabled(enabled: Boolean) {
        bluetoothService?.setAutoConnectEnabled(enabled)
            ?: getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(PREFS_KEY_AUTO_CONNECT, enabled)
                .apply()
    }

    private fun tryAutoConnectLastDevice() {
        val service = bluetoothService ?: return
        if (!isAutoConnectEnabled()) return

        val lastAddress = service.getLastConnectedDeviceAddress()
        if (lastAddress.isNullOrBlank()) return

        Log.d("MainActivity", "Trying to auto connect last device: $lastAddress")
        service.connect(lastAddress)
    }

    private fun registerAppReceiver() {
        val intentFilter = IntentFilter().apply {
            addAction(BluetoothService.ACTION_STATE_CHANGED)
            addAction(BluetoothService.ACTION_DATA_RECEIVED)
            addAction(BluetoothService.ACTION_ERROR)
            addAction(BluetoothService.ACTION_TIMER_UPDATE)
            addAction(BluetoothService.ACTION_DEVICE_FOUND)
            // [新增] 注册新的 Action
            addAction(BluetoothService.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothService.ACTION_DISCOVERY_FINISHED)
        }
        localBroadcastManager.registerReceiver(broadcastReceiver, intentFilter)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        localBroadcastManager.unregisterReceiver(broadcastReceiver)
    }

    // JS 接口
    inner class WebAppInterface {
        // [新增] 用于处理前端单个权限申请的智能接口
        @JavascriptInterface
        fun requestSinglePermission(permissionKey: String) {
            runOnUiThread {
                when (permissionKey) {
                    // 必要权限：蓝牙
                    "bluetooth" -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            requestPermissionsLauncher.launch(arrayOf(
                                Manifest.permission.BLUETOOTH_SCAN,
                                Manifest.permission.BLUETOOTH_CONNECT
                            ))
                        }
                    }
                    // 必要权限：通知
                    "notifications" -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            requestPermissionsLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                        }
                    }
                    // 可选权限：位置信息 (旧版安卓)
                    "location" -> {
                        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) {
                            requestPermissionsLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
                        }
                    }
                    // 可选权限：精确闹钟
                    "alarm" -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            // 对于闹钟权限，不是直接申请，而是跳转到系统设置页面
                            Intent().also { intent ->
                                intent.action = Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM
                                startActivity(intent)
                            }
                        }
                    }
                }
            }
        }

        // [新增] 用于触发多种振动反馈的接口
        @JavascriptInterface
        fun performHapticFeedback(type: String) {
            runOnUiThread {
                // [修改] 将JS传来的字符串转换为我们定义的枚举类型
                val vibrationType = when(type) {
                    "double" -> VibrationType.TASK_SET_OFF
                    "triple" -> VibrationType.TASK_SET_ON
                    "thud" -> VibrationType.IMPACT_THUD          // [新增] 调用闷响效果
                    "bass_thump" -> VibrationType.CUSTOM_BASS_THUMP // [新增] 调用自定义重低音
                    else -> VibrationType.SINGLE_CLICK
                }
                HapticFeedbackManager.vibrate(this@MainActivity, vibrationType)
            }
        }

        // [新增] 用于触发连续“咔哒”声振动的接口
        @JavascriptInterface
        fun performHapticTicks(tickCount: Int) {
            runOnUiThread {
                HapticFeedbackManager.vibrateTicks(this@MainActivity, tickCount)
            }
        }

// ... WebAppInterface 内部的其他函数 ...

        @JavascriptInterface
        fun startScan() {
            runOnUiThread {
                Log.d("WebAppInterface", "startScan called from JS")
                bluetoothService?.startDiscovery()
            }
        }

        @JavascriptInterface
        fun stopScan() {
            runOnUiThread {
                Log.d("WebAppInterface", "stopScan called from JS")
                bluetoothService?.stopDiscovery()
            }
        }

        @JavascriptInterface
        fun getAutoConnectEnabled(): Boolean {
            return this@MainActivity.isAutoConnectEnabled()
        }

        @JavascriptInterface
        fun setAutoConnectEnabled(enabled: Boolean) {
            runOnUiThread {
                this@MainActivity.setAutoConnectEnabled(enabled)
            }
        }

        @JavascriptInterface
        fun connect(address: String) {
            runOnUiThread {
                bluetoothService?.connect(address)
            }
        }

        @JavascriptInterface
        fun checkAllPermissionsStatus(): String {
            // 这个函数会检查所有必要权限，并返回一个JSON字符串给前端
            val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS)
            } else {
                listOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }

            val statusMap = requiredPermissions.associateWith {
                ContextCompat.checkSelfPermission(this@MainActivity, it) == PackageManager.PERMISSION_GRANTED
            }
            // 例: 返回 {"android.permission.BLUETOOTH_SCAN":true, "android.permission.BLUETOOTH_CONNECT":false}
            return org.json.JSONObject(statusMap as Map<*, *>).toString()
        }

        // [新增] 添加这个新函数，为新的前端UI提供简洁的权限状态JSON
        @JavascriptInterface
        fun checkPermissions(): String {
            // [新增] 引入 NotificationManagerCompat，这是检查通知状态的最佳实践
            // 需要确保你的 build.gradle 文件中有 'androidx.core:core-ktx' 依赖
            val notificationManager = androidx.core.app.NotificationManagerCompat.from(this@MainActivity)

            // 检查蓝牙相关权限 (逻辑不变，是正确的)
            val hasBluetooth = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                        ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            } else {
                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            }

            // [核心修复] 检查通知权限
            val hasNotifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // 对于安卓13+，检查运行时权限
                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            } else {
                // 对于安卓13以下版本，检查用户是否在系统设置中手动关闭了通知
                notificationManager.areNotificationsEnabled()
            }

            // 检查精确定时闹钟权限 (逻辑不变，符合系统行为)
            val hasAlarm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
                alarmManager.canScheduleExactAlarms()
            } else {
                true
            }

            // 检查定位权限 (逻辑不变，是正确的)
            val hasLocation = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

            // 构建与前端 `permissionsData` 的 key 一致的 Map
            val statusMap = mapOf(
                "bluetooth" to hasBluetooth,
                "notifications" to hasNotifications,
                "alarm" to hasAlarm,
                "location" to hasLocation
            )

            // 将 Map 转换为 JSON 字符串并返回给 WebView
            return org.json.JSONObject(statusMap as Map<*, *>).toString()
        }

        @JavascriptInterface
        fun requestMissingPermissions() {
            // 这个函数让JS可以主动触发权限申请流程
            runOnUiThread {
                checkAndRequestRuntimePermissions()
            }
        }

        // [修改] onJsReady 函数
        @JavascriptInterface
        fun onJsReady() {
            runOnUiThread {
                // JS准备好后，先检查核心蓝牙权限
                val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
                } else {
                    listOf(Manifest.permission.ACCESS_FINE_LOCATION)
                }
                val allGranted = requiredPermissions.all {
                    ContextCompat.checkSelfPermission(this@MainActivity, it) == PackageManager.PERMISSION_GRANTED
                }

                if (allGranted) {
                    // [修复] 如果权限已经有了，就只启动服务。
                    // 状态更新将会在服务成功连接后的 onServiceConnected 回调中自动进行。
                    startAndBindService()
                } else {
                    // 如果权限不全，就通知JS弹窗（这处理了首次启动的情况）
                    runJs("javascript:window.showInitialPermissionDialog();")
                }
            }
        }

        @JavascriptInterface
        fun disconnect() {
            runOnUiThread {
                bluetoothService?.disconnect()
            }
        }

        @JavascriptInterface
        fun sendData(data: String) {
            if (isBound) {
                bluetoothService?.sendData(data)
            }
        }

        @JavascriptInterface
        fun setTimer(taskId: String, delayMs: Long, command: String) {
            if (isBound) {
                bluetoothService?.setNativeTimer(taskId, delayMs, command)
            }
        }

        @JavascriptInterface
        fun cancelTimer(taskId: String) {
            if (isBound) {
                bluetoothService?.cancelNativeTimer(taskId)
            }
        }

        @JavascriptInterface
        fun checkExactAlarmPermission() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
                if (!alarmManager.canScheduleExactAlarms()) {
                    runJs("javascript:window.onPermissionRequired('exact-alarm');")
                }
            }
        }

        @JavascriptInterface
        fun requestExactAlarmPermission() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Intent().also { intent ->
                    intent.action = Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM
                    startActivity(intent)
                }
            }
        }
    }

    // 与JS通信的辅助函数
    private fun runJs(script: String) {
        runOnUiThread {
            webView.evaluateJavascript(script, null)
        }
    }

    private fun sendJsDeviceFound(name: String, address: String, type: Int) {
        val escapedName = name.replace("'", "\\'")
        runJs("javascript:window.onDeviceFound('$escapedName', '$address', $type);")
    }

    private fun sendJsError(message: String) {
        val escaped = message.replace("'", "\\'")
        runJs("javascript:window.onError('$escaped');")
    }

    private fun sendJsData(hexData: String) {
        runJs("javascript:window.onData('$hexData');")
    }

    private fun sendJsTimerUpdate(timersJson: String) {
        val flag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) android.util.Base64.NO_WRAP else android.util.Base64.DEFAULT
        val encodedJson = android.util.Base64.encodeToString(timersJson.toByteArray(), flag)
        runJs("javascript:window.onTimerUpdate('$encodedJson', true);")
    }
}
