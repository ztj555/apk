package com.autodial.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Build
import android.provider.CallLog
import android.telephony.SubscriptionManager
import android.util.Log
import java.text.SimpleDateFormat
import java.util.*

class CallLogDb(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val TAG = "CallLogDb"
        private const val DB_NAME = "autodial.db"
        private const val DB_VERSION = 2  // v2: 新增 sim_cache 表
        const val TABLE_DIAL = "dial_log"
        const val COL_ID = "_id"
        const val COL_NUMBER = "number"
        const val COL_TIME = "dial_time"
        const val COL_SIM_SLOT = "sim_slot"
        const val COL_STATUS = "status"  // ok / error

        // SIM 卡缓存表：从系统通话记录同步而来
        private const val TABLE_SIM_CACHE = "sim_cache"
        private const val CACHE_COL_NUMBER = "number"
        private const val CACHE_COL_SIM_SLOT = "sim_slot"
        private const val CACHE_COL_TIME = "call_time"

        private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        private val dateTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    }

    data class DialRecord(
        val id: Long = 0,
        val number: String,
        val time: Long,
        val simSlot: Int = 0,
        val status: String = "ok"
    )

    data class DayStats(
        val date: String,
        val count: Int
    )

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE $TABLE_DIAL (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_NUMBER TEXT NOT NULL,
                $COL_TIME INTEGER NOT NULL,
                $COL_SIM_SLOT INTEGER DEFAULT 0,
                $COL_STATUS TEXT DEFAULT 'ok'
            )
        """)
        db.execSQL("""
            CREATE TABLE $TABLE_SIM_CACHE (
                $CACHE_COL_NUMBER TEXT NOT NULL,
                $CACHE_COL_SIM_SLOT INTEGER DEFAULT 0,
                $CACHE_COL_TIME INTEGER NOT NULL,
                PRIMARY KEY ($CACHE_COL_NUMBER)
            )
        """)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS $TABLE_SIM_CACHE (
                    $CACHE_COL_NUMBER TEXT NOT NULL,
                    $CACHE_COL_SIM_SLOT INTEGER DEFAULT 0,
                    $CACHE_COL_TIME INTEGER NOT NULL,
                    PRIMARY KEY ($CACHE_COL_NUMBER)
                )
            """)
        }
    }

    /** 记录一次拨号 */
    fun insertDial(number: String, status: String = "ok", simSlot: Int = 0) {
        val cv = ContentValues().apply {
            put(COL_NUMBER, number)
            put(COL_TIME, System.currentTimeMillis())
            put(COL_SIM_SLOT, simSlot)
            put(COL_STATUS, status)
        }
        writableDatabase.insert(TABLE_DIAL, null, cv)
        // 同步更新 SIM 缓存
        updateSimCache(number, simSlot, System.currentTimeMillis())
    }

    /** 根据号码更新 SIM 卡槽信息（旧方法，保留兼容） */
    fun updateSimSlot(number: String, simSlot: Int) {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_DIAL,
            arrayOf(COL_ID),
            "$COL_NUMBER = ? AND $COL_SIM_SLOT = 0",
            arrayOf(number),
            null, null, "$COL_TIME DESC", "1"
        )
        if (cursor.moveToFirst()) {
            val id = cursor.getLong(0)
            val cv = ContentValues().apply { put(COL_SIM_SLOT, simSlot) }
            writableDatabase.update(TABLE_DIAL, cv, "$COL_ID = ?", arrayOf(id.toString()))
        }
        cursor.close()
        updateSimCache(number, simSlot, System.currentTimeMillis())
    }

    /**
     * 更新 SIM 缓存表（号码 → 上次使用的卡）
     */
    private fun updateSimCache(number: String, simSlot: Int, time: Long) {
        try {
            val cv = ContentValues().apply {
                put(CACHE_COL_SIM_SLOT, simSlot)
                put(CACHE_COL_TIME, time)
            }
            writableDatabase.insertWithOnConflict(TABLE_SIM_CACHE, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        } catch (e: Exception) {
            Log.e(TAG, "更新SIM缓存失败: ${e.message}")
        }
    }

    /**
     * 从系统通话记录同步 SIM 卡信息到缓存表
     * 在首次启动或数据库为空时调用
     * @return 同步的记录数
     */
    fun syncFromSystemCallLog(context: Context): Int {
        var syncCount = 0
        try {
            if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CALL_LOG)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "没有 READ_CALL_LOG 权限，无法同步系统通话记录")
                return 0
            }

            // 获取当前 SIM 信息
            val sm = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                    context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
                } else null
            } catch (_: Exception) { null }

            val simInfoList = try {
                sm?.activeSubscriptionInfoList?.filterNotNull() ?: emptyList()
            } catch (_: Exception) { emptyList() }

            if (simInfoList.isEmpty()) {
                Log.w(TAG, "无可用 SIM 信息，无法同步")
                return 0
            }

            // 查询系统通话记录（仅呼出）
            @Suppress("DEPRECATION")
            val cursor = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(
                    CallLog.Calls.NUMBER,
                    CallLog.Calls.DATE,
                    CallLog.Calls.PHONE_ACCOUNT_ID,
                    CallLog.Calls.TYPE
                ),
                "${CallLog.Calls.TYPE} = ?",
                arrayOf(CallLog.Calls.OUTGOING_TYPE.toString()),
                "${CallLog.Calls.DATE} DESC"
            ) ?: return 0

            cursor.use {
                val numberIdx = it.getColumnIndex(CallLog.Calls.NUMBER)
                val dateIdx = it.getColumnIndex(CallLog.Calls.DATE)
                val subIdIdx = it.getColumnIndex(CallLog.Calls.PHONE_ACCOUNT_ID)

                val syncedNumbers = mutableSetOf<String>()

                while (it.moveToNext()) {
                    val number = it.getString(numberIdx) ?: continue
                    if (syncedNumbers.contains(number)) continue

                    val date = it.getLong(dateIdx)
                    val subId = it.getString(subIdIdx)

                    // 匹配 subscriptionId → simSlot
                    var simSlot = -1
                    if (subId != null) {
                        for (info in simInfoList) {
                            if (info.subscriptionId.toString() == subId) {
                                simSlot = info.simSlotIndex
                                break
                            }
                        }
                    }

                    if (simSlot >= 0) {
                        val cv = ContentValues().apply {
                            put(CACHE_COL_SIM_SLOT, simSlot)
                            put(CACHE_COL_TIME, date)
                        }
                        writableDatabase.insertWithOnConflict(TABLE_SIM_CACHE, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
                        syncedNumbers.add(number)
                        syncCount++
                    }

                    if (syncCount >= 500) break
                }
            }
            Log.d(TAG, "系统通话记录同步完成，共 $syncCount 个号码")
        } catch (e: Exception) {
            Log.e(TAG, "同步系统通话记录失败: ${e.message}")
        }
        return syncCount
    }

    /**
     * 查询全局最近一次拨号使用的 SIM 卡槽（不分号码，用于轮流模式）
     * 优先查 APP 拨号记录，fallback 查 sim_cache
     * @return 0=卡1, 1=卡2, -1=无记录
     */
    fun getLastSimSlotGlobal(): Int {
        // 1. 优先查 APP 自身的拨号记录（最新一条成功的）
        val db = readableDatabase
        val cursor = db.query(
            TABLE_DIAL,
            arrayOf(COL_SIM_SLOT),
            "$COL_STATUS = 'ok'",
            null,
            null, null, "$COL_TIME DESC", "1"
        )
        if (cursor.moveToFirst()) {
            val slot = cursor.getInt(0)
            cursor.close()
            return slot
        }
        cursor.close()

        // 2. fallback 查 sim_cache（最新的一条）
        val cacheCursor = db.query(
            TABLE_SIM_CACHE,
            arrayOf(CACHE_COL_SIM_SLOT),
            null, null,
            null, null, "$CACHE_COL_TIME DESC", "1"
        )
        val cachedSlot = if (cacheCursor.moveToFirst()) cacheCursor.getInt(0) else -1
        cacheCursor.close()
        db.close()
        return cachedSlot
    }

    /**
     * 查询该号码最近一次拨号使用的 SIM 卡槽
     * 优先查 APP 自身拨号记录，fallback 查 SIM 缓存（来自系统通话记录）
     * @return 0=卡1, 1=卡2, -1=无记录
     */
    fun getLastSimSlot(number: String): Int {
        // 1. 优先查 APP 自身的拨号记录
        val db = readableDatabase
        val cursor = db.query(
            TABLE_DIAL,
            arrayOf(COL_SIM_SLOT),
            "$COL_NUMBER = ? AND $COL_STATUS = 'ok'",
            arrayOf(number),
            null, null, "$COL_TIME DESC", "1"
        )
        if (cursor.moveToFirst()) {
            val slot = cursor.getInt(0)
            cursor.close()
            return slot
        }
        cursor.close()

        // 2. fallback 查 SIM 缓存
        val cacheCursor = db.query(
            TABLE_SIM_CACHE,
            arrayOf(CACHE_COL_SIM_SLOT),
            "$CACHE_COL_NUMBER = ?",
            arrayOf(number),
            null, null, null, "1"
        )
        val cachedSlot = if (cacheCursor.moveToFirst()) cacheCursor.getInt(0) else -1
        cacheCursor.close()
        db.close()
        return cachedSlot
    }

    /**
     * 查询该号码最近一次拨号的时间和SIM卡（供弹窗显示）
     * @return Pair(simSlot, timeMs) 或 null
     */
    fun getLastDialInfo(number: String): Pair<Int, Long>? {
        // 1. 优先查 APP 自身记录
        val db = readableDatabase
        val cursor = db.query(
            TABLE_DIAL,
            arrayOf(COL_SIM_SLOT, COL_TIME),
            "$COL_NUMBER = ? AND $COL_STATUS = 'ok'",
            arrayOf(number),
            null, null, "$COL_TIME DESC", "1"
        )
        if (cursor.moveToFirst()) {
            val result = Pair(cursor.getInt(0), cursor.getLong(1))
            cursor.close()
            return result
        }
        cursor.close()

        // 2. fallback 查 SIM 缓存
        val cacheCursor = db.query(
            TABLE_SIM_CACHE,
            arrayOf(CACHE_COL_SIM_SLOT, CACHE_COL_TIME),
            "$CACHE_COL_NUMBER = ?",
            arrayOf(number),
            null, null, null, "1"
        )
        val cachedResult = if (cacheCursor.moveToFirst()) {
            Pair(cacheCursor.getInt(0), cacheCursor.getLong(1))
        } else null
        cacheCursor.close()
        db.close()
        return cachedResult
    }

    /** 获取最近 N 天的每日拨号统计 */
    fun getDailyStats(days: Int = 7): List<DayStats> {
        val list = mutableListOf<DayStats>()
        val calendar = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_MONTH, -(days - 1))
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startTime = calendar.timeInMillis

        val db = readableDatabase
        val cursor = db.query(
            TABLE_DIAL,
            arrayOf(COL_TIME),
            "$COL_TIME >= ? AND $COL_STATUS = 'ok'",
            arrayOf(startTime.toString()),
            null, null, "$COL_TIME ASC"
        )

        val countMap = LinkedHashMap<String, Int>()
        if (cursor.moveToFirst()) {
            do {
                val time = cursor.getLong(0)
                val date = dateFormat.format(Date(time))
                countMap[date] = (countMap[date] ?: 0) + 1
            } while (cursor.moveToNext())
        }
        cursor.close()
        db.close()

        for (i in 0 until days) {
            val cal = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_MONTH, -(days - 1 - i))
            }
            val date = dateFormat.format(cal.time)
            list.add(DayStats(date, countMap[date] ?: 0))
        }
        return list
    }

    /** 获取总拨号次数 */
    fun getTotalCount(): Int {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT COUNT(*) FROM $TABLE_DIAL WHERE $COL_STATUS = 'ok'", null)
        val count = if (cursor.moveToFirst()) cursor.getInt(0) else 0
        cursor.close()
        db.close()
        return count
    }

    /** 获取今日拨号次数 */
    fun getTodayCount(): Int {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val db = readableDatabase
        val cursor = db.query(
            TABLE_DIAL,
            arrayOf("COUNT(*)"),
            "$COL_TIME >= ? AND $COL_STATUS = 'ok'",
            arrayOf(cal.timeInMillis.toString()),
            null, null, null
        )
        val count = if (cursor.moveToFirst()) cursor.getInt(0) else 0
        cursor.close()
        db.close()
        return count
    }

    /** 格式化时间为日期时间字符串 */
    fun formatDateTime(timeMs: Long): String = dateTimeFormat.format(Date(timeMs))

    fun formatDate(timeMs: Long): String = dateFormat.format(Date(timeMs))
}
