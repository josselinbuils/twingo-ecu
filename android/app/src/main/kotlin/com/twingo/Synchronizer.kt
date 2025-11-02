package com.twingo

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
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
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.app.NotificationCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.core.graphics.set
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.text.Normalizer
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.min


private const val CHARACTERISTIC_CURRENT_MUSIC_UUID = "39394651-8477-4ffa-bc10-dfef56583a29"
private const val CHARACTERISTIC_MUSIC_COVER_UUID = "39394653-8477-4ffa-bc10-dfef56583a29"
private const val DEBUG = false
private const val NOTIFICATION_CHANNEL_ID = "com.twingo.synchronizer.NOTIFICATION_CHANNEL_ID"
private const val REGISTER_CONTROLLER_INTERVAL_MS = 10000
private const val SERVICE_UUID = "39394650-8477-4ffa-bc10-dfef56583a29"

class Synchronizer : Service() {
    inner class LocalBinder : Binder() {
        fun getService(): Synchronizer = this@Synchronizer
    }

    val currentMusicCharacteristic = BluetoothGattCharacteristic(
        UUID.fromString(CHARACTERISTIC_CURRENT_MUSIC_UUID),
        BluetoothGattCharacteristic.PROPERTY_NOTIFY,
        BluetoothGattCharacteristic.PERMISSION_READ
    )
    val musicCoverCharacteristic = BluetoothGattCharacteristic(
        UUID.fromString(CHARACTERISTIC_MUSIC_COVER_UUID),
        BluetoothGattCharacteristic.PROPERTY_NOTIFY,
        BluetoothGattCharacteristic.PERMISSION_READ
    )

    private val advertiser by lazy {
        bluetoothAdapter.bluetoothLeAdvertiser
    }
    private val binder = LocalBinder()
    private val bluetoothAdapter: BluetoothAdapter by lazy {
        bluetoothManager.adapter
    }
    private val bluetoothManager: BluetoothManager by lazy {
        getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
    }
    private var connectedDevice: BluetoothDevice? = null

    private var currentTitle: String? = null
    private var gattServer: BluetoothGattServer? = null
    private val handlerThread = HandlerThread("background-handler-thread")
    private val instance = this
    private var isAdvertising = false
    private var mediaController: MediaController? = null
    private var registerMediaControllerHandler: Handler? = null
    private var mtu = 512

