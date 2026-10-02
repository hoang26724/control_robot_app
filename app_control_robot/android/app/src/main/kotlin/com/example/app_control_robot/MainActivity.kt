package com.example.app_control_robot

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Classic Bluetooth (RFCOMM / SPP) host for the Flutter app.
 *
 * There is no third-party plugin for this, so the socket is opened here and
 * exposed over a MethodChannel. Link events travel the other way on an
 * EventChannel, because the Flutter side has to know the moment the socket drops
 * so it can stop the motors.
 *
 * Pairing is deliberately *not* implemented: only the system Bluetooth settings
 * may pair a device, so `openBluetoothSettings` is exposed instead and the UI
 * sends the user there.
 */
class MainActivity : FlutterActivity() {

    private companion object {
        const val METHOD_CHANNEL = "com.example.app_control_robot/bluetooth"
        const val EVENT_CHANNEL = "com.example.app_control_robot/bluetooth/events"
        const val PERMISSION_REQUEST_CODE = 4242

        /** Standard Bluetooth Serial Port Profile service UUID. */
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        /** RFCOMM read chunk. Commands are two bytes; this only bounds the copy. */
        const val READ_BUFFER_BYTES = 128
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val session = AtomicInteger(0)

    private var events: EventChannel.EventSink? = null

    @Volatile
    private var socket: BluetoothSocket? = null
    private var readerThread: HandlerThread? = null

    /** Resumes the method call that asked for a runtime permission. */
    private var pendingPermission: ((Boolean) -> Unit)? = null

    private val adapter: BluetoothAdapter?
        get() = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothAdapter

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, METHOD_CHANNEL)
            .setMethodCallHandler { call, result -> onMethodCall(call, result) }

        EventChannel(flutterEngine.dartExecutor.binaryMessenger, EVENT_CHANNEL)
            .setStreamHandler(object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, sink: EventChannel.EventSink?) {
                    events = sink
                }

