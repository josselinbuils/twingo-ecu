package com.twingo

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
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
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.os.Bundle
import android.os.Handler
import android.os.ParcelUuid
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.material.switchmaterial.SwitchMaterial
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

private const val BLUETOOTH_ALL_PERMISSIONS_REQUEST_CODE = 2

private const val ENABLE_BLUETOOTH_REQUEST_CODE = 1

private const val SERVICE_UUID = "39394650-8477-4ffa-bc10-dfef56583a29"
private const val CHARACTERISTIC_CURRENT_MUSIC_UUID = "39394651-8477-4ffa-bc10-dfef56583a29"
private const val DESCRIPTOR_CURRENT_MUSIC_UUID = "39394652-8477-4ffa-bc10-dfef56583a29"

class MainActivity : AppCompatActivity() {
    private val instance = this
    private val switchAdvertising: SwitchMaterial
        get() = findViewById(R.id.switchAdvertising)
    private val textViewLog: TextView
        get() = findViewById(R.id.textViewLog)
    private val scrollViewLog: ScrollView
        get() = findViewById(R.id.scrollViewLog)
    private val textViewConnectionState: TextView
        get() = findViewById(R.id.textViewConnectionState)
    private val editTextCurrentMusicCharacteristic: EditText
        get() = findViewById(R.id.editTextCurrentMusicCharacteristic)
    private val textViewSubscribers: TextView
        get() = findViewById(R.id.textViewSubscribers)

    private var currentMusic = ""

    private var isAdvertising = false
        set(value) {
            field = value

            // update visual state of the switch
            runOnUiThread {
                Handler().postDelayed({
                    if (value != switchAdvertising.isChecked) switchAdvertising.isChecked = value
                }, 200)
            }
        }

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

