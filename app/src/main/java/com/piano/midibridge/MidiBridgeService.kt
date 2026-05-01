package com.piano.midibridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket

class MidiBridgeService : Service() {

    private val TAG = "MidiBridge"
    private val ACTION_USB_PERMISSION = "com.piano.midibridge.USB_PERMISSION"
    private val CH_ID = "midi_bridge"
    private val NOTIF_ID = 1

    private var wsServer: MidiWsServer? = null
    private var usbConnection: UsbDeviceConnection? = null
    private var readThread: Thread? = null
    private var httpThread: Thread? = null

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    @Suppress("DEPRECATION")
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        device?.let { connectDevice(it) }
                    } else {
                        Log.w(TAG, "USB permission denied")
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    @Suppress("DEPRECATION")
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    device?.let { tryConnectMidi(it) }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    stopReading()
                    updateNotification("피아노 연결 끊김 - 다시 꽂으세요")
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        val notif = buildNotification("시작 중...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notif)
        }

        wsServer = MidiWsServer(9999).also {
            it.isReuseAddr = true
            it.start()
        }
        Log.i(TAG, "WebSocket server started on port 9999")

        startHttpServer()
        Log.i(TAG, "HTTP server started on port 8080")

        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }

        updateNotification("Brave에서 http://localhost:8080 접속")
        scanDevices()
    }

    private fun startHttpServer() {
        val htmlFile = File(getExternalFilesDir(null), "sadari.html")
        httpThread = Thread {
            val ss = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(8080))
            }
            while (!Thread.currentThread().isInterrupted) {
                try {
                    val client = ss.accept()
                    Thread {
                        try {
                            val br = client.getInputStream().bufferedReader()
                            br.readLine() // request line (ignored)
                            while (br.readLine()?.isNotEmpty() == true) {} // drain headers
                            val out = client.getOutputStream()
                            if (htmlFile.exists()) {
                                val bytes = htmlFile.readBytes()
                                out.write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\n\r\n".toByteArray())
                                out.write(bytes)
                            } else {
                                val msg = "sadari.html not found. Push it with:\nadb push sadari.html /sdcard/Android/data/com.piano.midibridge/files/sadari.html".toByteArray()
                                out.write("HTTP/1.1 404 Not Found\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${msg.size}\r\n\r\n".toByteArray())
                                out.write(msg)
                            }
                            out.flush()
                            client.close()
                        } catch (_: Exception) {}
                    }.also { it.isDaemon = true; it.start() }
                } catch (e: Exception) {
                    if (!Thread.currentThread().isInterrupted) Log.e(TAG, "HTTP accept error", e)
                }
            }
            runCatching { ss.close() }
        }.also { it.isDaemon = true; it.start() }
    }

    private fun scanDevices() {
        val mgr = getSystemService(USB_SERVICE) as UsbManager
        mgr.deviceList.values.forEach { tryConnectMidi(it) }
    }

    private fun tryConnectMidi(device: UsbDevice) {
        if (!isMidiDevice(device)) return
        val mgr = getSystemService(USB_SERVICE) as UsbManager
        if (mgr.hasPermission(device)) {
            connectDevice(device)
        } else {
            val piFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION), piFlags)
            mgr.requestPermission(device, pi)
        }
    }

    private fun isMidiDevice(device: UsbDevice): Boolean {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == 1 && iface.interfaceSubclass == 3) return true
        }
        return false
    }

    private fun connectDevice(device: UsbDevice) {
        var midiIface: UsbInterface? = null
        var epIn: UsbEndpoint? = null

        outer@ for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == 1 && iface.interfaceSubclass == 3) {
                for (j in 0 until iface.endpointCount) {
                    val ep = iface.getEndpoint(j)
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                        ep.direction == UsbConstants.USB_DIR_IN) {
                        midiIface = iface
                        epIn = ep
                        break@outer
                    }
                }
            }
        }

        if (midiIface == null || epIn == null) {
            Log.w(TAG, "No MIDI IN endpoint found")
            return
        }

        val mgr = getSystemService(USB_SERVICE) as UsbManager
        val conn = mgr.openDevice(device) ?: run {
            Log.e(TAG, "Failed to open USB device")
            return
        }

        // force=true disconnects the kernel USB audio driver
        if (!conn.claimInterface(midiIface, true)) {
            Log.e(TAG, "claimInterface failed")
            conn.close()
            return
        }

        usbConnection = conn
        val name = device.productName ?: device.deviceName
        Log.i(TAG, "Connected: $name")
        updateNotification("피아노 연결됨: $name")
        startReading(conn, epIn)
    }

    private fun startReading(conn: UsbDeviceConnection, ep: UsbEndpoint) {
        stopReading()
        readThread = Thread {
            val buf = ByteArray(64)
            while (!Thread.currentThread().isInterrupted) {
                val len = conn.bulkTransfer(ep, buf, buf.size, 100)
                if (len > 0) {
                    Log.d(TAG, "USB read $len bytes: ${buf.take(len).map { it.toInt() and 0xFF }}")
                    var i = 0
                    while (i + 3 < len) {
                        val cin = buf[i].toInt() and 0x0F
                        if (cin >= 1) {
                            wsServer?.broadcastMidi(byteArrayOf(buf[i + 1], buf[i + 2], buf[i + 3]))
                        }
                        i += 4
                    }
                }
            }
        }.also { it.start() }
    }

    private fun stopReading() {
        readThread?.interrupt()
        readThread = null
        usbConnection?.close()
        usbConnection = null
    }

    override fun onDestroy() {
        stopReading()
        httpThread?.interrupt()
        httpThread = null
        runCatching { wsServer?.stop(1000) }
        runCatching { unregisterReceiver(usbReceiver) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CH_ID, "MIDI Bridge", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CH_ID)
            .setContentTitle("MIDI Bridge")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()

    private fun updateNotification(text: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, buildNotification(text))
    }
}

private class MidiWsServer(port: Int) : WebSocketServer(InetSocketAddress(port)) {
    private val TAG = "MidiBridge"
    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        Log.i(TAG, "WS client connected: ${conn.remoteSocketAddress}")
    }
    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
        Log.i(TAG, "WS client disconnected")
    }
    override fun onMessage(conn: WebSocket, message: String) {}
    override fun onError(conn: WebSocket?, ex: Exception) {
        Log.e(TAG, "WS error", ex)
    }
    override fun onStart() {}

    fun broadcastMidi(data: ByteArray) {
        val count = connections.size
        Log.d(TAG, "MIDI broadcast to $count clients: ${data.map { it.toInt() and 0xFF }}")
        if (count > 0) broadcast(data)
    }
}
