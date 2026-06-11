package com.kongzhi2.myapplication

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

@SuppressLint("MissingPermission")
class BluetoothService : Service() {

    private val binder = LocalBinder()
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var connectThread: ConnectThread? = null
    private var connectedThread: ConnectedThread? = null

    private lateinit var localBroadcastManager: LocalBroadcastManager

    private var connectionState = STATE_DISCONNECTED
    private var deviceName: String? = null
    private var lastDeviceAddress: String? = null

    private val activeTimers = ConcurrentHashMap<String, TimerTask>()
    private val gson = Gson()
    private lateinit var alarmManager: AlarmManager
    private var wakeLock: PowerManager.WakeLock? = null

    private val notificationUpdateHandler = Handler(Looper.getMainLooper())
    private var isNotificationUpdaterRunning = false
    private val notificationUpdater = object : Runnable {
        override fun run() {
            if (activeTimers.isNotEmpty()) {
                updateNotification()
                notificationUpdateHandler.postDelayed(this, 1000)
            } else {
                isNotificationUpdaterRunning = false
            }
        }
    }

    // 用于接收系统发现的设备
    private val discoveryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothDevice.ACTION_FOUND) {
                val device: BluetoothDevice? =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }

                if (device != null && device.name != null) { // 增加一个判断，避免发送没有名字的设备
                    val deviceName = device.name
                    val deviceType = device.bluetoothClass?.deviceClass ?: 0

                    Log.d("BluetoothService", "Discovered device: $deviceName - ${device.address}")
                    val broadcastIntent = Intent(ACTION_DEVICE_FOUND).apply {
                        putExtra(EXTRA_DEVICE_NAME, deviceName)
                        putExtra(EXTRA_DEVICE_ADDRESS, device.address)
                        putExtra(EXTRA_DEVICE_TYPE, deviceType)
                    }
                    localBroadcastManager.sendBroadcast(broadcastIntent)
                }
            }
        }
    }

    // [新增] 用于接收系统扫描状态的广播接收器
    private val systemDiscoveryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    Log.d("BluetoothService", "System discovery finished. Broadcasting to UI.")
                    // 转发给 MainActivity
                    localBroadcastManager.sendBroadcast(Intent(ACTION_DISCOVERY_FINISHED))
                }
            }
        }
    }


    companion object {
        const val STATE_DISCONNECTED = 0
        const val STATE_CONNECTING = 1
        const val STATE_CONNECTED = 2

        const val ACTION_STATE_CHANGED = "com.kongzhi2.myapplication.STATE_CHANGED"
        const val ACTION_DATA_RECEIVED = "com.kongzhi2.myapplication.DATA_RECEIVED"
        const val ACTION_ERROR = "com.kongzhi2.myapplication.ERROR"
        const val ACTION_TIMER_UPDATE = "com.kongzhi2.myapplication.TIMER_UPDATE"
        const val ACTION_EXECUTE_TIMER_COMMAND = "com.kongzhi2.myapplication.EXECUTE_TIMER_COMMAND"
        const val ACTION_DEVICE_FOUND = "com.kongzhi2.myapplication.DEVICE_FOUND"

        // [新增] 定义新的广播 Action
        const val ACTION_DISCOVERY_STARTED = "com.kongzhi2.myapplication.DISCOVERY_STARTED"
        const val ACTION_DISCOVERY_FINISHED = "com.kongzhi2.myapplication.DISCOVERY_FINISHED"


        const val EXTRA_STATE = "extra_state"
        const val EXTRA_DEVICE_NAME = "extra_device_name"
        const val EXTRA_DATA = "extra_data"
        const val EXTRA_ERROR_MESSAGE = "extra_error_message"
        const val EXTRA_TIMERS_JSON = "extra_timers_json"
        const val EXTRA_COMMAND = "extra_command"
        const val EXTRA_TASK_ID = "extra_task_id"
        const val EXTRA_DEVICE_ADDRESS = "extra_device_address"
        const val EXTRA_DEVICE_TYPE = "extra_device_type"

        private const val NOTIFICATION_CHANNEL_ID = "BluetoothServiceChannel"
        private const val NOTIFICATION_ID = 1
        private val MY_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val PREFS_NAME = "TimerPrefs"
        private const val PREFS_KEY_TIMERS = "ActiveTimers"
        private const val PREFS_KEY_LAST_DEVICE = "LastDeviceAddress"
        private const val PREFS_KEY_AUTO_CONNECT = "AutoConnectEnabled"
    }

    override fun onCreate() {
        super.onCreate()
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager

        localBroadcastManager = LocalBroadcastManager.getInstance(this)

        createNotificationChannel()
        loadStateFromPrefs()

        // 注册设备发现的广播
        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
        registerReceiver(discoveryReceiver, filter)

        // [新增] 注册系统扫描状态的广播
        val systemFilter = IntentFilter(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        registerReceiver(systemDiscoveryReceiver, systemFilter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification("服务正在运行", "等待连接...")
        startForeground(NOTIFICATION_ID, notification)

        if (intent?.action == ACTION_EXECUTE_TIMER_COMMAND) {
            val command = intent.getStringExtra(EXTRA_COMMAND)
            val taskId = intent.getStringExtra(EXTRA_TASK_ID)
            if (command != null && taskId != null) {
                Log.d("BluetoothService", "Executing timer command for task $taskId")
                executeTimerCommand(taskId, command)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopNotificationUpdater()
        stopDiscovery()

        try {
            unregisterReceiver(discoveryReceiver)
            // [新增] 注销新的广播接收器
            unregisterReceiver(systemDiscoveryReceiver)
        } catch (e: IllegalArgumentException) {
            Log.w("BluetoothService", "Receiver not registered at destroy, skipping unregister.")
        }
    }

    @SuppressLint("MissingPermission")
    fun startDiscovery() {
        if (bluetoothAdapter?.isDiscovering == true) {
            bluetoothAdapter?.cancelDiscovery()
        }
        bluetoothAdapter?.startDiscovery()
        // [新增] 发送扫描开始的广播给 MainActivity
        localBroadcastManager.sendBroadcast(Intent(ACTION_DISCOVERY_STARTED))
        Log.d("BluetoothService", "Bluetooth discovery started and broadcast sent.")
    }

    @SuppressLint("MissingPermission")
    fun stopDiscovery() {
        if (bluetoothAdapter?.isDiscovering == true) {
            bluetoothAdapter?.cancelDiscovery()
        }
        localBroadcastManager.sendBroadcast(Intent(ACTION_DISCOVERY_FINISHED))
        Log.d("BluetoothService", "Bluetooth discovery stopped.")
    }

    // ... (其他函数保持不变)
    // ... (executeTimerCommand, connect, disconnect, sendData, etc. are unchanged)
    // ... (只需确保将以上修改应用到你的文件中即可)

    private fun executeTimerCommand(taskId: String, command: String) {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MyApp::TimerWakeLock")
        wakeLock?.acquire(10 * 1000L /* 10 seconds timeout */)
        HapticFeedbackManager.vibrate(this, VibrationType.TASK_EXECUTED)// [新增] 在任务执行时触发振动

        synchronized(this) {
            activeTimers.remove(taskId)
            saveTimersToPrefs()
            broadcastTimerUpdate()
            if (activeTimers.isEmpty()) {
                stopNotificationUpdater()
            }
        }

        if (connectionState == STATE_CONNECTED) {
            Log.d("BluetoothService", "Device connected. Sending command: $command")
            sendData(command)
        } else {
            Log.w("BluetoothService", "Device not connected. Attempting to reconnect...")
            broadcastError("设备未连接，正在为任务重连...")
            if (lastDeviceAddress != null) {
                connect(lastDeviceAddress!!, onConnected = {
                    Log.d("BluetoothService", "Reconnected successfully for timer task. Sending command.")
                    sendData(command)
                })
            } else {
                broadcastError("无法为任务重连：没有可用的设备地址")
            }
        }

        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
    }

    @Synchronized
    fun connect(address: String, onConnected: (() -> Unit)? = null) {
        if (connectionState == STATE_CONNECTING || connectionState == STATE_CONNECTED) {
            return
        }
        val device: BluetoothDevice? = bluetoothAdapter?.getRemoteDevice(address)
        if (device == null) {
            broadcastError("无法获取设备: $address")
            return
        }
        stopDiscovery()

        connectThread = ConnectThread(device, onConnected)
        connectThread?.start()
        setState(STATE_CONNECTING, device.name, address)
    }

    @Synchronized
    fun disconnect() {
        connectThread?.cancel()
        connectThread = null
        connectedThread?.cancel()
        connectedThread = null
        setState(STATE_DISCONNECTED, "未连接")
    }

    fun sendData(data: String) {
        connectedThread?.write(data.hexToByteArray())
    }

    fun getLastConnectedDeviceAddress(): String? {
        return lastDeviceAddress
    }

    fun isAutoConnectEnabled(): Boolean {
        return getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(PREFS_KEY_AUTO_CONNECT, false)
    }

    fun setAutoConnectEnabled(enabled: Boolean) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putBoolean(PREFS_KEY_AUTO_CONNECT, enabled)
        }
    }

    fun requestFullStatusUpdate() {
        Log.d("BluetoothService", "Full status update requested. State: $connectionState")
        broadcastConnectionState(connectionState, deviceName ?: "未连接", lastDeviceAddress)
        broadcastTimerUpdate()
    }

    // BluetoothService.kt 中
    private fun setState(state: Int, name: String?, address: String? = null) {
        connectionState = state
        this.deviceName = name
        // 当连接成功时，我们才更新并保存 lastDeviceAddress
        if (state == STATE_CONNECTED && address != null) {
            lastDeviceAddress = address
            saveStateToPrefs()
        }
        Log.d("BluetoothService", "Connection state changed to $state, Device: $name, Address: $address")
        broadcastConnectionState(state, name ?: "未连接", address)
        updateNotification()
    }

    private fun broadcastConnectionState(state: Int, name: String, address: String?) {
        val intent = Intent(ACTION_STATE_CHANGED).apply {
            putExtra(EXTRA_STATE, state)
            putExtra(EXTRA_DEVICE_NAME, name)
            // 只有在地址不为空时才把它放进 Intent
            address?.let { putExtra(EXTRA_DEVICE_ADDRESS, it) }
        }
        localBroadcastManager.sendBroadcast(intent)
    }

    private fun broadcastData(data: String) {
        val intent = Intent(ACTION_DATA_RECEIVED).apply {
            putExtra(EXTRA_DATA, data)
        }
        localBroadcastManager.sendBroadcast(intent)
    }

    private fun broadcastError(message: String) {
        val intent = Intent(ACTION_ERROR).apply {
            putExtra(EXTRA_ERROR_MESSAGE, message)
        }
        localBroadcastManager.sendBroadcast(intent)
        Log.e("BluetoothService", message)
    }

    private fun broadcastTimerUpdate() {
        val timersJson = gson.toJson(activeTimers.values.toList())
        val intent = Intent(ACTION_TIMER_UPDATE).apply {
            putExtra(EXTRA_TIMERS_JSON, timersJson)
        }
        localBroadcastManager.sendBroadcast(intent)
        updateNotification()
        Log.d("BluetoothService", "Broadcasting timer update: $timersJson")
    }

    // 定时器管理
    fun setNativeTimer(taskId: String, delayMs: Long, command: String) {
        val triggerAt = System.currentTimeMillis() + delayMs
        val intent = Intent(this, AlarmReceiver::class.java).apply {
            action = ACTION_EXECUTE_TIMER_COMMAND
            putExtra(EXTRA_COMMAND, command)
            putExtra(EXTRA_TASK_ID, taskId)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            this,
            taskId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            broadcastError("无法设置精确闹钟，请授予权限")
            return
        }

        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)

        synchronized(this) {
            activeTimers[taskId] = TimerTask(taskId, command, triggerAt)
            saveTimersToPrefs()
        }
        broadcastTimerUpdate()
        startNotificationUpdater()
        Log.d("BluetoothService", "Timer set for task $taskId, command: $command")
    }

    fun cancelNativeTimer(taskId: String) {
        synchronized(this) {
            if (activeTimers.containsKey(taskId)) {
                val intent = Intent(this, AlarmReceiver::class.java).apply {
                    action = ACTION_EXECUTE_TIMER_COMMAND
                }
                val pendingIntentToCancel = PendingIntent.getBroadcast(
                    this,
                    taskId.hashCode(),
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                alarmManager.cancel(pendingIntentToCancel)
                activeTimers.remove(taskId)
                saveTimersToPrefs()
                Log.d("BluetoothService", "Timer canceled for task $taskId")
                broadcastTimerUpdate()

                if (activeTimers.isEmpty()) {
                    stopNotificationUpdater()
                } else {
                }
            } else {
                Log.w("BluetoothService", "Attempted to cancel a non-existent timer task: $taskId")
            }
        }
    }

    private fun saveTimersToPrefs() {
        val timersJson = gson.toJson(activeTimers)
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putString(PREFS_KEY_TIMERS, timersJson)
        }
    }

    private fun saveStateToPrefs() {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putString(PREFS_KEY_LAST_DEVICE, lastDeviceAddress)
        }
    }

    private fun loadStateFromPrefs() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val timersJson = prefs.getString(PREFS_KEY_TIMERS, null)
        if (timersJson != null) {
            val type = object : TypeToken<ConcurrentHashMap<String, TimerTask>>() {}.type
            val loadedTimers: ConcurrentHashMap<String, TimerTask> = gson.fromJson(timersJson, type)
            activeTimers.clear()
            activeTimers.putAll(loadedTimers)
            Log.d("BluetoothService", "Loaded ${activeTimers.size} timers from prefs.")
            startNotificationUpdater()
        }
        lastDeviceAddress = prefs.getString(PREFS_KEY_LAST_DEVICE, null)
        Log.d("BluetoothService", "Loaded last device address: $lastDeviceAddress")
    }

    private inner class ConnectThread(private val device: BluetoothDevice, private val onConnectedCallback: (() -> Unit)? = null) : Thread() {
        private val mmSocket: BluetoothSocket? by lazy(LazyThreadSafetyMode.NONE) {
            device.createRfcommSocketToServiceRecord(MY_UUID)
        }

        override fun run() {
            try {
                mmSocket?.connect()
                synchronized(this@BluetoothService) {
                    connectedThread = ConnectedThread(mmSocket!!)
                    connectedThread?.start()
                    setState(STATE_CONNECTED, device.name, device.address)
                    onConnectedCallback?.invoke()
                }
            } catch (e: IOException) {
                Log.e("ConnectThread", "Socket connection failed", e)
                try {
                    mmSocket?.close()
                } catch (closeException: IOException) {
                    Log.e("ConnectThread", "Could not close the client socket", closeException)
                }
                setState(STATE_DISCONNECTED, "连接失败")
                broadcastError("连接失败: ${e.message}")
            }
        }

        fun cancel() {
            try {
                mmSocket?.close()
            } catch (e: IOException) {
                Log.e("ConnectThread", "Could not close the client socket", e)
            }
        }
    }

    private inner class ConnectedThread(private val mmSocket: BluetoothSocket) : Thread() {
        private val mmInStream: InputStream = mmSocket.inputStream
        private val mmOutStream: OutputStream = mmSocket.outputStream
        private val mmBuffer: ByteArray = ByteArray(1024)

        override fun run() {
            var numBytes: Int
            while (true) {
                try {
                    numBytes = mmInStream.read(mmBuffer)
                    val receivedData = mmBuffer.copyOf(numBytes).toHexString()
                    broadcastData(receivedData)
                } catch (e: IOException) {
                    Log.d("ConnectedThread", "Input stream was disconnected", e)
                    setState(STATE_DISCONNECTED, "连接已断开")
                    break
                }
            }
        }

        fun write(bytes: ByteArray) {
            try {
                mmOutStream.write(bytes)
            } catch (e: IOException) {
                Log.e("ConnectedThread", "Error occurred when sending data", e)
                setState(STATE_DISCONNECTED, "发送失败，连接已断开")
            }
        }

        fun cancel() {
            try {
                mmSocket.close()
            } catch (e: IOException) {
                Log.e("ConnectedThread", "Could not close the connect socket", e)
            }
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): BluetoothService = this@BluetoothService
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "蓝牙连接服务",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(title: String, text: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification() {
        val title: String
        val text: String

        when (connectionState) {
            STATE_CONNECTED -> {
                title = "已连接到 ${deviceName ?: "设备"}"
                text = getNextTimerText()
            }
            STATE_CONNECTING -> {
                title = "正在连接..."
                text = "请稍候"
            }
            else -> {
                title = "蓝牙已断开"
                text = getNextTimerText()
            }
        }
        val notification = createNotification(title, text)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun getNextTimerText(): String {
        if (activeTimers.isEmpty()) {
            return "没有正在运行的定时任务"
        }
        val nextTask = activeTimers.values.minByOrNull { it.triggerAt } ?: return "没有正在运行的定时任务"
        val remainingMs = nextTask.triggerAt - System.currentTimeMillis()
        return if (remainingMs > 0) {
            val hours = TimeUnit.MILLISECONDS.toHours(remainingMs)
            val minutes = TimeUnit.MILLISECONDS.toMinutes(remainingMs) % 60
            val seconds = TimeUnit.MILLISECONDS.toSeconds(remainingMs) % 60
            val timeString = when {
                hours > 0 -> String.format(Locale.getDefault(), "%d小时%d分后执行", hours, minutes)
                minutes > 0 -> String.format(Locale.getDefault(), "%d分%d秒后执行", minutes, seconds)
                else -> String.format(Locale.getDefault(), "%d秒后执行", seconds)
            }
            "下一个任务: $timeString"
        } else {
            "任务即将执行..."
        }
    }

    private fun startNotificationUpdater() {
        if (!isNotificationUpdaterRunning && activeTimers.isNotEmpty()) {
            isNotificationUpdaterRunning = true
            notificationUpdateHandler.post(notificationUpdater)
            Log.d("BluetoothService", "Notification updater started.")
        }
    }

    private fun stopNotificationUpdater() {
        if (isNotificationUpdaterRunning) {
            isNotificationUpdaterRunning = false
            notificationUpdateHandler.removeCallbacks(notificationUpdater)
            Log.d("BluetoothService", "Notification updater stopped.")
            updateNotification()
        }
    }

    data class TimerTask(
        val id: String,
        val command: String,
        val triggerAt: Long
    )

    private fun String.hexToByteArray(): ByteArray {
        val cleanHex = this.replace(" ", "")
        check(cleanHex.length % 2 == 0) { "Hex 字符串必须有偶数个字符" }
        return cleanHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun ByteArray.toHexString(): String = joinToString(" ") { "%02X".format(it) }
}
