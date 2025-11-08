package com.twingo

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.core.graphics.set
import java.text.Normalizer
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.min

private const val DEBUG = false
private const val NOTIFICATION_CHANNEL_ID = "com.twingo.synchronizer.NOTIFICATION_CHANNEL_ID"
private const val REGISTER_CONTROLLER_INIT_INTERVAL_MS = 5000
private const val REGISTER_CONTROLLER_INTERVAL_MS = 10000
private const val RESTART_GATT_SERVER_DELAY_MS = 20000

class Synchronizer : Service() {
    companion object {
        const val INTENT_BITMAPS = "Twingo.Synchronizer.BITMAPS"
        const val INTENT_BITMAPS_BITMAP = "Twingo.Synchronizer.BITMAPS_BITMAP"
        const val INTENT_BITMAPS_GRAYSCALE_BITMAP = "Twingo.Synchronizer.BITMAPS_GRAYSCALE_BITMAP"
        const val INTENT_LOG = "Twingo.Synchronizer.LOG"
        const val INTENT_LOG_LEVEL = "Twingo.Synchronizer.LOG_IMPORTANT"
        const val INTENT_LOG_MESSAGE = "Twingo.Synchronizer.LOG_MESSAGE"
        const val INTENT_NOTIFICATION_CANCELED = "Twingo.Synchronizer.NOTIFICATION_CANCELED"
        const val INTENT_NOTIFICATION_CLICKED = "Twingo.Synchronizer.NOTIFICATION_CLICKED"
        const val INTENT_STATE = "Twingo.Synchronizer.STATE"
        const val INTENT_STATE_STATE = "Twingo.Synchronizer.STATE_STATE"
        const val STATE_CENTRAL_CONNECTED = "CENTRAL_CONNECTED"
        const val STATE_CENTRAL_DISCONNECTED = "CENTRAL_DISCONNECTED"
        const val STATE_GATT_SERVER_STOPPED = "GATT_SERVER_CLOSED"
        const val UUID_CHARACTERISTIC_CURRENT_MUSIC = "39394651-8477-4ffa-bc10-dfef56583a29"
        const val UUID_CHARACTERISTIC_MUSIC_COVER = "39394653-8477-4ffa-bc10-dfef56583a29"
        const val UUID_SERVICE = "39394650-8477-4ffa-bc10-dfef56583a29"
    }

    inner class LocalBinder : Binder() {
        fun getService(): Synchronizer = this@Synchronizer
    }

    private val binder = LocalBinder()
    private val bluetoothManager: BluetoothManager by lazy {
        getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
    }
    private var connectedDevice: BluetoothDevice? = null

    private var currentTitle: String? = null
    private var gattServer: BluetoothGattServer? = null
    private var handler: Handler? = null
    private val instance = this
    private var isAdvertising = false
    private var mediaController: MediaController? = null
    private val mediaSessionManager: MediaSessionManager by lazy {
        getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
    }
    private var mtu = 512

