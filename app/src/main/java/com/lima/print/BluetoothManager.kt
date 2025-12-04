package com.lima.print

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.nio.charset.Charset
import java.util.*
import kotlin.math.min

object BluetoothManager {
    private const val TAG = "BluetoothManager"
    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private var adapter: BluetoothAdapter? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val socketMutex = Mutex()
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var connectedDevice: BluetoothDevice? = null

    private const val DEFAULT_TIMEOUT_MS = 5000L
    private const val DEFAULT_CHUNK_SIZE = 512
    private const val DEFAULT_CHUNK_DELAY_MS = 40L
    private const val DEFAULT_RETRIES = 1

    fun init(context: Context) {
        if (adapter == null) {
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
            adapter = bluetoothManager?.adapter
        }
    }

    fun isBluetoothAvailable(): Boolean = adapter != null
    fun isEnabled(): Boolean = adapter?.isEnabled == true

    @SuppressLint("MissingPermission")
    fun getPairedDevices(): Set<BluetoothDevice> {
        val devices = adapter?.bondedDevices ?: return emptySet()
        return devices.filter {
            val deviceClass = it.bluetoothClass
            deviceClass != null &&
                    deviceClass.majorDeviceClass == BluetoothClass.Device.Major.IMAGING &&
                    deviceClass.deviceClass == 1664
        }.toSet()
    }

    fun getConnectedDeviceAddress(): String? = if (isSocketConnected()) connectedDevice?.address else null

    private fun isSocketConnected(): Boolean {
        val s = socket ?: return false
        if (!s.isConnected) return false

        // The socket might be "connected" from Android's perspective, but dead.
        // A common trick is to check if the inputStream is available, as it will throw
        // an exception on a dead socket. A read of 0 bytes can also work.
        return try {
            s.inputStream.available()
            true
        } catch (e: IOException) {
            Log.w(TAG, "isSocketConnected check failed, connection is likely dead.")
            false
        }
    }

    suspend fun sendBytesRaw(
        mac: String,
        payload: ByteArray,
        keepAlive: Boolean = true,
        chunkSize: Int = DEFAULT_CHUNK_SIZE,
        chunkDelayMs: Long = DEFAULT_CHUNK_DELAY_MS,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        retries: Int = DEFAULT_RETRIES
    ): Result<Unit> = withContext(Dispatchers.IO) {
        socketMutex.withLock {
            val connResult = connectInternal(mac, timeoutMs, retries)
            if (connResult.isFailure) {
                return@withLock connResult
            }

            val s = socket ?: return@withLock Result.failure(IOException("Socket not available after connection attempt."))

            try {
                Log.d(TAG, "Writing ${payload.size} bytes to socket...")
                val os = s.outputStream
                payload.inputStream().buffered(chunkSize).use {
                    val buffer = ByteArray(chunkSize)
                    var bytesRead: Int
                    while (it.read(buffer).also { bytesRead = it } != -1) {
                        os.write(buffer, 0, bytesRead)
                        os.flush()
                        if (chunkDelayMs > 0) delay(chunkDelayMs)
                    }
                }

                Log.i(TAG, "Successfully sent ${payload.size} bytes to $mac.")

                if (!keepAlive) {
                    Log.d(TAG, "keepAlive is false, closing connection after successful print.")
                    closeConnectionInternal()
                }
                return@withLock Result.success(Unit)

            } catch (e: IOException) {
                Log.e(TAG, "Failed to write to socket. Connection may be lost. Closing to force reconnect on next job.", e)
                closeConnectionInternal()
                return@withLock Result.failure(IOException("Failed to write to printer: ${e.message}", e))
            }
        }
    }

    suspend fun establishConnection(mac: String): Result<Unit> = withContext(Dispatchers.IO) {
        Log.d(TAG, "Establishing persistent connection to $mac")
        socketMutex.withLock {
            connectInternal(mac, DEFAULT_TIMEOUT_MS, DEFAULT_RETRIES)
        }
    }

    suspend fun testPrint(mac: String): Result<Unit> {
        Log.d(TAG, "Initiating test print for $mac.")
        val text = "Hola LimaPrint!\nTest print successful.\n\n\n Sebástian áéí"
        val payload: ByteArray
        try {
            val init = byteArrayOf(0x1B, 0x40) // Initialize printer
            val textBytes = text.toByteArray(Charset.forName("CP437"))
            payload = init + textBytes
        } catch (e: Exception) {
            return Result.failure(IOException("Could not encode test text.", e))
        }
        return sendBytesRaw(mac = mac, payload = payload, keepAlive = true)
    }

    @SuppressLint("MissingPermission")
    private suspend fun connectInternal(mac: String, timeoutMs: Long, retries: Int): Result<Unit> {
        val currentAdapter = adapter ?: return Result.failure(IOException("Bluetooth not initialized."))
        val device = currentAdapter.getRemoteDevice(mac) ?: return Result.failure(IOException("Device not found: $mac"))

        if (isSocketConnected() && device.address == connectedDevice?.address) {
            Log.d(TAG, "Already connected to this device, reusing socket.")
            return Result.success(Unit)
        }

        closeConnectionInternal()

        var lastEx: Exception? = null
        for (attempt in 1..retries + 1) {
            try {
                Log.d(TAG, "Connection attempt $attempt to $mac...")
                if (currentAdapter.isDiscovering) {
                    currentAdapter.cancelDiscovery()
                }

                val tmpSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)

                withTimeout(timeoutMs) {
                    tmpSocket.connect()
                }

                socket = tmpSocket
                connectedDevice = device
                Log.i(TAG, "Connected successfully to ${device.name} ($mac)")
                return Result.success(Unit)

            } catch (e: Exception) {
                lastEx = e
                Log.w(TAG, "Connection attempt $attempt failed for $mac: ${e.message}")
                closeConnectionInternal()
                if (attempt <= retries) delay(200L * attempt)
            }
        }
        return Result.failure(IOException("Could not connect to $mac after ${retries + 1} attempts", lastEx))
    }

    fun closeConnection() {
        scope.launch {
            socketMutex.withLock {
                closeConnectionInternal()
            }
        }
    }

    private fun closeConnectionInternal() {
        try {
            socket?.close()
            if (connectedDevice != null) {
                Log.d(TAG, "Socket for ${connectedDevice?.address} closed.")
            }
        } catch (_: IOException) {
            // Ignore errors on close
        } finally {
            socket = null
            connectedDevice = null
        }
    }
}
