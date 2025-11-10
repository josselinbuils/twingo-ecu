package com.twingo

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
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
import android.location.LocationManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.net.Uri
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.app.NotificationCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.core.graphics.set
import com.android.volley.RequestQueue
import com.android.volley.toolbox.StringRequest
import com.android.volley.toolbox.Volley
import com.twingo.lib.LogLevel
import java.text.Normalizer
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.min
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.charset.StandardCharsets
import java.util.function.Consumer

private const val DEBUG = false
private const val DEVICE_GATT_INIT_PERIOD_MS = 5000
private const val NOTIFICATION_CHANNEL_ID = "com.twingo.synchronizer.NOTIFICATION_CHANNEL_ID"
private const val REFRESH_LOCATION_PERIOD_MS = 10000
private const val REGISTER_CONTROLLER_PERIOD_MS = 10000
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
        const val INTENT_NOTIFICATION_ACTION_RESTART =
            "Twingo.Synchronizer.NOTIFICATION_ACTION_RESTART"
        const val INTENT_STATE = "Twingo.Synchronizer.STATE"
        const val INTENT_STATE_STATE = "Twingo.Synchronizer.STATE_STATE"
        const val STATE_CENTRAL_CONNECTED = "CENTRAL_CONNECTED"
        const val STATE_CENTRAL_DISCONNECTED = "CENTRAL_DISCONNECTED"
        const val STATE_GATT_SERVER_STOPPED = "GATT_SERVER_CLOSED"
        const val UUID_CHARACTERISTIC_CURRENT_MUSIC = "39394651-8477-4ffa-bc10-dfef56583a29"
        const val UUID_CHARACTERISTIC_MUSIC_COVER = "39394652-8477-4ffa-bc10-dfef56583a29"
        const val UUID_CHARACTERISTIC_PING = "39394653-8477-4ffa-bc10-dfef56583a29"
        const val UUID_CHARACTERISTIC_SPEED_LIMIT = "39394654-8477-4ffa-bc10-dfef56583a29"
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
    private var mtu = 512
    private var requestQueue: RequestQueue? = null

    private val advertiseSettings =
        AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM).setConnectable(true)
            .build()

    private val advertiseData = AdvertiseData.Builder()
        .setIncludeDeviceName(false) // don't include name, because if name size > 8 bytes, ADVERTISE_FAILED_DATA_TOO_LARGE
        .addServiceUuid(ParcelUuid(UUID.fromString(UUID_SERVICE))).build()

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            log("Advertising started")
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
            log("Advertise start failed: errorCode=$errorCode $desc", LogLevel.ERROR)
            isAdvertising = false
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectedDevice = device
                log("Central has connected", LogLevel.INFO)
                log("Device address: ${device.address}")

                handler?.removeCallbacks(restartGattServerTask)
                stopAdvertising()
                handler?.removeCallbacksAndMessages(null)
                unregisterMediaController()
                handler?.postDelayed(
                    registerMediaControllerTask, DEVICE_GATT_INIT_PERIOD_MS.toLong()
                )
                handler?.postDelayed(refreshLocationTask, DEVICE_GATT_INIT_PERIOD_MS.toLong())
                sendState(STATE_CENTRAL_CONNECTED)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connectedDevice = null
                log("Central has disconnected", LogLevel.INFO)
                log("Device address: ${device.address}")
                handler?.removeCallbacksAndMessages(null)
                unregisterMediaController()
                startAdvertising()
                sendState(STATE_CENTRAL_DISCONNECTED)
                handler?.postDelayed(restartGattServerTask, RESTART_GATT_SERVER_DELAY_MS.toLong())
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == UUID.fromString(UUID_CHARACTERISTIC_PING)) {
                log("Ping received", LogLevel.VERBOSE)
                gattServer?.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_SUCCESS,
                    0,
                    byteArrayOf(1)
                )
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            log("MTU changed: $mtu")
            instance.mtu = mtu
        }
    }

    private val mediaControllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            val newCurrentTitle = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            val bitmap = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)

            if (newCurrentTitle != currentTitle && bitmap != null) {
                currentTitle = newCurrentTitle
                log("Current music changed", LogLevel.INFO)
                sendCurrentMusic(metadata)
            } else if (DEBUG) {
                log("Current music did not change")
            }
        }

        override fun onSessionDestroyed() {
            log("Media session destroyed")
        }
    }

    private val refreshLocationTask: Runnable = Runnable {
        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager

        locationManager.getCurrentLocation(
            LocationManager.GPS_PROVIDER,
            null,
            application.mainExecutor,
            { location ->
                if (location != null) {
                    log("Location: ${location.latitude}, ${location.longitude}", LogLevel.VERBOSE)

                    val request = StringRequest(
                        "https://overpass-api.de/api/interpreter?data=${Uri.encode("[out:json][timeout:3000];way(around:5,${location.latitude}, ${location.longitude})[maxspeed];out;")}",
                        { response ->
                            val jsonObject = Json.parseToJsonElement(response).jsonObject
                            val elements = jsonObject.getValue("elements").jsonArray

                            if (!elements.isEmpty()) {
                                val tags = elements[0].jsonObject.getValue("tags").jsonObject
                                val maxSpeed = tags.getValue("maxspeed").jsonPrimitive.content

                                log("Max speed: $maxSpeed", LogLevel.VERBOSE)
                                sendNotification(
                                    UUID_CHARACTERISTIC_SPEED_LIMIT,
                                    maxSpeed.toByteArray(Charsets.UTF_8)
                                )
                            } else {
                                log("No max speed found", LogLevel.VERBOSE)
                                sendNotification(
                                    UUID_CHARACTERISTIC_SPEED_LIMIT, "".toByteArray(Charsets.UTF_8)
                                )
                            }
                        },
                        { error ->
                            val statusCode = error.networkResponse.statusCode

                            if (statusCode != 504) {
                                val statusText =
                                    String(error.networkResponse.data, StandardCharsets.UTF_8)

                                log("Overpass API error: $statusCode $statusText", LogLevel.ERROR)
                            }
                        }
                    )
                    requestQueue?.add(request)
                } else {
                    log("Unable to get current location", LogLevel.VERBOSE)
                }
            }
        )
        handler?.postDelayed(refreshLocationTask, REFRESH_LOCATION_PERIOD_MS.toLong())
    }

    private val registerMediaControllerTask: Runnable = Runnable {
        registerMediaController()

        if (handler == null) {
            log("Handler not initialised", LogLevel.ERROR)
        }
        handler?.postDelayed(registerMediaControllerTask, REGISTER_CONTROLLER_PERIOD_MS.toLong())
    }

    private val restartGattServerTask: Runnable = Runnable {
        restartGattServer()
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onCreate() {
        log("Start Synchronizer service")

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
        val clickIntent = Intent(this, MainActivity::class.java)
        val restartIntent = Intent(this, BroadcastForwarder::class.java)

        cancelIntent.action = INTENT_NOTIFICATION_CANCELED
        restartIntent.action = INTENT_NOTIFICATION_ACTION_RESTART

        val cancelPendingIntent =
            PendingIntent.getBroadcast(this, 0, cancelIntent, PendingIntent.FLAG_IMMUTABLE)

        val clickPendingIntent =
            PendingIntent.getActivity(baseContext, 0, clickIntent, PendingIntent.FLAG_IMMUTABLE)

        val restartPendingIntent =
            PendingIntent.getBroadcast(this, 0, restartIntent, PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setOngoing(true)
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .setContentTitle("Twingo")
            .setContentText("Foreground service running")
            .addAction(
                R.mipmap.ic_launcher_round,
                getString(R.string.button_restart),
                restartPendingIntent
            )
            .setContentIntent(clickPendingIntent)
            .setDeleteIntent(cancelPendingIntent)
            .build()

        startForeground(1, notification, FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)

        handler = Handler(Looper.getMainLooper())
        requestQueue = Volley.newRequestQueue(this)

        startGattServer()
        startAdvertising()
    }

    override fun onDestroy() {
        handler?.removeCallbacksAndMessages(null)
        unregisterMediaController()
        stopAdvertising()
        stopGattServer()
    }

    fun sendNotification(uuid: String, data: ByteArray, split: Boolean = false) {
        val device = connectedDevice
        val server = gattServer

        if (server == null) {
            log("Cannot send notification: GATT server not running", LogLevel.ERROR)
            return
        }
        if (device == null) {
            log("Cannot send notification: device not connected", LogLevel.ERROR)
            return
        }

        val characteristic = server.getService(UUID.fromString(UUID_SERVICE))
            ?.getCharacteristic(UUID.fromString(uuid))

        if (characteristic != null) {
            if (split) {
                val notificationDataByteSize = mtu - 2 // 2 bytes to store offset
                val numberOfNotifications =
                    ceil(data.size.toDouble() / notificationDataByteSize).toInt()

                log("Sending $numberOfNotifications notifications: ${data.size} bytes")

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
                log(
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
        log("Restart GATT server", LogLevel.INFO)
        handler?.removeCallbacksAndMessages(null)
        unregisterMediaController()
        stopAdvertising()
        stopGattServer()
        startGattServer()
        startAdvertising()
    }

    private fun log(message: String, level: LogLevel = LogLevel.DEBUG) {
        val intent = Intent(INTENT_LOG)
        intent.putExtra(INTENT_LOG_LEVEL, level.name)
        intent.putExtra(INTENT_LOG_MESSAGE, message)
        this.sendBroadcast(intent)
    }

    private fun registerMediaController() {
        log("Register media controller", LogLevel.VERBOSE)

        val mediaSessionManager = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
        val componentName = ComponentName(instance, NotificationListener::class.java)
        val controller = mediaSessionManager.getActiveSessions(componentName).getOrNull(0)

        mediaController?.unregisterCallback(mediaControllerCallback)
        mediaController = controller

        if (controller != null) {
            val metadata = controller.metadata
            val newCurrentTitle = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)

            if (newCurrentTitle != currentTitle) {
                currentTitle = newCurrentTitle
                log("Current music changed", LogLevel.INFO)
                sendCurrentMusic(metadata)
            }
            controller.registerCallback(mediaControllerCallback)
        } else {
            log("No media controller found", LogLevel.VERBOSE)

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

    private fun sendCurrentMusic(metadata: MediaMetadata?) {
        log("Send current music")

        if (connectedDevice == null) {
            log("Cannot send current music: device not connected", LogLevel.ERROR)
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

    private fun sendState(state: String) {
        val intent = Intent(INTENT_STATE)
        intent.putExtra(INTENT_STATE_STATE, state)
        this.sendBroadcast(intent)
    }

    private fun startAdvertising() {
        if (!isAdvertising && connectedDevice == null) {
            log("Start advertising")
            isAdvertising = true
            bluetoothManager.adapter.bluetoothLeAdvertiser.startAdvertising(
                advertiseSettings, advertiseData, advertiseCallback
            )
        }
    }

    private fun startGattServer() {
        log("Start GATT server")

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

        val pingCharacteristic = BluetoothGattCharacteristic(
            UUID.fromString(UUID_CHARACTERISTIC_PING),
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        service.addCharacteristic(pingCharacteristic)

        val speedLimitCharacteristic = BluetoothGattCharacteristic(
            UUID.fromString(UUID_CHARACTERISTIC_SPEED_LIMIT),
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        service.addCharacteristic(speedLimitCharacteristic)

        val result = gattServer?.addService(service) ?: false

        if (result) {
            log("GATT service added")
        } else {
            log("GATT service not added", LogLevel.ERROR)
        }
    }

    private fun stopAdvertising() {
        if (isAdvertising) {
            log("Stop advertising")
            isAdvertising = false
            bluetoothManager.adapter.bluetoothLeAdvertiser.stopAdvertising(advertiseCallback)
        }
    }

    private fun stopGattServer() {
        if (gattServer != null) {
            log("Stop GATT server")
            gattServer?.close()
            gattServer = null
            sendState(STATE_GATT_SERVER_STOPPED)
        } else {
            log("Cannot stop GATT server: not running", LogLevel.WARNING)
        }
    }

    private fun unregisterMediaController() {
        log("Unregister media controller")
        mediaController?.unregisterCallback(mediaControllerCallback)
        currentTitle = null
        mediaController = null
    }
}
