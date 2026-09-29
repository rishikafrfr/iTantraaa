package isro.itantra.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class ItantraDatabaseHelper(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "itantra.db"
        private const val DATABASE_VERSION = 1

        // Devices table
        const val TABLE_DEVICES = "devices"
        const val COL_DEVICE_ID = "id"
        const val COL_DEVICE_NAME = "name"
        const val COL_DEVICE_ADDRESS = "address"
        const val COL_DEVICE_PROTOCOL = "protocol"
        const val COL_DEVICE_LAST_CONNECTED = "last_connected"
        const val COL_DEVICE_FORGOTTEN = "forgotten"

        // Connection history table
        const val TABLE_CONNECTIONS = "connection_history"
        const val COL_CONNECTION_ID = "id"
        const val COL_CONNECTION_DEVICE_NAME = "device_name"
        const val COL_CONNECTION_DEVICE_ADDRESS = "device_address"
        const val COL_CONNECTION_PROTOCOL = "protocol"
        const val COL_CONNECTION_TIME = "connection_time"
    }

    override fun onCreate(db: SQLiteDatabase) {

        val createDevicesTable = """
            CREATE TABLE $TABLE_DEVICES (
                $COL_DEVICE_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_DEVICE_NAME TEXT NOT NULL,
                $COL_DEVICE_ADDRESS TEXT NOT NULL UNIQUE,
                $COL_DEVICE_PROTOCOL TEXT NOT NULL,
                $COL_DEVICE_LAST_CONNECTED INTEGER,
                $COL_DEVICE_FORGOTTEN INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent()

        db.execSQL(createDevicesTable)

        val createConnectionsTable = """
            CREATE TABLE $TABLE_CONNECTIONS (
                $COL_CONNECTION_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_CONNECTION_DEVICE_NAME TEXT,
                $COL_CONNECTION_DEVICE_ADDRESS TEXT NOT NULL,
                $COL_CONNECTION_PROTOCOL TEXT NOT NULL,
                $COL_CONNECTION_TIME INTEGER NOT NULL
            )
        """.trimIndent()

        db.execSQL(createConnectionsTable)
    }

    override fun onUpgrade(
        db: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int
    ) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_CONNECTIONS")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_DEVICES")
        onCreate(db)
    }
    fun saveDevice(
        name: String,
        address: String,
        protocol: String
    ) {
        val db = writableDatabase

        val values = android.content.ContentValues().apply {
            put(COL_DEVICE_NAME, name)
            put(COL_DEVICE_ADDRESS, address)
            put(COL_DEVICE_PROTOCOL, protocol)
            put(COL_DEVICE_LAST_CONNECTED, System.currentTimeMillis())
            put(COL_DEVICE_FORGOTTEN, 0)
        }

        db.insertWithOnConflict(
            TABLE_DEVICES,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun getSavedDevices(): List<Map<String, Any?>> {
        val db = readableDatabase
        val devices = mutableListOf<Map<String, Any?>>()

        val cursor = db.query(
            TABLE_DEVICES,
            null,
            "$COL_DEVICE_FORGOTTEN = ?",
            arrayOf("0"),
            null,
            null,
            "$COL_DEVICE_LAST_CONNECTED DESC"
        )

        cursor.use {
            while (it.moveToNext()) {
                devices.add(
                    mapOf(
                        "id" to it.getLong(
                            it.getColumnIndexOrThrow(COL_DEVICE_ID)
                        ),
                        "name" to it.getString(
                            it.getColumnIndexOrThrow(COL_DEVICE_NAME)
                        ),
                        "address" to it.getString(
                            it.getColumnIndexOrThrow(COL_DEVICE_ADDRESS)
                        ),
                        "protocol" to it.getString(
                            it.getColumnIndexOrThrow(COL_DEVICE_PROTOCOL)
                        ),
                        "lastConnected" to it.getLong(
                            it.getColumnIndexOrThrow(COL_DEVICE_LAST_CONNECTED)
                        )
                    )
                )
            }
        }

        return devices
    }
    fun forgetDevice(address: String) {
        val db = writableDatabase

        val values = android.content.ContentValues().apply {
            put(COL_DEVICE_FORGOTTEN, 1)
        }

        db.update(
            TABLE_DEVICES,
            values,
            "$COL_DEVICE_ADDRESS = ?",
            arrayOf(address)
        )
    }

    fun addConnectionHistory(
        deviceName: String,
        deviceAddress: String,
        protocol: String
    ) {
        val db = writableDatabase

        val values = android.content.ContentValues().apply {
            put(COL_CONNECTION_DEVICE_NAME, deviceName)
            put(COL_CONNECTION_DEVICE_ADDRESS, deviceAddress)
            put(COL_CONNECTION_PROTOCOL, protocol)
            put(COL_CONNECTION_TIME, System.currentTimeMillis())
        }

        db.insert(
            TABLE_CONNECTIONS,
            null,
            values
        )
    }
    fun getConnectionHistory(): List<Map<String, Any?>> {
        val db = readableDatabase
        val history = mutableListOf<Map<String, Any?>>()

        val cursor = db.query(
            TABLE_CONNECTIONS,
            null,
            null,
            null,
            null,
            null,
            "$COL_CONNECTION_TIME DESC"
        )

        cursor.use {
            while (it.moveToNext()) {
                history.add(
                    mapOf(
                        "id" to it.getLong(
                            it.getColumnIndexOrThrow(COL_CONNECTION_ID)
                        ),
                        "deviceName" to it.getString(
                            it.getColumnIndexOrThrow(COL_CONNECTION_DEVICE_NAME)
                        ),
                        "deviceAddress" to it.getString(
                            it.getColumnIndexOrThrow(COL_CONNECTION_DEVICE_ADDRESS)
                        ),
                        "protocol" to it.getString(
                            it.getColumnIndexOrThrow(COL_CONNECTION_PROTOCOL)
                        ),
                        "connectionTime" to it.getLong(
                            it.getColumnIndexOrThrow(COL_CONNECTION_TIME)
                        )
                    )
                )
            }
        }

        return history
    }
}