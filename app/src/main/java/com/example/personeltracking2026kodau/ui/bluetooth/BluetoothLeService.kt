package com.example.personeltracking2026kodau.ui.bluetooth

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.*
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import com.example.personeltracking2026kodau.R
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

data class HeartRateReading(
    val bpm: Int,
    val timestamp: Long = System.currentTimeMillis()
)

class BluetoothLeService : Service() {

    companion object {
        private const val TAG = "BLEService"
        private const val NOTIF_CHANNEL_ID = "ble_channel"
        private const val NOTIF_ID = 101
        private const val PREFS_NAME = "ble_locked_device"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_DEVICE_ADDRESS = "device_address"
        private const val RECONNECT_DELAY_MS = 5_000L

        val connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
        val bpmValue        = MutableStateFlow(0)
        val bpmReading      = MutableSharedFlow<HeartRateReading>(extraBufferCapacity = 64)
        val connectedDevice = MutableStateFlow<BluetoothDeviceModel?>(null)
        val lockedDevice    = MutableStateFlow<BluetoothDeviceModel?>(null)

        // UUID Heart Rate
        val HEART_RATE_SERVICE  = java.util.UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val HEART_RATE_CHAR     = java.util.UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        val CLIENT_CHAR_CONFIG  = java.util.UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        fun publishBpm(bpm: Int) {
            val normalizedBpm = bpm.coerceAtLeast(0)
            bpmValue.value = normalizedBpm
            bpmReading.tryEmit(HeartRateReading(normalizedBpm))
        }
    }

    enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED }

    // Binder untuk Activity bisa panggil fungsi langsung
    inner class LocalBinder : Binder() {
        fun getService() = this@BluetoothLeService
    }
    private val binder = LocalBinder()

    private var bluetoothGatt: BluetoothGatt? = null
    private var currentDeviceAddress: String? = null
    private var lastContactLostRawBpm: Int? = null
    private var userRequestedDisconnect = false
    private var reconnectScheduled = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private val reconnectRunnable = object : Runnable {
        override fun run() {
            reconnectScheduled = false
            reconnectLockedDevice()
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // LIFECYCLE SERVICE
    // ══════════════════════════════════════════════════════════════════════

    override fun onCreate() {
        super.onCreate()
        lockedDevice.value = loadLockedDevice()
        currentDeviceAddress = lockedDevice.value?.address
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Bluetooth standby"))
        if (lockedDevice.value != null) {
            scheduleReconnect()
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lockedDevice.value = loadLockedDevice()
        currentDeviceAddress = lockedDevice.value?.address
        if (lockedDevice.value != null &&
            connectionState.value == ConnectionState.DISCONNECTED
        ) {
            scheduleReconnect()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(reconnectRunnable)
        disconnectGatt()
    }

    // ══════════════════════════════════════════════════════════════════════
    // PUBLIC API — dipanggil dari Activity
    // ══════════════════════════════════════════════════════════════════════

    fun connect(device: BluetoothDeviceModel, adapter: android.bluetooth.BluetoothAdapter) {
        userRequestedDisconnect = false
        mainHandler.removeCallbacks(reconnectRunnable)
        reconnectScheduled = false
        currentDeviceAddress = device.address
        saveLockedDevice(device)

        // Paksa disconnect gatt sebelumnya sebelum connect device
        if (bluetoothGatt != null) {
            disconnectGatt()
        }

//        if (connectionState.value == ConnectionState.CONNECTED) {
//            Log.d(TAG, "Already connected, skip")
//            return
//        }

        connectionState.value = ConnectionState.CONNECTING
        connectedDevice.value = device.copy(state = DeviceState.CONNECTING)

        val rawDevice = adapter.getRemoteDevice(device.address) ?: run {
            connectionState.value = ConnectionState.DISCONNECTED
            return
        }

        try {
            bluetoothGatt = rawDevice.connectGatt(this, false, gattCallback)
            updateNotification("Menghubungkan ke ${device.name}...")
        } catch (e: SecurityException) {
            Log.e(TAG, "connectGatt failed: ${e.message}")
            connectionState.value = ConnectionState.DISCONNECTED
        }
    }

    fun disconnect() {
        userRequestedDisconnect = true
        mainHandler.removeCallbacks(reconnectRunnable)
        reconnectScheduled = false
        clearLockedDevice()
        disconnectGatt()
        bluetoothGatt = null
        currentDeviceAddress = null
        connectionState.value = ConnectionState.DISCONNECTED
        publishBpm(0)
        connectedDevice.value = null
        updateNotification("Bluetooth standby")
    }

    fun forgetLockedDevice() {
        disconnect()
    }

    fun isConnected() = connectionState.value == ConnectionState.CONNECTED

    // ══════════════════════════════════════════════════════════════════════
    // GATT CALLBACK
    // ══════════════════════════════════════════════════════════════════════

    private val gattCallback = object : BluetoothGattCallback() {

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "GATT Connected")
                    try {
                        gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                        gatt.discoverServices()
                    } catch (e: SecurityException) { }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    gatt.close()
                    bluetoothGatt = null
                    lastContactLostRawBpm = null
                    Log.d(TAG, "GATT Disconnected")
                    connectionState.value = ConnectionState.DISCONNECTED
                    publishBpm(0)
                    connectedDevice.value = lockedDevice.value
                    if (userRequestedDisconnect || lockedDevice.value == null) {
                        connectedDevice.value = null
                        updateNotification("Bluetooth standby")
                    } else {
                        updateNotification("Mencoba reconnect ke ${lockedDevice.value?.name ?: "device"}...")
                        scheduleReconnect()
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return

            connectionState.value = ConnectionState.CONNECTED
            connectedDevice.value = connectedDevice.value?.copy(state = DeviceState.CONNECTED)
            updateNotification("Terhubung ke ${connectedDevice.value?.name ?: "device"}")
            try {
                gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
            } catch (e: SecurityException) { }

            // Subscribe heart rate notification
            val service = gatt.getService(HEART_RATE_SERVICE) ?: return
            val char    = service.getCharacteristic(HEART_RATE_CHAR) ?: return

            try {
                gatt.setCharacteristicNotification(char, true)
                val descriptor = char.getDescriptor(CLIENT_CHAR_CONFIG)
                descriptor?.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                if (descriptor != null) gatt.writeDescriptor(descriptor)
            } catch (e: SecurityException) {
                Log.e(TAG, "setNotification failed: ${e.message}")
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (gatt.device.address != currentDeviceAddress) {
                Log.d("BLE", "IGNORE OLD DEVICE: ${gatt.device.address}")
                return
            }

            if (characteristic.uuid == HEART_RATE_CHAR) {
                val payload = characteristic.value ?: return
                if (payload.isEmpty()) return

                val flags = payload[0].toInt()
                val isUint16 = flags and 0x01 != 0
                val sensorContactSupported = flags and 0x04 != 0
                val sensorContactDetected = flags and 0x02 != 0

                val measuredBpm = if (isUint16) {
                    characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT16, 1) ?: 0
                } else {
                    characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT8, 1) ?: 0
                }

                val currentBpm = bpmValue.value
                val effectiveBpm = when {
                    measuredBpm <= 0 -> 0
                    sensorContactSupported && !sensorContactDetected && currentBpm > 0 -> {
                        lastContactLostRawBpm = measuredBpm
                        0
                    }
                    sensorContactSupported && !sensorContactDetected &&
                            lastContactLostRawBpm == measuredBpm -> 0
                    else -> measuredBpm
                }

                if (effectiveBpm > 0) {
                    lastContactLostRawBpm = null
                }

                publishBpm(effectiveBpm)
                Log.d(
                    TAG,
                    "BPM raw=$measuredBpm effective=$effectiveBpm contactSupported=$sensorContactSupported contactDetected=$sensorContactDetected"
                )
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // INTERNAL
    // ══════════════════════════════════════════════════════════════════════

    private fun disconnectGatt() {
        try {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
            bluetoothGatt = null
        } catch (e: SecurityException) {
            Log.e(TAG, "disconnect failed: ${e.message}")
        }
    }

    private fun saveLockedDevice(device: BluetoothDeviceModel) {
        val locked = device.copy(state = DeviceState.CONNECTING)
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DEVICE_NAME, device.name)
            .putString(KEY_DEVICE_ADDRESS, device.address)
            .apply()
        lockedDevice.value = locked
        connectedDevice.value = locked
    }

    private fun loadLockedDevice(): BluetoothDeviceModel? {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val address = prefs.getString(KEY_DEVICE_ADDRESS, null)?.takeIf { it.isNotBlank() }
            ?: return null
        val name = prefs.getString(KEY_DEVICE_NAME, null).orEmpty()
        return BluetoothDeviceModel(
            name = name.ifBlank { "Heart Rate Device" },
            address = address,
            rssi = 0
        )
    }

    private fun clearLockedDevice() {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
        lockedDevice.value = null
    }

    private fun scheduleReconnect() {
        if (reconnectScheduled || userRequestedDisconnect || lockedDevice.value == null) return
        reconnectScheduled = true
        mainHandler.postDelayed(reconnectRunnable, RECONNECT_DELAY_MS)
    }

    private fun reconnectLockedDevice() {
        val device = lockedDevice.value ?: return
        if (userRequestedDisconnect) return
        if (connectionState.value == ConnectionState.CONNECTED ||
            connectionState.value == ConnectionState.CONNECTING
        ) return

        currentDeviceAddress = device.address
        connectionState.value = ConnectionState.CONNECTING
        connectedDevice.value = device.copy(state = DeviceState.CONNECTING)

        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) {
            connectionState.value = ConnectionState.DISCONNECTED
            scheduleReconnect()
            return
        }

        try {
            val rawDevice = adapter.getRemoteDevice(device.address)
            bluetoothGatt?.close()
            bluetoothGatt = rawDevice.connectGatt(this, false, gattCallback)
            updateNotification("Reconnect ke ${device.name.ifBlank { "device" }}...")
        } catch (e: Exception) {
            Log.e(TAG, "reconnect failed: ${e.message}")
            connectionState.value = ConnectionState.DISCONNECTED
            scheduleReconnect()
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // NOTIFICATION (Foreground Service wajib punya ini)
    // ══════════════════════════════════════════════════════════════════════

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIF_CHANNEL_ID,
                "Bluetooth Connection",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle("Personel Tracking")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_bluetooth)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIF_ID, buildNotification(text))
    }
}
