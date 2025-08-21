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
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class MainActivity : AppCompatActivity() {

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

        startPermissionCheckFlow()
    }

    private fun setupPermissionsLauncher() {
        requestPermissionsLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val allGranted = permissions.values.all { it }

            if (allGranted) {
                Log.d("MainActivity", "All runtime permissions granted.")
                if (!isLocationEnabled()) {
                    showLocationDisabledDialog()
                } else {
                    startAndBindService()
                }
            } else {
                Log.d("MainActivity", "Some permissions were denied.")
                val shouldShowRationale = permissions.keys.any {
                    shouldShowRequestPermissionRationale(it)
                }
                if (!shouldShowRationale) {
                    showPermissionDeniedDialog()
                } else {
                    Toast.makeText(this, "需要所有权限才能正常工作", Toast.LENGTH_LONG).show()
                }
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
        webView = findViewById(R.id.webView)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                Log.d("WebViewConsole", "${consoleMessage.message()} -- From line ${consoleMessage.lineNumber()} of ${consoleMessage.sourceId()}")
                return true
            }
        }
        webView.addJavascriptInterface(WebAppInterface(), "BTBridge")
        webView.loadUrl("file:///android_asset/index.html")
    }

    private fun startAndBindService() {
        if (isBound) return
        val serviceIntent = Intent(this, BluetoothService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
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
        fun connect(address: String) {
            runOnUiThread {
                bluetoothService?.connect(address)
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

        @JavascriptInterface
        fun onJsReady() {
            runOnUiThread {
                bluetoothService?.requestFullStatusUpdate()
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
