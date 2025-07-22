package com.kongzhi2.myapplication // 确保包名和你的项目一致

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // 确保只响应我们自己的action
        if (intent.action == BluetoothService.ACTION_EXECUTE_TIMER_COMMAND) {
            val command = intent.getStringExtra(BluetoothService.EXTRA_COMMAND)
            val taskId = intent.getStringExtra(BluetoothService.EXTRA_TASK_ID)

            if (command != null && taskId != null) {
                Log.d("AlarmReceiver", "Alarm triggered for task $taskId, command: $command. Starting service.")

                // 创建启动服务的Intent
                val serviceIntent = Intent(context, BluetoothService::class.java).apply {
                    action = BluetoothService.ACTION_EXECUTE_TIMER_COMMAND
                    putExtra(BluetoothService.EXTRA_COMMAND, command)
                    putExtra(BluetoothService.EXTRA_TASK_ID, taskId)
                }

                // [核心] 使用 ContextCompat.startForegroundService 确保在后台也能安全地启动服务。
                // 系统会给予几秒钟的窗口期，让服务内部调用 startForeground()。
                ContextCompat.startForegroundService(context, serviceIntent)
            } else {
                Log.e("AlarmReceiver", "Received alarm intent with missing data.")
            }
        }
    }
}