    private val advertiseSettings =
        AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM).setConnectable(true)
            .build()

    private val advertiseData = AdvertiseData.Builder()
        .setIncludeDeviceName(false) // don't include name, because if name size > 8 bytes, ADVERTISE_FAILED_DATA_TOO_LARGE
        .addServiceUuid(ParcelUuid(UUID.fromString(SERVICE_UUID))).build()

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            appendLog("Advertise start success")
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
            appendLog("Advertise start failed: errorCode=$errorCode $desc")
            isAdvertising = false
        }
    }

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            appendLog(intent.action.toString())
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                sendState("CENTRAL_CONNECTED")
                appendLog("Central did connect")
                registerMediaControllerHandler?.removeCallbacks(registerMediaControllerTask)
                mediaController?.unregisterCallback(mediaControllerCallback)
                currentTitle = null
                mediaController = null
                connectedDevice = device
                stopAdvertising()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                sendState("CENTRAL_DISCONNECTED")
                appendLog("Central did disconnect")
                registerMediaControllerHandler?.removeCallbacks(registerMediaControllerTask)
                mediaController?.unregisterCallback(mediaControllerCallback)
                connectedDevice = null
                currentTitle = null
                mediaController = null
                startAdvertising()
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            appendLog("MTU changed: $mtu")
            instance.mtu = mtu
            registerMediaControllerTask.run() // Device is ready to receive messages
        }
    }

    private val mediaControllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            val newCurrentTitle = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            val bitmap = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)

            if (newCurrentTitle != currentTitle && bitmap != null) {
                appendLog("Current music changed")
                currentTitle = newCurrentTitle
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

        if (registerMediaControllerHandler == null) {
            appendLog("registerMediaControllerHandler not initialised")
        }
        registerMediaControllerHandler?.postDelayed(
            { registerMediaControllerTask.run() }, REGISTER_CONTROLLER_INTERVAL_MS.toLong()
        )
    }

    override fun onBind(intent: Intent?): IBinder? {
        return binder
    }

    override fun onDestroy() {
        super.onDestroy()

        registerMediaControllerHandler?.removeCallbacks(registerMediaControllerTask)
        handlerThread.quitSafely()
        mediaController?.unregisterCallback(mediaControllerCallback)
        stopAdvertising()
        gattServer?.close()
        connectedDevice = null
        currentTitle = null
        mediaController = null
        gattServer = null
        appendLog("GATT server closed")
        sendState("GATT_SERVER_CLOSED")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForegroundService()
        return START_STICKY
    }

    private fun appendLog(message: String) {
        val intent = Intent("Log")
        intent.putExtra("message", message)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    fun registerMediaController() {
        appendLog("Register media controller")

        val mediaSessionManager = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
        val componentName = ComponentName(instance, NotificationListener::class.java)
        val controller = mediaSessionManager.getActiveSessions(componentName).getOrNull(0)

        mediaController?.unregisterCallback(mediaControllerCallback)
        mediaController = controller

        if (controller != null) {
            val metadata = controller.metadata
            val newCurrentTitle = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)

            if (newCurrentTitle != currentTitle) {
                appendLog("Current music changed")
                currentTitle = newCurrentTitle
                sendCurrentMusic(metadata)
            }
            controller.registerCallback(mediaControllerCallback)
        } else {
            appendLog("No media controller found")
        }
    }

    private fun sendBitmaps(bitmap: Bitmap, grayscaleBitmap: Bitmap) {
        val intent = Intent("Bitmaps")
        intent.putExtra("bitmap", bitmap)
        intent.putExtra("grayscaleBitmap", grayscaleBitmap)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    fun sendNotification(uuid: String, data: ByteArray, split: Boolean = false) {
        val device = connectedDevice
        val server = gattServer

        if (device == null || server == null) {
            return
        }

        val characteristic = server.getService(UUID.fromString(SERVICE_UUID))
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
                appendLog("Sending notification: \"${data.toString(Charsets.UTF_8)}\"")
                server.notifyCharacteristicChanged(
                    device, characteristic, false, data
                )
            }
        }
    }

    private fun sendState(state: String) {
        val intent = Intent("State")
        intent.putExtra("state", state)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun startAdvertising() {
        if (!isAdvertising) {
            appendLog("Start advertising")
            isAdvertising = true
            advertiser.startAdvertising(advertiseSettings, advertiseData, advertiseCallback)
        }
    }

    private fun startAsForegroundService() {
        appendLog("Start service")

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

        val pendingIntent = PendingIntent.getBroadcast(
            this, 0, Intent("NotificationCanceled"),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setOngoing(true)
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .setContentTitle("Twingo")
            .setContentText("Foreground service running")
            .setDeleteIntent(pendingIntent)
            .build()

        startForeground(1, notification, FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)

        if (gattServer == null) {
            startGattServer()
            startAdvertising()
            handlerThread.start()
            registerMediaControllerHandler = Handler(Looper.getMainLooper())
            LocalBroadcastManager.getInstance(this).registerReceiver(
                broadcastReceiver, IntentFilter("NotificationCanceled")
            )
        } else {
            if (connectedDevice != null) {
                sendState("CENTRAL_CONNECTED")
                appendLog("Central is connected")
                registerMediaControllerTask.run()
            } else {
                sendState("CENTRAL_DISCONNECTED")
                appendLog("Central is not connected")
            }
        }
    }

    private fun startGattServer() {
        appendLog("Start GATT Server")

        val gattServer = bluetoothManager.openGattServer(this, gattServerCallback)
        val service = BluetoothGattService(
            UUID.fromString(SERVICE_UUID), BluetoothGattService.SERVICE_TYPE_PRIMARY
        )
        service.addCharacteristic(currentMusicCharacteristic)
        service.addCharacteristic(musicCoverCharacteristic)

        val result = gattServer.addService(service)

        this.gattServer = gattServer

        appendLog(
            "addService " + when (result) {
                true -> "OK"
                false -> "fail"
            }
        )
    }

    private fun stopAdvertising() {
        if (isAdvertising) {
            appendLog("Stop advertising")
            isAdvertising = false
            advertiser.stopAdvertising(advertiseCallback)
        }
    }

    private fun sendCurrentMusic(metadata: MediaMetadata?) {
        if (connectedDevice == null) {
            return
        }
        var bitmap = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)

        if (metadata != null && bitmap != null) {
            val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
            val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            val music = Normalizer.normalize("$title\n$artist", Normalizer.Form.NFD)
                .replace("\\p{Mn}+".toRegex(), "") // Removes accents

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

            sendNotification(CHARACTERISTIC_CURRENT_MUSIC_UUID, music.toByteArray(Charsets.UTF_8))
            sendNotification(CHARACTERISTIC_MUSIC_COVER_UUID, grayscaleDataBytes, true)
        } else {
            sendNotification(CHARACTERISTIC_CURRENT_MUSIC_UUID, "".toByteArray(Charsets.UTF_8))
        }
    }
}