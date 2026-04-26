package com.autodial.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.text.SimpleDateFormat
import java.util.*

class CallLogDb(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val DB_NAME = "autodial.db"
        private const val DB_VERSION = 1
        const val TABLE_DIAL = "dial_log"
        const val COL_ID = "_id"
        const val COL_NUMBER = "number"
        const val COL_TIME = "dial_time"
        const val COL_SIM_SLOT = "sim_slot"
        const val COL_STATUS = "status"  // ok / error

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
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    /** 记录一次拨号 */
    fun insertDial(number: String, status: String = "ok", simSlot: Int = 0) {
        val cv = ContentValues().apply {
            put(COL_NUMBER, number)
            put(COL_TIME, System.currentTimeMillis())
            put(COL_SIM_SLOT, simSlot)
            put(COL_STATUS, status)
        }
        writableDatabase.insert(TABLE_DIAL, null, cv)
    }

    /** 根据号码更新 SIM 卡槽信息 */
    fun updateSimSlot(number: String, simSlot: Int) {
        // 找到该号码最近的一条拨号记录并更新卡槽
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

        // 确保每天都有数据（没有的填0）
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

    /**
     * 查询该号码最近一次拨号使用的 SIM 卡槽
     * @return 0=卡1, 1=卡2, -1=无记录
     */
    fun getLastSimSlot(number: String): Int {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_DIAL,
            arrayOf(COL_SIM_SLOT),
            "$COL_NUMBER = ? AND $COL_STATUS = 'ok'",
            arrayOf(number),
            null, null, "$COL_TIME DESC", "1"
        )
        val slot = if (cursor.moveToFirst()) cursor.getInt(0) else -1
        cursor.close()
        db.close()
        return slot
    }

    /**
     * 查询该号码最近一次拨号的时间和SIM卡（供弹窗显示）
     * @return Pair(simSlot, timeMs) 或 null
     */
    fun getLastDialInfo(number: String): Pair<Int, Long>? {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_DIAL,
            arrayOf(COL_SIM_SLOT, COL_TIME),
            "$COL_NUMBER = ? AND $COL_STATUS = 'ok'",
            arrayOf(number),
            null, null, "$COL_TIME DESC", "1"
        )
        val result = if (cursor.moveToFirst()) {
            Pair(cursor.getInt(0), cursor.getLong(1))
        } else null
        cursor.close()
        db.close()
        return result
    }
}
