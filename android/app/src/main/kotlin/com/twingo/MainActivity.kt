package com.twingo

import android.Manifest.permission.BLUETOOTH_ADVERTISE
import android.Manifest.permission.BLUETOOTH_CONNECT
import android.Manifest.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE
import android.Manifest.permission.POST_NOTIFICATIONS
import android.annotation.SuppressLint
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
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.scale
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val ENABLE_BLUETOOTH_REQUEST_CODE = 1
private const val PERMISSIONS_REQUEST_CODE = 2

class MainActivity : AppCompatActivity() {
    private var activityResultHandlers = mutableMapOf<Int, (Int) -> Unit>()
    private val broadcastReceiver = Receiver()
    private val editTextCurrentMusicCharacteristic: EditText
        get() = findViewById(R.id.edit_current_music)
    private val grayscaleMusicCover: ImageView
        get() = findViewById(R.id.image_grayscale_music_cover)
    private val musicCover: ImageView
        get() = findViewById(R.id.image_music_cover)
    private var permissionResultHandler: ((Array<out String>, IntArray) -> Unit)? = null
    private val scrollViewLog: ScrollView
        get() = findViewById(R.id.scroll_logs)
    private var synchronizer: Synchronizer? = null
    private val textViewConnectionState: TextView
        get() = findViewById(R.id.text_connection_state)
    private val textViewLog: TextView
        get() = findViewById(R.id.text_logs)