            switchAdvertising.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    prepareAndStartAdvertising()
                } else {
                    bleStopAdvertising()
                }
            }
        }
    }

    override fun onDestroy() {
        bleStopAdvertising()
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
            Handler().postDelayed({
                scrollViewLog.fullScroll(View.FOCUS_DOWN)
            }, 16)
        }
    }

    private fun hasNotificationAccess(): Boolean {
        return Settings.Secure.getString(
            contentResolver, "enabled_notification_listeners"
        ).contains(packageName)
    }

    private fun sendNotification(uuid: String, data: ByteArray) {
        if (subscribedDevice == null) {
            return
        }
        val characteristic = gattServer?.getService(UUID.fromString(SERVICE_UUID))
            ?.getCharacteristic(UUID.fromString(uuid))

        characteristic?.let {
            it.value = data
            appendLog("Sending notification: ${data.toString(Charsets.UTF_8)}")
            gattServer?.notifyCharacteristicChanged(subscribedDevice, it, false)
        }
    }

    private fun updateCurrentMusic(metadata: MediaMetadata?) {
        if (metadata != null) {
            val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
            val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            val music = "$artist - $title"

            if (currentMusic != music) {
                currentMusic = music

                sendNotification(
                    CHARACTERISTIC_CURRENT_MUSIC_UUID, currentMusic.toByteArray(Charsets.UTF_8)
                )
                appendLog("NowPlaying: $music")
            }
        } else if (currentMusic != "") {
            currentMusic = ""

            sendNotification(
                CHARACTERISTIC_CURRENT_MUSIC_UUID, "".toByteArray(Charsets.UTF_8)
            )
            appendLog("NowPlaying: none")
        }
    }

    private fun updateSubscribersUI() {
        runOnUiThread {
            textViewSubscribers.text = if (subscribedDevice !== null) "subscribed" else ""
        }
    }

    private fun prepareAndStartAdvertising() {
        ensureBluetoothCanBeUsed { isSuccess, message ->
            runOnUiThread {
                appendLog(message)
                if (isSuccess) {
                    bleStartAdvertising()
                } else {
                    isAdvertising = false
                }
            }
        }
    }

    private fun bleStartAdvertising() {
        isAdvertising = true
        bleStartGattServer()
        bleAdvertiser.startAdvertising(advertiseSettings, advertiseData, advertiseCallback)
    }

    private fun bleStopAdvertising() {
        isAdvertising = false
        bleStopGattServer()
        bleAdvertiser.stopAdvertising(advertiseCallback)
    }

    private fun bleStartGattServer() {
        val gattServer = bluetoothManager.openGattServer(this, gattServerCallback)
        val service = BluetoothGattService(
            UUID.fromString(SERVICE_UUID), BluetoothGattService.SERVICE_TYPE_PRIMARY
        )
        val currentMusicCharacteristic = BluetoothGattCharacteristic(
            UUID.fromString(CHARACTERISTIC_CURRENT_MUSIC_UUID),
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        val currentMusicDescriptor = BluetoothGattDescriptor(
            UUID.fromString(DESCRIPTOR_CURRENT_MUSIC_UUID),
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        currentMusicCharacteristic.addDescriptor(currentMusicDescriptor)

        service.addCharacteristic(currentMusicCharacteristic)

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
        appendLog("gattServer closed")
        runOnUiThread {
            textViewConnectionState.text = getString(R.string.text_disconnected)
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
            appendLog("Advertise start success\n$SERVICE_UUID")
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
    private var subscribedDevice: BluetoothDevice? = null

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            runOnUiThread {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    textViewConnectionState.text = getString(R.string.text_connected)
                    appendLog("Central did connect")
                } else {
                    textViewConnectionState.text = getString(R.string.text_disconnected)
                    appendLog("Central did disconnect")
                    subscribedDevice = null
                    updateSubscribersUI()
                    updateCurrentMusic(null)
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            appendLog("onMtuChanged mtu=$mtu")
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            appendLog("onNotificationSent status=$status")
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            var log = "onCharacteristicRead offset=$offset"
            if (characteristic.uuid == UUID.fromString(CHARACTERISTIC_CURRENT_MUSIC_UUID)) {
                runOnUiThread {
                    gattServer?.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        0,
                        currentMusic.toByteArray(Charsets.UTF_8)
                    )
                    log += "\nresponse=success, value=\"$currentMusic\""
                    appendLog(log)
                }
            } else {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                log += "\nresponse=failure, unknown UUID\n${characteristic.uuid}"
                appendLog(log)
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            var log =
                "onCharacteristicWrite offset=$offset responseNeeded=$responseNeeded preparedWrite=$preparedWrite"
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                log += "\nresponse=failure, unknown UUID\n${characteristic.uuid}"
            } else {
                log += "\nresponse=notNeeded, unknown UUID\n${characteristic.uuid}"
            }
            appendLog(log)
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor
        ) {
            var log = "onDescriptorReadRequest"
            if (descriptor.uuid == UUID.fromString(DESCRIPTOR_CURRENT_MUSIC_UUID)) {
                val returnValue = if (subscribedDevice != null) {
                    log += " DESCRIPTOR response=ENABLE_NOTIFICATION"
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                } else {
                    log += " DESCRIPTOR response=DISABLE_NOTIFICATION"
                    BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                }
                gattServer?.sendResponse(
                    device, requestId, BluetoothGatt.GATT_SUCCESS, 0, returnValue
                )
            } else {
                log += " unknown uuid=${descriptor.uuid}"
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
            }
            appendLog(log)
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            var strLog = "onDescriptorWriteRequest"

            if (descriptor.uuid == UUID.fromString(DESCRIPTOR_CURRENT_MUSIC_UUID)) {
                var status = BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED

                if (descriptor.characteristic.uuid == UUID.fromString(
                        CHARACTERISTIC_CURRENT_MUSIC_UUID
                    )
                ) {
                    if (value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                        subscribedDevice = device
                        status = BluetoothGatt.GATT_SUCCESS
                        strLog += ", subscribed"
                        appendLog(strLog)

                        if (responseNeeded) {
                            gattServer?.sendResponse(device, requestId, status, 0, null)
                        }
                        updateSubscribersUI()

                        val mediaSessionManager =
                            getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager

                        val componentName =
                            ComponentName(instance, NotificationListener::class.java)

                        val updateCurrentMusicFromController = { controller: MediaController? ->
                            if (controller != null) {
                                updateCurrentMusic(controller.metadata)

                                controller.registerCallback(object : MediaController.Callback() {
                                    override fun onMetadataChanged(metadata: MediaMetadata?) {
                                        updateCurrentMusic(metadata)
                                    }
                                })
                            } else {
                                appendLog("NowPlaying: no media controller found (0)")
                                updateCurrentMusic(null)
                            }
                        }

                        updateCurrentMusicFromController(
                            mediaSessionManager.getActiveSessions(componentName).getOrNull(0)
                        )

                        runOnUiThread {
                            mediaSessionManager.addOnActiveSessionsChangedListener(
                                { controllers ->
                                    updateCurrentMusicFromController(controllers?.getOrNull(0))
                                }, componentName
                            )
                        }


                    } else if (value.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)) {
                        subscribedDevice = null
                        status = BluetoothGatt.GATT_SUCCESS
                        strLog += ", unsubscribed"
                        appendLog(strLog)

                        if (responseNeeded) {
                            gattServer?.sendResponse(device, requestId, status, 0, null)
                        }
                        updateSubscribersUI()
                        updateCurrentMusic(null)
                    } else {
                        strLog += ", unknown status: ${value[0]} ${value[1]}"
                        appendLog(strLog)
                    }
                }
            } else {
                strLog += " unknown uuid=${descriptor.uuid}"
                appendLog(strLog)

                if (responseNeeded) {
                    gattServer?.sendResponse(
                        device, requestId, BluetoothGatt.GATT_FAILURE, 0, null
                    )
                }
            }
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