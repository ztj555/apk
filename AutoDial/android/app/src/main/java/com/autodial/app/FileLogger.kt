package com.autodial.app

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.*

/**
 * AutoDial 文件日志工具
 * 自动将关键日志写入手机文件，便于排查问题
 * 日志路径: /sdcard/Android/data/com.autodial.app/files/autodial-logs/
 * 文件名: autodial-YYYY-MM-DD.log
 * 自动保留最近7天日志
 */
object FileLogger {

    private const val TAG = "FileLogger"
    private const val MAX_LOG_DAYS = 7
    private const val FLUSH_INTERVAL_MS = 3000L

    private var logDir: File? = null
    private var currentWriter: PrintWriter? = null
    private var currentDate: String = ""
    private var handler: Handler? = null
    private var handlerThread: HandlerThread? = null
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    private val buffer = StringBuffer()

    /**
     * 初始化日志系统
     */
    fun init(context: Context) {
        try {
            // 优先使用外部存储（方便用户导出）
            val externalDir = context.getExternalFilesDir(null)
            if (externalDir != null) {
                logDir = File(externalDir, "autodial-logs")
            } else {
                // 回退到内部存储
                logDir = File(context.filesDir, "autodial-logs")
            }

            if (!logDir!!.exists()) {
                logDir!!.mkdirs()
            }

            // 启动后台写入线程
            handlerThread = HandlerThread("FileLogger")
            handlerThread!!.start()
            handler = Handler(handlerThread!!.looper)

            // 定期刷新缓冲区
            handler!!.post(object : Runnable {
                override fun run() {
                    flushBuffer()
                    handler?.postDelayed(this, FLUSH_INTERVAL_MS)
                }
            })

            i("FileLogger", "=== AutoDial 日志系统启动 ===")
            i("FileLogger", "日志目录: ${logDir?.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "FileLogger init failed: ${e.message}")
        }
    }

    /**
     * 关闭日志系统
     */
    fun shutdown() {
        try {
            flushBuffer()
            handler?.removeCallbacksAndMessages(null)
            handlerThread?.quitSafely()
            currentWriter?.close()
            currentWriter = null
        } catch (_: Exception) {}
    }

    /**
     * 获取日志目录路径（供 UI 显示）
     */
    fun getLogDirPath(): String = logDir?.absolutePath ?: "(未初始化)"

    /**
     * INFO 级别日志
     */
    fun i(tag: String, msg: String) {
        log("I", tag, msg)
    }

    /**
     * WARNING 级别日志
     */
    fun w(tag: String, msg: String) {
        log("W", tag, msg)
    }

    /**
     * ERROR 级别日志
     */
    fun e(tag: String, msg: String) {
        log("E", tag, msg)
    }

    /**
     * DEBUG 级别日志
     */
    fun d(tag: String, msg: String) {
        log("D", tag, msg)
    }

    /**
     * 记录消息内容（JSON格式，截断超长内容）
     */
    fun logMessage(direction: String, msgType: String, content: String) {
        val truncated = if (content.length > 500) content.substring(0, 500) + "...(truncated)" else content
        log("M", direction, "[$msgType] $truncated")
    }

    private fun log(level: String, tag: String, msg: String) {
        try {
            val timestamp = timeFormat.format(Date())
            val line = "$timestamp $level/$tag: $msg\n"
            synchronized(buffer) {
                buffer.append(line)
            }
            // 同时输出到 Android Logcat
            when (level) {
                "E" -> Log.e(tag, msg)
                "W" -> Log.w(tag, msg)
                "I" -> Log.i(tag, msg)
                else -> Log.d(tag, msg)
            }
        } catch (_: Exception) {}
    }

    private fun flushBuffer() {
        try {
            val today = dateFormat.format(Date())

            // 日期变更或首次写入，切换文件
            if (today != currentDate || currentWriter == null) {
                currentWriter?.close()
                currentDate = today
                val logFile = File(logDir, "autodial-$today.log")
                currentWriter = PrintWriter(FileWriter(logFile, true), true)

                // 清理过期日志
                cleanOldLogs()
            }

            val toWrite: String
            synchronized(buffer) {
                if (buffer.isEmpty()) return
                toWrite = buffer.toString()
                buffer.setLength(0)
            }

            currentWriter?.print(toWrite)
            currentWriter?.flush()
        } catch (_: Exception) {}
    }

    private fun cleanOldLogs() {
        try {
            val dir = logDir ?: return
            val files = dir.listFiles() ?: return
            val cutoff = System.currentTimeMillis() - MAX_LOG_DAYS * 24 * 60 * 60 * 1000L
            for (file in files) {
                if (file.name.endsWith(".log") && file.lastModified() < cutoff) {
                    file.delete()
                }
            }
        } catch (_: Exception) {}
    }
}