    inner class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Synchronizer.INTENT_BITMAPS) {
                val bitmap = intent.getParcelableExtra(
                    Synchronizer.INTENT_BITMAPS_BITMAP,
                    Bitmap::class.java
                )
                val grayscaleBitmap =
                    intent.getParcelableExtra(
                        Synchronizer.INTENT_BITMAPS_GRAYSCALE_BITMAP,
                        Bitmap::class.java
                    )

                runOnUiThread {
                    musicCover.setImageBitmap(bitmap?.scale(128, 128))
                    grayscaleMusicCover.setImageBitmap(grayscaleBitmap?.scale(128, 128))
                }
            } else if (intent.action == Synchronizer.INTENT_LOG) {
                val level = intent.getIntExtra(Synchronizer.INTENT_LOG_LEVEL, Log.DEBUG)
                val message = intent.getStringExtra(Synchronizer.INTENT_LOG_MESSAGE)

                if (message != null) {
                    appendLog(message, level)
                }
            } else if (intent.action == Synchronizer.INTENT_NOTIFICATION_CANCELED) {
                finish()
            } else if (intent.action == Synchronizer.INTENT_NOTIFICATION_CLICKED) {
                synchronizer?.restartGattServer()
            } else if (intent.action == Synchronizer.INTENT_STATE) {
                val state = intent.getStringExtra(Synchronizer.INTENT_STATE_STATE)

                runOnUiThread {
                    if (state == Synchronizer.STATE_CENTRAL_CONNECTED) {
                        textViewConnectionState.text = getString(R.string.text_connected)
                    } else if (
                        state == Synchronizer.STATE_CENTRAL_DISCONNECTED ||
                        state == Synchronizer.STATE_GATT_SERVER_STOPPED
                    ) {
                        textViewConnectionState.text = getString(R.string.text_disconnected)
                    }
                }
            } else {
                appendLog("Unknown intent: ${intent.action}")
            }
        }
    }

    private val synchronizerConnection = object : ServiceConnection {

        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            if (service !is Synchronizer.LocalBinder) {
                return
            }
            appendLog("Synchronizer service connected")
            synchronizer = service.getService()
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            appendLog("Synchronizer service disconnected")
            synchronizer = null
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        activityResultHandlers[requestCode]?.let { handler ->
            handler(resultCode)
        } ?: run {
            appendLog("Error: onActivityResult requestCode=$requestCode result=$resultCode not handled")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        appendLog("Activity created")
        grantAppPermissions { isGranted ->
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

            intentFilter.addAction(Synchronizer.INTENT_BITMAPS)
            intentFilter.addAction(Synchronizer.INTENT_LOG)
            intentFilter.addAction(Synchronizer.INTENT_NOTIFICATION_CANCELED)
            intentFilter.addAction(Synchronizer.INTENT_NOTIFICATION_CLICKED)
            intentFilter.addAction(Synchronizer.INTENT_STATE)

            this.registerReceiver(broadcastReceiver, intentFilter, RECEIVER_EXPORTED)

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

    override fun onDestroy() {
        super.onDestroy()
        stopSynchronizer()
        unregisterReceiver(broadcastReceiver)
    }

    @Suppress("unused")
    fun onTapSend(view: View) {
        val synchronizer = synchronizer

        if (synchronizer != null) {
            val text = editTextCurrentMusicCharacteristic.text.toString()
            val data = text.toByteArray(Charsets.UTF_8)
            synchronizer.sendNotification(Synchronizer.UUID_CHARACTERISTIC_CURRENT_MUSIC, data)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        permissionResultHandler?.let { handler ->
            handler(permissions, grantResults)
        } ?: run {
            appendLog("Error: onRequestPermissionsResult requestCode=$requestCode not handled")
        }
    }

    @Suppress("unused")
    fun onTapClearLog(view: View) {
        textViewLog.text = ""
        appendLog("Logs cleared")
    }

    @SuppressLint("SetTextI18n")
    private fun appendLog(message: String, level: Int = Log.DEBUG) {
        Log.println(level, "appendLog", message)
        runOnUiThread {
            val strTime = SimpleDateFormat("mm.ss", Locale.getDefault()).format(Date())
            val text = SpannableString("[$strTime] $message\n")
            val color = when (level) {
                Log.DEBUG -> ForegroundColorSpan(Color.DKGRAY)
                Log.ERROR -> ForegroundColorSpan(Color.RED)
                Log.INFO -> ForegroundColorSpan(Color.rgb(126, 76, 245))
                Log.WARN -> ForegroundColorSpan(Color.rgb(210, 130, 0))
                else -> ForegroundColorSpan(Color.GRAY)
            }

            text.setSpan(color, 0, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            textViewLog.append(text)

            // scroll after delay, because textView has to be updated first
            Handler(Looper.getMainLooper()).postDelayed({
                scrollViewLog.fullScroll(View.FOCUS_DOWN)
            }, 16)
        }
    }

    private fun bindSynchronizerIfRunning() {
        Intent(this, Synchronizer::class.java).also { intent ->
            bindService(intent, synchronizerConnection, 0)
        }
    }

    private fun hasNotificationAccess(): Boolean {
        return Settings.Secure.getString(
            contentResolver, "enabled_notification_listeners"
        ).contains(packageName)
    }

    private fun enableBluetooth(completion: (Boolean) -> Unit) {
        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager

        if (bluetoothManager.adapter.isEnabled) {
            completion(true)
        } else {
            val intentString = BluetoothAdapter.ACTION_REQUEST_ENABLE
            val requestCode = ENABLE_BLUETOOTH_REQUEST_CODE

            // set activity result handler
            activityResultHandlers[requestCode] = { result ->
                val isSuccess = result == RESULT_OK

                if (isSuccess) {
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

    private fun ensureBluetoothCanBeUsed(completion: (Boolean, String) -> Unit) {
        enableBluetooth { isEnabled ->
            if (!isEnabled) {
                completion(false, "Bluetooth OFF")
                return@enableBluetooth
            }
            completion(true, "BLE ready for use")
        }
    }

    private fun grantAppPermissions(completion: (Boolean) -> Unit) {
        val permissions = arrayOf(
            BLUETOOTH_CONNECT, BLUETOOTH_ADVERTISE, FOREGROUND_SERVICE_CONNECTED_DEVICE,
            POST_NOTIFICATIONS
        )

        if (permissions.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
            completion(true)
        } else {
            runOnUiThread {
                appendLog("Permissions not granted, requesting")

                permissionResultHandler = { permissions, grantResults ->
                    val isSuccess = grantResults.all { it == PackageManager.PERMISSION_GRANTED }

                    if (isSuccess) {
                        appendLog("Permissions granted")
                        completion(true)
                    } else {
                        appendLog("Permissions not granted, requesting")
                        requestPermissions(permissions, PERMISSIONS_REQUEST_CODE)
                    }
                }
                requestPermissions(permissions, PERMISSIONS_REQUEST_CODE)
            }
        }
    }

    private fun startSynchronizer() {
        val intent = Intent(this, Synchronizer::class.java)
        startForegroundService(intent)
        bindSynchronizerIfRunning()
    }

    private fun stopSynchronizer() {
        val intent = Intent(this, Synchronizer::class.java)
        stopService(intent)
        synchronizer = null
    }
}