                override fun onCancel(arguments: Any?) {
                    events = null
                }
            })
    }

    private fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "ensureReady" -> withReadyBluetooth(result) { ensureReady(result) }
            "pairedDevices" -> withReadyBluetooth(result) { pairedDevices(result) }
            "openBluetoothSettings" -> openBluetoothSettings(result)
            "connect" -> connect(call, result)
            "disconnect" -> {
                closeSession()
                result.success(null)
            }
            "write" -> write(call, result)
            else -> result.notImplemented()
        }
    }

    // ---- Readiness and permissions ----------------------------------------

    /**
     * Runs [body] once Bluetooth is present, enabled, and permitted.
     *
     * Asking for the runtime permission is asynchronous, so the method call
     * cannot be answered inline: the caller's [result] is parked in
     * [pendingPermission] and the system dialog decides whether [body] ever
     * runs.
     */
    private fun withReadyBluetooth(
        result: MethodChannel.Result,
        body: (BluetoothAdapter) -> Unit
    ) {
        val a = adapter
        if (a == null) {
            result.error("no_adapter", "Máy này không có Bluetooth.", null)
            return
        }
        if (!hasPermissions()) {
            if (pendingPermission != null) {
                result.error("busy", "Đang chờ bạn cấp quyền Bluetooth.", null)
                return
            }
            pendingPermission = { granted ->
                if (granted) {
                    afterPermission(a, result, body)
                } else {
                    result.error(
                        "permission_denied",
                        "Cần cấp quyền Bluetooth để điều khiển robot.",
                        null
                    )
                }
            }
            ActivityCompat.requestPermissions(
                this,
                requiredPermissions(),
                PERMISSION_REQUEST_CODE
            )
            return
        }
        afterPermission(a, result, body)
    }

    private fun afterPermission(
        a: BluetoothAdapter,
        result: MethodChannel.Result,
        body: (BluetoothAdapter) -> Unit
    ) {
        if (!a.isEnabled) {
            result.error("disabled", "Hãy bật Bluetooth rồi thử lại.", null)
            return
        }
        body(a)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != PERMISSION_REQUEST_CODE) {
            return
        }
        val resume = pendingPermission ?: return
        pendingPermission = null
        resume(hasPermissions())
    }

    /**
     * Android 12 split Bluetooth into runtime permissions. Older releases only
     * need a location grant, and only to read the bond list at all — Bluetooth
     * itself is a normal install-time permission there.
     */
    private fun requiredPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.BLUETOOTH_SCAN
        )
    } else {
        arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun hasPermissions(): Boolean = requiredPermissions().all { permission ->
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    // ---- Method handlers --------------------------------------------------

    private fun ensureReady(result: MethodChannel.Result) {
        if (adapter?.bondedDevices.isNullOrEmpty()) {
            result.error(
                "no_devices",
                "Chưa có robot nào được ghép nối. Mở Cài đặt Bluetooth và ghép nối ESP32_ROBOT (mã 1234).",
                null
            )
            return
        }
        result.success(null)
    }

    private fun pairedDevices(result: MethodChannel.Result) {
        val bonded = adapter?.bondedDevices ?: emptySet()
        val devices = bonded.map { device ->
            mapOf(
                "address" to device.address,
                // Reading the name needs BLUETOOTH_CONNECT on API 31+, which
                // withReadyBluetooth() has already checked. A device with no
                // name reports null and Dart falls back to the address.
                "name" to (runCatching { device.name }.getOrNull() ?: "")
            )
        }
        result.success(devices)
    }

    private fun openBluetoothSettings(result: MethodChannel.Result) {
        try {
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            result.success(null)
        } catch (error: Exception) {
            result.error("settings_failed", error.message, null)
        }
    }

    private fun connect(call: MethodCall, result: MethodChannel.Result) {
        val address = call.argument<String>("address")
        if (address.isNullOrBlank()) {
            result.error("bad_address", "Thiết bị không hợp lệ.", null)
            return
        }
        withReadyBluetooth(result) { a -> openSocket(a, address, result) }
    }

    private fun openSocket(a: BluetoothAdapter, address: String, result: MethodChannel.Result) {
        val device = a.bondedDevices.firstOrNull { it.address == address }
        if (device == null) {
            result.error(
                "not_paired",
                "Robot này không còn trong danh sách đã ghép nối.",
                null
            )
            return
        }

        closeSession()
        val id = session.incrementAndGet()

        // BluetoothSocket.connect() blocks for seconds while it pairs and
        // authenticates, so it must never run on the platform thread.
        Thread {
            var opened: BluetoothSocket? = null
            try {
                a.cancelDiscovery()
                opened = a.getRemoteDevice(address).createRfcommSocketToServiceRecord(SPP_UUID)
                opened.connect()

                // A newer connect() may have replaced this one while we were
                // blocked; drop the socket we just opened rather than clobber
                // the live one.
                if (id != session.get()) {
                    runCatching { opened.close() }
                    mainHandler.post { result.success(null) }
                    return@Thread
                }

                socket = opened
                startReader(id, opened.inputStream)
                mainHandler.post {
                    emit(mapOf("type" to "message", "text" to "Đã nối $address"))
                    result.success(null)
                }
            } catch (error: IOException) {
                runCatching { opened?.close() }
                if (id == session.get()) {
                    socket = null
                }
                mainHandler.post {
                    result.error(
                        "connect_failed",
                        "Không nối được với robot: ${error.message ?: "bị từ chối"}",
                        null
                    )
                }
            }
        }.start()
    }

    private fun write(call: MethodCall, result: MethodChannel.Result) {
        val data = call.argument<String>("data")
        if (data == null) {
            result.error("bad_command", "Lệnh rỗng.", null)
            return
        }
        val open = socket
        if (open == null || !open.isConnected) {
            result.error("not_connected", "Chưa nối robot.", null)
            return
        }
        try {
            // A command is two bytes, so writing it inline is cheap enough.
            open.outputStream.write(data.toByteArray(Charsets.UTF_8))
            open.outputStream.flush()
            result.success(null)
        } catch (error: IOException) {
            result.error("write_failed", error.message, null)
            emitLinkLost("Mất kết nối Bluetooth.")
        }
    }

    // ---- Bluetooth plumbing -----------------------------------------------

    private fun startReader(id: Int, stream: InputStream) {
        val thread = HandlerThread("bluetooth-reader").apply { start() }
        readerThread = thread
        // Looper.handler is not public API, so build the Handler explicitly.
        // looper is non-null here because start() above created it.
        Handler(checkNotNull(thread.looper)).post {
            val buffer = ByteArray(READ_BUFFER_BYTES)
            try {
                while (id == session.get() && !thread.isInterrupted) {
                    val count = stream.read(buffer)
                    if (count < 0) {
                        break
                    }
                    if (count > 0) {
                        val text = String(buffer, 0, count)
                        mainHandler.post { emit(mapOf("type" to "message", "text" to text)) }
                    }
                }
                if (id == session.get()) {
                    mainHandler.post { emitLinkLost("Robot đã ngắt kết nối.") }
                }
            } catch (error: IOException) {
                if (id == session.get()) {
                    mainHandler.post { emitLinkLost("Mất kết nối Bluetooth.") }
                }
            }
        }
    }

    /**
     * Drops the current socket and stops its reader.
     *
     * Bumping [session] first means a reader blocked in `read()` wakes up, sees
     * a stale id, and stays quiet instead of reporting a link loss the user
     * asked for.
     */
    private fun closeSession() {
        session.incrementAndGet()
        readerThread?.quitSafely()
        readerThread = null
        runCatching { socket?.close() }
        socket = null
    }

    private fun emitLinkLost(reason: String) {
        closeSession()
        emit(mapOf("type" to "linkLost", "reason" to reason))
    }

    private fun emit(event: Map<String, String>) {
        events?.success(event)
    }

    // ---- Lifecycle --------------------------------------------------------

    override fun onDestroy() {
        pendingPermission = null
        closeSession()
        super.onDestroy()
    }
}
