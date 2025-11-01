package com.twingo

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
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
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.core.graphics.set
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.min

private const val BLUETOOTH_ALL_PERMISSIONS_REQUEST_CODE = 2
private const val CHARACTERISTIC_CURRENT_MUSIC_UUID = "39394651-8477-4ffa-bc10-dfef56583a29"
private const val CHARACTERISTIC_MUSIC_COVER_UUID = "39394653-8477-4ffa-bc10-dfef56583a29"
private const val DEBUG = false
private const val ENABLE_BLUETOOTH_REQUEST_CODE = 1
private const val SERVICE_UUID = "39394650-8477-4ffa-bc10-dfef56583a29"

class MainActivity : AppCompatActivity() {
    private val instance = this
    private val editTextCurrentMusicCharacteristic: EditText
        get() = findViewById(R.id.editTextCurrentMusicCharacteristic)
    private val grayscaleMusicCover: ImageView
        get() = findViewById(R.id.grayscaleMusicCover)
    private val musicCover: ImageView
        get() = findViewById(R.id.musicCover)
    private val scrollViewLog: ScrollView
        get() = findViewById(R.id.scrollViewLog)
    private val textViewConnectionState: TextView
        get() = findViewById(R.id.textViewConnectionState)
    private val textViewLog: TextView
        get() = findViewById(R.id.textViewLog)

    private var currentTitle: String? = null
    private var isAdvertising = false
    private var mediaMetadata: MediaMetadata? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        appendLog("MainActivity.onCreate")

