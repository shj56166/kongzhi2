package com.kongzhi2.myapplication // 确保包名和你的项目一致

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.util.Log
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
import com.google.gson.Gson

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var bluetoothService: BluetoothService? = null
    private var isBound = false
    private val gson = Gson()

    private lateinit var requestPermissionsLauncher: ActivityResultLauncher<Array<String>>

    // 服务连接对象
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as BluetoothService.LocalBinder
            bluetoothService = binder.getService()
            isBound = true
            Log.d("MainActivity", "BluetoothService connected.")
            // 服务连接后，立即请求一次完整状态更新，确保UI同步
            bluetoothService?.requestFullStatusUpdate()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            bluetoothService = null
            Log.d("MainActivity", "BluetoothService disconnected.")
        }
    }

    // 广播接收器，用于接收来自Service的状态更新
    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothService.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothService.EXTRA_STATE, BluetoothService.STATE_DISCONNECTED)
                    val deviceName = intent.getStringExtra(BluetoothService.EXTRA_DEVICE_NAME) ?: "未连接"
                    Log.d("MainActivity", "Received state change: $state, Device: $deviceName")
                    runJs("javascript:window.onConnectionStateChange($state, '$deviceName');")
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
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setupPermissions()
        setupWebView()
        // startAndBindService() // 此行已移除
        registerAppReceiver()

        checkAndRequestPermissions()
    }

    private fun setupPermissions() {
        requestPermissionsLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val allGranted = permissions.entries.all { it.value }
            if (allGranted) {
                Log.d("MainActivity", "All required permissions granted. Starting service.")
                // ✅ 在获取所有权限后，安全地启动并绑定服务
                startAndBindService()
            } else {
                Toast.makeText(this, "需要蓝牙和通知权限才能正常工作", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.POST_NOTIFICATIONS
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN
            )
        }
        requestPermissionsLauncher.launch(requiredPermissions)
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
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(broadcastReceiver, intentFilter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(broadcastReceiver, intentFilter)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        unregisterReceiver(broadcastReceiver)
    }

    // JS 接口
    inner class WebAppInterface {
        @JavascriptInterface
        fun connect() {
            bluetoothService?.findAndConnectDeviceByName("HCW")
        }

        @JavascriptInterface
        fun disconnect() {
            bluetoothService?.disconnect()
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

    private fun runJs(script: String) {
        runOnUiThread {
            webView.evaluateJavascript(script, null)
        }
    }

    private fun sendJsError(message: String) {
        val escaped = message.replace("'", "\\'")
        runJs("javascript:window.onError('$escaped');")
    }

    private fun sendJsData(hexData: String) {
        runJs("javascript:window.onData('$hexData');")
    }

    private fun sendJsTimerUpdate(timersJson: String) {
        val encodedJson = android.util.Base64.encodeToString(timersJson.toByteArray(), android.util.Base64.NO_WRAP)
        runJs("javascript:window.onTimerUpdate('$encodedJson', true);")
    }
}