    private val advertiseSettings =
        AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM).setConnectable(true)
            .build()

    private val advertiseData = AdvertiseData.Builder()
        .setIncludeDeviceName(false) // don't include name, because if name size > 8 bytes, ADVERTISE_FAILED_DATA_TOO_LARGE
        .addServiceUuid(ParcelUuid(UUID.fromString(UUID_SERVICE))).build()

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            appendLog("Advertising started")
        }

        override fun onStartFailure(errorCode: Int) {
            val desc = when (errorCode) {
                ADVERTISE_FAILED_DATA_TOO_LARGE -> "\nADVERTISE_FAILED_DATA_TOO_LARGE"
                ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "\nADVERTISE_FAILED_TOO_MANY_ADVERTISERS"
                ADVERTISE_FAILED_ALREADY_STARTED -> "\nADVERTISE_FAILED_ALREADY_STARTED"
                ADVERTISE_FAILED_INTERNAL_ERROR -> "\nADVERTISE_FAILED_INTERNAL_ERROR"
                ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "\nADVERTISE_FAILED_FEATURE_UNSUPPORTED"
                else -> ""
            }
            appendLog("Advertise start failed: errorCode=$errorCode $desc", Log.ERROR)
            isAdvertising = false
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectedDevice = device
                appendLog("Central has connected ($status)", Log.INFO)
                appendLog("Device address: ${device.address}")

                handler?.removeCallbacks(restartGattServerTask)
                stopAdvertising()
                unregisterMediaController()
                handler?.postDelayed(
                    registerMediaControllerTask, REGISTER_CONTROLLER_INIT_INTERVAL_MS.toLong()
                )
                sendState(STATE_CENTRAL_CONNECTED)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connectedDevice = null
                appendLog("Central has disconnected ($status)", Log.INFO)
                appendLog("Device address: ${device.address}")
                unregisterMediaController()
                startAdvertising()
                sendState(STATE_CENTRAL_DISCONNECTED)
                handler?.postDelayed(restartGattServerTask, RESTART_GATT_SERVER_DELAY_MS.toLong())
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            appendLog("MTU changed: $mtu")
            instance.mtu = mtu
        }
    }

    private val mediaControllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            val newCurrentTitle = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            val bitmap = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)

            if (newCurrentTitle != currentTitle && bitmap != null) {
                currentTitle = newCurrentTitle
                appendLog("Current music changed", Log.INFO)
                sendCurrentMusic(metadata)
            } else if (DEBUG) {
                appendLog("Current music did not change")
            }
        }

        override fun onSessionDestroyed() {
            appendLog("Media session destroyed")
        }
    }

    val registerMediaControllerTask: Runnable = Runnable {
        registerMediaController()

        if (handler == null) {
            appendLog("Handler not initialised", Log.ERROR)
        }
        handler?.postDelayed(registerMediaControllerTask, REGISTER_CONTROLLER_INTERVAL_MS.toLong())
    }

    val restartGattServerTask: Runnable = Runnable {
        restartGattServer()
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onCreate() {
        appendLog("Start Synchronizer service")

        val notificationManager = getSystemService(NotificationManager::class.java)
        var notificationChannel =
            notificationManager.getNotificationChannel(NOTIFICATION_CHANNEL_ID)

        if (notificationChannel == null) {
            notificationChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID, "SynchronizerChannel", NotificationManager.IMPORTANCE_LOW
            )
            notificationChannel.description =
                "Channel for Twingo's Synchronizer foreground service notification"
            notificationManager.createNotificationChannel(notificationChannel)
        }

        val cancelIntent = Intent(this, BroadcastForwarder::class.java)
        val clickIntent = Intent(this, BroadcastForwarder::class.java)

        cancelIntent.action = INTENT_NOTIFICATION_CANCELED
        clickIntent.action = INTENT_NOTIFICATION_CLICKED

        val cancelPendingIntent =
            PendingIntent.getBroadcast(this, 0, cancelIntent, PendingIntent.FLAG_IMMUTABLE)

        val clickPendingIntent =
            PendingIntent.getBroadcast(this, 0, clickIntent, PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setOngoing(true)
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .setContentTitle("Twingo")
            .setContentText("Foreground service running")
            .setContentIntent(clickPendingIntent)
            .setDeleteIntent(cancelPendingIntent)
            .build()

        startForeground(1, notification, FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        startGattServer()
        startAdvertising()
        handler = Handler(Looper.getMainLooper())
    }

    override fun onDestroy() {
        handler?.removeCallbacks(restartGattServerTask)
        unregisterMediaController()
        stopAdvertising()
        stopGattServer()
    }

    fun sendNotification(uuid: String, data: ByteArray, split: Boolean = false) {
        val device = connectedDevice
        val server = gattServer

        if (server == null) {
            appendLog("Cannot send notification: GATT server not running", Log.ERROR)
            return
        }
        if (device == null) {
            appendLog("Cannot send notification: device not connected", Log.ERROR)
            return
        }

        val characteristic = server.getService(UUID.fromString(UUID_SERVICE))
            ?.getCharacteristic(UUID.fromString(uuid))

        if (characteristic != null) {
            if (split) {
                val notificationDataByteSize = mtu - 2 // 2 bytes to store offset
                val numberOfNotifications =
                    ceil(data.size.toDouble() / notificationDataByteSize).toInt()

                appendLog("Sending $numberOfNotifications notifications: ${data.size} bytes")

                for (index in 0..<numberOfNotifications) {
                    val offset = notificationDataByteSize * (numberOfNotifications - index - 1)

                    val notificationData = byteArrayOf(
                        (offset and 0xff).toByte(), ((offset shr 8) and 0xff).toByte()
                    ) + data.copyOfRange(
                        offset, min(offset + notificationDataByteSize, data.size)
                    )

                    server.notifyCharacteristicChanged(
                        device, characteristic, false, notificationData
                    )
                }
            } else {
                appendLog(
                    "Sending notification: \"${
                        data.toString(Charsets.UTF_8).replace("\n", "\\n")
                    }\""
                )
                server.notifyCharacteristicChanged(
                    device, characteristic, false, data
                )
            }
        }
    }

    fun restartGattServer() {
        appendLog("Restart GATT server", Log.INFO)
        handler?.removeCallbacks(restartGattServerTask)
        unregisterMediaController()
        stopAdvertising()
        stopGattServer()
        startGattServer()
        startAdvertising()
    }

    private fun appendLog(message: String, level: Int = Log.DEBUG) {
        val intent = Intent(INTENT_LOG)
        intent.putExtra(INTENT_LOG_LEVEL, level)
        intent.putExtra(INTENT_LOG_MESSAGE, message)
        this.sendBroadcast(intent)
    }

    private fun registerMediaController() {
        appendLog("Register media controller", Log.VERBOSE)

        val componentName = ComponentName(instance, NotificationListener::class.java)
        val controller = mediaSessionManager.getActiveSessions(componentName).getOrNull(0)

        mediaController?.unregisterCallback(mediaControllerCallback)
        mediaController = controller

        if (controller != null) {
            val metadata = controller.metadata
            val newCurrentTitle = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)

            if (newCurrentTitle != currentTitle) {
                currentTitle = newCurrentTitle
                appendLog("Current music changed", Log.INFO)
                sendCurrentMusic(metadata)
            }
            controller.registerCallback(mediaControllerCallback)
        } else {
            appendLog("No media controller found")

            if (currentTitle != "") {
                currentTitle = ""
                sendCurrentMusic(null)
            }
        }
    }

    private fun sendBitmaps(bitmap: Bitmap, grayscaleBitmap: Bitmap) {
        val intent = Intent(INTENT_BITMAPS)
        intent.putExtra(INTENT_BITMAPS_BITMAP, bitmap)
        intent.putExtra(INTENT_BITMAPS_GRAYSCALE_BITMAP, grayscaleBitmap)
        this.sendBroadcast(intent)
    }

    private fun sendState(state: String) {
        val intent = Intent(INTENT_STATE)
        intent.putExtra(INTENT_STATE_STATE, state)
        this.sendBroadcast(intent)
    }

    private fun startAdvertising() {
        if (!isAdvertising && connectedDevice == null) {
            appendLog("Start advertising")
            isAdvertising = true
            bluetoothManager.adapter.bluetoothLeAdvertiser.startAdvertising(
                advertiseSettings, advertiseData, advertiseCallback
            )
        }
    }

    private fun startGattServer() {
        appendLog("Start GATT server")

        this.gattServer = bluetoothManager.openGattServer(this, gattServerCallback)

        val service = BluetoothGattService(
            UUID.fromString(UUID_SERVICE), BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        val currentMusicCharacteristic = BluetoothGattCharacteristic(
            UUID.fromString(UUID_CHARACTERISTIC_CURRENT_MUSIC),
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        service.addCharacteristic(currentMusicCharacteristic)

        val musicCoverCharacteristic = BluetoothGattCharacteristic(
            UUID.fromString(UUID_CHARACTERISTIC_MUSIC_COVER),
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        service.addCharacteristic(musicCoverCharacteristic)

        val result = gattServer?.addService(service) ?: false

        if (result) {
            appendLog("GATT service added")
        } else {
            appendLog("GATT service not added", Log.ERROR)
        }
    }

    private fun stopAdvertising() {
        if (isAdvertising) {
            appendLog("Stop advertising")
            isAdvertising = false
            bluetoothManager.adapter.bluetoothLeAdvertiser.stopAdvertising(advertiseCallback)
        }
    }

    private fun stopGattServer() {
        if (gattServer != null) {
            appendLog("Stop GATT server")
            gattServer?.close()
            gattServer = null
            sendState(STATE_GATT_SERVER_STOPPED)
        } else {
            appendLog("Cannot stop GATT server: not running", Log.WARN)
        }
    }

    private fun sendCurrentMusic(metadata: MediaMetadata?) {
        appendLog("Send current music")

        if (connectedDevice == null) {
            appendLog("Cannot send current music: device not connected", Log.ERROR)
            return
        }
        var bitmap = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)

        if (metadata != null && bitmap != null) {
            val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
            val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            val music = Normalizer.normalize("$title\n$artist", Normalizer.Form.NFD)
                .replace("\\p{Mn}+".toRegex(), "") // Removes accents
                .replace("’", "'")

            val coverSize = 64

            bitmap = bitmap.scale(coverSize, coverSize)

            val pixels = IntArray(coverSize * coverSize)

            bitmap.getPixels(
                pixels, 0, coverSize, 0, 0, coverSize, coverSize
            )
            val lumR = DoubleArray(256)
            val lumG = DoubleArray(256)
            val lumB = DoubleArray(256)

            // Greyscale luminance
            for (i in 0..255) {
                lumR[i] = i * 0.299
                lumG[i] = i * 0.587
                lumB[i] = i * 0.114
            }

            val grayscaleData = IntArray(pixels.size)

            for ((index, pixel) in pixels.withIndex()) {
                val r = (pixel shr 16) and 0xff
                val g = (pixel shr 8) and 0xff
                val b = pixel and 0xff
                grayscaleData[index] = (lumR[r] + lumG[g] + lumB[b]).toInt()
            }

            val grayscaleDataBytes = ByteArray(pixels.size)
            val grayscaleBitmap = createBitmap(coverSize, coverSize, Bitmap.Config.ARGB_8888)

            for (index in grayscaleData.indices) {
                grayscaleDataBytes[index] = grayscaleData[index].toByte()
                grayscaleBitmap[index % coverSize, index / coverSize] =
                    (255 and 0xff) shl 24 or (grayscaleData[index] and 0xff) shl 16 or (grayscaleData[index] and 0xff) shl 8 or (grayscaleData[index] and 0xff)
            }

            sendBitmaps(bitmap, grayscaleBitmap)

            sendNotification(UUID_CHARACTERISTIC_CURRENT_MUSIC, music.toByteArray(Charsets.UTF_8))
            sendNotification(UUID_CHARACTERISTIC_MUSIC_COVER, grayscaleDataBytes, true)
        } else {
            sendNotification(UUID_CHARACTERISTIC_CURRENT_MUSIC, "".toByteArray(Charsets.UTF_8))
        }
    }

    private fun unregisterMediaController() {
        appendLog("Unregister media controller")
        handler?.removeCallbacks(registerMediaControllerTask)
        mediaController?.unregisterCallback(mediaControllerCallback)
        currentTitle = null
        mediaController = null
    }
}