        grantAppPermissions(AskType.AskOnce) { isGranted ->
            if (!isGranted) {
                appendLog("⚠️Permission issue")
                return@grantAppPermissions
            }

            if (!hasNotificationAccess()) {
                appendLog("⚠️Notification service not enabled")

                try {
                    val settingsIntent =
                        Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
                    startActivity(settingsIntent)
                } catch (e: ActivityNotFoundException) {
                    e.printStackTrace()
                }
            }
            prepareAndStartAdvertising()
        }
    }

    override fun onResume() {
        super.onResume()
        appendLog("Resume")
        registerMediaController()
    }

    override fun onDestroy() {
        bleStopAdvertising()
        bleStopGattServer()
        super.onDestroy()
    }

    @Suppress("unused")
    fun onTapSend(view: View) {
        val text = editTextCurrentMusicCharacteristic.text.toString()
        val data = text.toByteArray(Charsets.UTF_8)
        sendNotification(CHARACTERISTIC_CURRENT_MUSIC_UUID, data)
    }

    @Suppress("unused")
    fun onTapClearLog(view: View) {
        textViewLog.text = ""
        appendLog("Logs cleared")
    }

    @SuppressLint("SetTextI18n")
    private fun appendLog(message: String) {
        Log.d("appendLog", message)
        runOnUiThread {
            val strTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            textViewLog.text = textViewLog.text.toString() + "\n$strTime $message"

            // scroll after delay, because textView has to be updated first
            Handler(Looper.getMainLooper()).postDelayed({
                scrollViewLog.fullScroll(View.FOCUS_DOWN)
            }, 16)
        }
    }

    private fun hasNotificationAccess(): Boolean {
        return Settings.Secure.getString(
            contentResolver, "enabled_notification_listeners"
        ).contains(packageName)
    }

    private fun registerMediaController() {
        val mediaSessionManager = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
        val componentName = ComponentName(instance, NotificationListener::class.java)
        val controller = mediaSessionManager.getActiveSessions(componentName).getOrNull(0)

        if (controller != null) {
            appendLog("Initial music metadata set")
            mediaMetadata = controller.metadata
            currentTitle = mediaMetadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            sendCurrentMusic()

            runOnUiThread {
                controller.registerCallback(object : MediaController.Callback() {
                    override fun onMetadataChanged(metadata: MediaMetadata?) {
                        mediaMetadata = metadata

                        val newCurrentTitle = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)

                        val bitmap = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)

                        if (newCurrentTitle != currentTitle && bitmap != null) {
                            appendLog("Current music changed")
                            currentTitle = newCurrentTitle
                            sendCurrentMusic()
                        } else if (DEBUG) {
                            appendLog("Current music did not change")
                        }
                    }

                    override fun onSessionDestroyed() {
                        appendLog("Media session destroyed")
                    }
                })

                mediaSessionManager.addOnActiveSessionsChangedListener(
                    { controllers ->
                        appendLog("New media session")
                        registerMediaController()
                    }, componentName
                )
            }
        } else {
            appendLog("No media controller found")
            mediaMetadata = null
        }
    }

    private fun sendNotification(uuid: String, data: ByteArray, split: Boolean = false) {
        val device = connectedDevice;
        val server = gattServer;

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

                for (index in 0..<numberOfNotifications) {
                    val offset = notificationDataByteSize * (numberOfNotifications - index - 1)

                    val notificationData = byteArrayOf(
                        (offset and 0xff).toByte(), ((offset shr 8) and 0xff).toByte()
                    ) + data.copyOfRange(
                        offset, min(offset + notificationDataByteSize, data.size)
                    )

                    appendLog("Sending notification: ${index + 1}/${numberOfNotifications} ${notificationData.size} bytes")
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

    private fun sendCurrentMusic() {
        val metadata = mediaMetadata

        if (connectedDevice == null) {
            return
        }

        if (metadata != null) {
            val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
            val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            val music = Normalizer.normalize("$title\n$artist", Normalizer.Form.NFD)
                .replace("\\p{Mn}+".toRegex(), "") // Removes accents

            sendNotification(
                CHARACTERISTIC_CURRENT_MUSIC_UUID, music.toByteArray(Charsets.UTF_8)
            )

            var bitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)

            if (bitmap != null) {
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
                    lumR[i] = i * 0.299;
                    lumG[i] = i * 0.587;
                    lumB[i] = i * 0.114;
                }

                val grayscaleData = IntArray(pixels.size)

                for ((index, pixel) in pixels.withIndex()) {
                    val r = (pixel shr 16) and 0xff
                    val g = (pixel shr 8) and 0xff
                    val b = pixel and 0xff
                    grayscaleData[index] = (lumR[r] + lumG[g] + lumB[b]).toInt()
                }

                val grayscaleDataBytes = ByteArray(pixels.size)
                val grayscaleBitmap = createBitmap(coverSize, coverSize, Bitmap.Config.ARGB_8888);

                for (index in grayscaleData.indices) {
                    grayscaleDataBytes[index] = grayscaleData[index].toByte()
                    grayscaleBitmap[index % coverSize, index / coverSize] =
                        (255 and 0xff) shl 24 or (grayscaleData[index] and 0xff) shl 16 or (grayscaleData[index] and 0xff) shl 8 or (grayscaleData[index] and 0xff)
                }

                runOnUiThread {
                    musicCover.setImageBitmap(bitmap)
                    grayscaleMusicCover.setImageBitmap(grayscaleBitmap)
                }
                sendNotification(CHARACTERISTIC_MUSIC_COVER_UUID, grayscaleDataBytes, true)
            } else {
                appendLog("No bitmap found")
            }
        } else {
            sendNotification(CHARACTERISTIC_CURRENT_MUSIC_UUID, "".toByteArray(Charsets.UTF_8))
        }
    }

    private fun prepareAndStartAdvertising() {
        ensureBluetoothCanBeUsed { isSuccess, message ->
            runOnUiThread {
                appendLog(message)

                if (isSuccess) {
                    bleStartGattServer()
                    bleStartAdvertising()
                }
            }
        }
    }

    private fun bleStartAdvertising() {
        if (!isAdvertising) {
            appendLog("Start advertising")
            isAdvertising = true
            bleAdvertiser.startAdvertising(advertiseSettings, advertiseData, advertiseCallback)
        }
    }

    private fun bleStopAdvertising() {
        if (isAdvertising) {
            appendLog("Stop advertising")
            isAdvertising = false
            bleAdvertiser.stopAdvertising(advertiseCallback)
        }
    }

    private fun bleStartGattServer() {
        appendLog("Start GATT Server")

        val gattServer = bluetoothManager.openGattServer(this, gattServerCallback)
        val service = BluetoothGattService(
            UUID.fromString(SERVICE_UUID), BluetoothGattService.SERVICE_TYPE_PRIMARY
        )
        val currentMusicCharacteristic = BluetoothGattCharacteristic(
            UUID.fromString(CHARACTERISTIC_CURRENT_MUSIC_UUID),
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        service.addCharacteristic(currentMusicCharacteristic)

        val musicCoverCharacteristic = BluetoothGattCharacteristic(
            UUID.fromString(CHARACTERISTIC_MUSIC_COVER_UUID),
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
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

    private fun bleStopGattServer() {
        gattServer?.close()
        gattServer = null
        appendLog("GATT server closed")
        runOnUiThread {
            textViewConnectionState.text = getString(R.string.textDisconnected)
        }
    }

    private val bluetoothManager: BluetoothManager by lazy {
        getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
    }

    private val bluetoothAdapter: BluetoothAdapter by lazy {
        bluetoothManager.adapter
    }

    //region BLE advertise
    private val bleAdvertiser by lazy {
        bluetoothAdapter.bluetoothLeAdvertiser
    }

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
    //endregion

    //region BLE GATT server
    private var gattServer: BluetoothGattServer? = null
    private var connectedDevice: BluetoothDevice? = null

    private var mtu = 512

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            runOnUiThread {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    textViewConnectionState.text = getString(R.string.textConnected)
                    appendLog("Central did connect")
                    connectedDevice = device
                    bleStopAdvertising()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    textViewConnectionState.text = getString(R.string.textDisconnected)
                    appendLog("Central did disconnect")
                    connectedDevice = null
                    bleStartAdvertising()
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            appendLog("MTU changed: $mtu")
            instance.mtu = mtu;
        }
    }

    enum class AskType {
        AskOnce, InsistUntilSuccess
    }

    private var activityResultHandlers = mutableMapOf<Int, (Int) -> Unit>()
    private var permissionResultHandlers =
        mutableMapOf<Int, (Array<out String>, IntArray) -> Unit>()

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        activityResultHandlers[requestCode]?.let { handler ->
            handler(resultCode)
        } ?: run {
            appendLog("Error: onActivityResult requestCode=$requestCode result=$resultCode not handled")
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        permissionResultHandlers[requestCode]?.let { handler ->
            handler(permissions, grantResults)
        } ?: run {
            appendLog("Error: onRequestPermissionsResult requestCode=$requestCode not handled")
        }
    }

    private fun ensureBluetoothCanBeUsed(completion: (Boolean, String) -> Unit) {
        enableBluetooth(AskType.AskOnce) { isEnabled ->
            if (!isEnabled) {
                completion(false, "Bluetooth OFF")
                return@enableBluetooth
            }
            completion(true, "BLE ready for use")
        }
    }

    private fun enableBluetooth(askType: AskType, completion: (Boolean) -> Unit) {
        if (bluetoothAdapter.isEnabled) {
            completion(true)
        } else {
            val intentString = BluetoothAdapter.ACTION_REQUEST_ENABLE
            val requestCode = ENABLE_BLUETOOTH_REQUEST_CODE

            // set activity result handler
            activityResultHandlers[requestCode] = { result ->
                val isSuccess = result == RESULT_OK
                if (isSuccess || askType != AskType.InsistUntilSuccess) {
                    activityResultHandlers.remove(requestCode)
                    completion(isSuccess)
                } else {
                    // start activity for the request again
                    startActivityForResult(Intent(intentString), requestCode)
                }
            }

            // start activity for the request
            startActivityForResult(Intent(intentString), requestCode)
        }
    }

    private fun grantAppPermissions(
        askType: AskType, completion: (Boolean) -> Unit
    ) {
        val wantedPermissions = arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE
        )

        if (hasPermissions(wantedPermissions)) {
            completion(true)
        } else {
            runOnUiThread {
                val requestCode = BLUETOOTH_ALL_PERMISSIONS_REQUEST_CODE

                permissionResultHandlers[requestCode] = { permissions, grantResults ->
                    val isSuccess = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
                    if (isSuccess || askType != AskType.InsistUntilSuccess) {
                        permissionResultHandlers.remove(requestCode)
                        completion(isSuccess)
                    } else {
                        requestPermissionArray(wantedPermissions, requestCode)
                    }
                }
                requestPermissionArray(wantedPermissions, requestCode)
            }
        }
    }

    private fun Context.hasPermissions(permissions: Array<String>): Boolean = permissions.all {
        ActivityCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun Activity.requestPermissionArray(permissions: Array<String>, requestCode: Int) {
        ActivityCompat.requestPermissions(this, permissions, requestCode)
    }
    //endregion
}