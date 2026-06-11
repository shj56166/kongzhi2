package com.kongzhi2.myapplication // 确保包名与您项目的一致

import android.content.Context
import android.os.Build
import android.os.Vibrator
import android.os.VibrationEffect

// [修改] 在枚举类中添加新的效果类型
enum class VibrationType {
    SINGLE_CLICK,
    TASK_SET_ON,
    TASK_SET_OFF,
    TASK_EXECUTED,
    IMPACT_THUD,         // [新增] 系统预设的“闷响”效果
    CUSTOM_BASS_THUMP    // [新增] 自定义的“重低音”效果
}

object HapticFeedbackManager {

    // 预先定义好振动模式的参数
    private val TRIPLE_VIBRATION_TIMINGS = longArrayOf(0, 50, 80, 50, 80, 50)
    private val DOUBLE_VIBRATION_TIMINGS = longArrayOf(0, 50, 80, 50)
    private val TRIPLE_VIBRATION_AMPLITUDES = intArrayOf(0, 255, 0, 255, 0, 255)
    private val DOUBLE_VIBRATION_AMPLITUDES = intArrayOf(0, 255, 0, 255)

    // [新增] 为自定义重低音效果定义波形参数
    private val BASS_THUMP_TIMINGS = longArrayOf(0, 15, 60)
    private val BASS_THUMP_AMPLITUDES = intArrayOf(0, 180, 255)

    // ==========================================================
    // === [恢复] 这是被遗漏的 vibrateTicks 函数，用于时间选择器 ===
    // ==========================================================
    fun vibrateTicks(context: Context, tickCount: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || tickCount <= 0) {
            return
        }

        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (!vibrator.hasVibrator()) {
            return
        }

        val tickDuration = 15L
        val pauseDuration = 30L

        val timings = LongArray(tickCount * 2)
        val amplitudes = IntArray(tickCount * 2)

        for (i in 0 until tickCount) {
            timings[i * 2] = pauseDuration
            timings[i * 2 + 1] = tickDuration
            amplitudes[i * 2] = 0
            amplitudes[i * 2 + 1] = 255
        }

        timings[0] = 0

        val effect = VibrationEffect.createWaveform(timings, amplitudes, -1)
        vibrator.vibrate(effect)
    }

    fun vibrate(context: Context, type: VibrationType) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return
        }

        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (!vibrator.hasVibrator()) {
            return
        }

        // [修改] 在 when 表达式中处理所有效果类型
        val effect: VibrationEffect = when (type) {
            VibrationType.SINGLE_CLICK ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
                } else {
                    VibrationEffect.createOneShot(30, 255)
                }

            VibrationType.TASK_SET_ON ->
                VibrationEffect.createWaveform(TRIPLE_VIBRATION_TIMINGS, TRIPLE_VIBRATION_AMPLITUDES, -1)

            VibrationType.TASK_SET_OFF ->
                VibrationEffect.createWaveform(DOUBLE_VIBRATION_TIMINGS, DOUBLE_VIBRATION_AMPLITUDES, -1)

            VibrationType.TASK_EXECUTED ->
                VibrationEffect.createOneShot(100, 255)

            // [新增] 处理系统预设的“闷响”效果
            // [修改] 为 IMPACT_THUD 添加了对 Android 9 的兼容
            // [修改] 使用手动创建的波形震动，绕过 EFFECT_THUD 编译错误
            // [修改] 将短促的“闷响”效果，直接升级为带有回响的复杂效果
// [修改] 升级为带有“线性增强”和“延长峰值”的最终版效果
            VibrationType.IMPACT_THUD ->
                VibrationEffect.createWaveform(
                    // --- 波形时长 Timings (单位: 毫秒) ---
                    longArrayOf(
                        // 1. 线性增强阶段 (共40ms)
                        10, 10, 10, 10,
                        // 2. 峰值持续阶段 (共50ms)
                        50,
                        // 3. 物理回响衰减阶段
                        30, 25, 40, 20
                    ),
                    // --- 波形强度 Amplitudes (范围: 0-255) ---
                    intArrayOf(
                        // 1. 线性增强阶段: 强度从80平滑过渡到220
                        80, 130, 180, 220,
                        // 2. 峰值持续阶段: 达到并保持最大强度
                        255,
                        // 3. 物理回响衰减阶段: 两次逐渐减弱的回响
                        0, 120, 0, 80
                    ),
                    // 不重复
                    -1
                )

            // [新增] 处理自定义的“重低音”效果
            VibrationType.CUSTOM_BASS_THUMP ->
                VibrationEffect.createWaveform(BASS_THUMP_TIMINGS, BASS_THUMP_AMPLITUDES, -1)
        }

        vibrator.vibrate(effect)
    }
}