package com.twingo

import android.Manifest.permission.BLUETOOTH_ADVERTISE
import android.Manifest.permission.BLUETOOTH_CONNECT
import android.Manifest.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE
import android.Manifest.permission.POST_NOTIFICATIONS
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.graphics.scale
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val BLUETOOTH_ALL_PERMISSIONS_REQUEST_CODE = 2
private const val ENABLE_BLUETOOTH_REQUEST_CODE = 1

class MainActivity : AppCompatActivity() {
    private val bluetoothAdapter: BluetoothAdapter by lazy {
        bluetoothManager.adapter
    }
    private val bluetoothManager: BluetoothManager by lazy {
        getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
    }
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
    private var synchronizer: Synchronizer? = null
    private val synchronizerConnection = object : ServiceConnection {

        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            appendLog("Synchronizer service connected")
            synchronizer = (service as Synchronizer.LocalBinder).getService()
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            appendLog("Synchronizer service disconnected")
        }
    }

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == "Bitmaps") {
                val bitmap = intent.getParcelableExtra("bitmap", Bitmap::class.java)
                val grayscaleBitmap = intent.getParcelableExtra("grayscaleBitmap", Bitmap::class.java)

                runOnUiThread {
                    musicCover.setImageBitmap(bitmap?.scale(128, 128))
                    grayscaleMusicCover.setImageBitmap(grayscaleBitmap?.scale(128, 128))
                }
            } else if (intent.action == "Log") {
                val message = intent.getStringExtra("message")

                if (message != null) {
                    appendLog(message, "R")
                }
            } else if (intent.action == "State") {
                val state = intent.getStringExtra("state")

                runOnUiThread {
                    if (state == "CENTRAL_CONNECTED") {
                        textViewConnectionState.text = getString(R.string.textConnected)
                    } else if (state == "CENTRAL_DISCONNECTED" || state == "GATT_SERVER_CLOSED") {
                        textViewConnectionState.text = getString(R.string.textDisconnected)
                    }
                }
            }
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

            val intentFilter = IntentFilter()

            intentFilter.addAction("Bitmaps")
            intentFilter.addAction("Log")
            intentFilter.addAction("State")
            intentFilter.addAction("NotificationCanceled")

            LocalBroadcastManager.getInstance(this)
                .registerReceiver(broadcastReceiver, intentFilter)

            ensureBluetoothCanBeUsed { isSuccess, message ->
                runOnUiThread {
                    appendLog(message)

                    if (isSuccess) {
                        startSynchronizer()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        appendLog("Resume")
        synchronizer?.registerMediaController()
    }

    @Suppress("unused")
    fun onTapSend(view: View) {
        val synchronizer = synchronizer

        if (synchronizer != null) {
            val text = editTextCurrentMusicCharacteristic.text.toString()
            val data = text.toByteArray(Charsets.UTF_8)
            synchronizer.sendNotification(
                synchronizer.currentMusicCharacteristic.uuid.toString(), data
            )
        }
    }

    @Suppress("unused")
    fun onTapClearLog(view: View) {
        textViewLog.text = ""
        appendLog("Logs cleared")
    }

    @SuppressLint("SetTextI18n")
    private fun appendLog(message: String, tag: String = "A") {
        Log.d("appendLog", message)
        runOnUiThread {
            val strTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            textViewLog.text = textViewLog.text.toString() + "[$strTime] [$tag] $message\n"

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

    private fun startSynchronizer() {
        val intent = Intent(this, Synchronizer::class.java)

        startForegroundService(intent)
        bindSynchronizerIfRunning()
    }

    private fun bindSynchronizerIfRunning() {
        Intent(this, Synchronizer::class.java).also { intent ->
            bindService(intent, synchronizerConnection, 0)
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
                    @Suppress("DEPRECATION")
                    startActivityForResult(Intent(intentString), requestCode)
                }
            }

            // start activity for the request
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(intentString), requestCode)
        }
    }

    private fun grantAppPermissions(
        askType: AskType, completion: (Boolean) -> Unit
    ) {
        val wantedPermissions = arrayOf(
            BLUETOOTH_CONNECT, BLUETOOTH_ADVERTISE, FOREGROUND_SERVICE_CONNECTED_DEVICE,
            POST_NOTIFICATIONS
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
}