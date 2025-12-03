package com.lima.print

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Build
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
    fun getPairedDevices(): Set<BluetoothDevice> = adapter?.bondedDevices ?: emptySet()

    fun getConnectedDeviceAddress(): String? = if (socket?.isConnected == true) connectedDevice?.address else null

    suspend fun sendBytesRaw(
        mac: String,
        payload: ByteArray,
        keepAlive: Boolean = false,
        chunkSize: Int = DEFAULT_CHUNK_SIZE,
        chunkDelayMs: Long = DEFAULT_CHUNK_DELAY_MS,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        retries: Int = DEFAULT_RETRIES
    ): Result<Unit> = withContext(Dispatchers.IO) {
        socketMutex.withLock {
            try {
                val connResult = connectInternal(mac, timeoutMs, retries)
                if (connResult.isFailure) {
                    return@withContext connResult
                }

                val s = socket ?: return@withContext Result.failure(IOException("Socket no disponible después de conectar."))
                
                try {
                    Log.d(TAG, "Escribiendo ${payload.size} bytes en el socket...")
                    val os = s.outputStream
                    var offset = 0
                    while (offset < payload.size) {
                        val len = min(chunkSize, payload.size - offset)
                        os.write(payload, offset, len)
                        os.flush()
                        offset += len
                        if (offset < payload.size && chunkDelayMs > 0) delay(chunkDelayMs)
                    }
                    Log.i(TAG, "${payload.size} bytes enviados a $mac exitosamente.")
                    return@withContext Result.success(Unit)
                } catch (e: IOException) {
                    Log.e(TAG, "Fallo en la escritura al socket, cerrando conexión.", e)
                    closeConnectionInternal()
                    return@withContext Result.failure(IOException("Fallo en la escritura: ${e.message}", e))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Excepción en sendBytesRaw, cerrando conexión.", e)
                closeConnectionInternal()
                return@withContext Result.failure(IOException("Excepción en el envío: ${e.message}", e))
            } finally {
                if (!keepAlive) {
                    Log.d(TAG, "keepAlive es false, cerrando conexión.")
                    closeConnectionInternal()
                }
            }
        }
    }
    
    suspend fun establishConnection(mac: String): Result<Unit> = withContext(Dispatchers.IO) {
        Log.d(TAG, "Estableciendo conexión persistente con $mac")
        socketMutex.withLock {
            val result = connectInternal(mac, DEFAULT_TIMEOUT_MS, DEFAULT_RETRIES)
            if (result.isSuccess) {
                Log.i(TAG, "Conexión persistente establecida con $mac.")
            } else {
                Log.w(TAG, "Fallo al establecer conexión persistente con $mac.")
            }
            return@withLock result
        }
    }

    /**
     * Envía una página de prueba a la impresora. REQUIERE una conexión activa.
     */
    suspend fun testPrint(mac: String): Result<Unit> = withContext(Dispatchers.IO) {
        socketMutex.withLock {
            if (getConnectedDeviceAddress() != mac) {
                return@withLock Result.failure(IOException("No está conectado a la impresora de prueba ($mac)."))
            }

            Log.d(TAG, "Iniciando impresión de prueba para $mac en conexión existente.")
            val text = "Hola LimaPrint!\nPrueba de impresion exitosa.\n\n\n"
            val payload: ByteArray
            try {
                val init = byteArrayOf(0x1B, 0x40) // Comando de inicialización
                val textBytes = text.toByteArray(Charset.forName("CP437"))
                payload = init + textBytes
            } catch (e: Exception) {
                return@withLock Result.failure(IOException("No se pudo codificar el texto de prueba.", e))
            }

            val s = socket ?: return@withLock Result.failure(IOException("Socket nulo a pesar de estar conectado."))

            try {
                s.outputStream.write(payload)
                s.outputStream.flush()
                Log.i(TAG, "Página de prueba enviada exitosamente.")
                return@withLock Result.success(Unit)
            } catch (e: IOException) {
                Log.e(TAG, "Fallo al enviar página de prueba. Se cierra la conexión.", e)
                closeConnectionInternal()
                return@withLock Result.failure(IOException("Error de escritura en prueba: ${e.message}", e))
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun connectInternal(mac: String, timeoutMs: Long, retries: Int): Result<Unit> {
        val currentAdapter = adapter ?: return Result.failure(IOException("Bluetooth no inicializado."))
        val device = currentAdapter.getRemoteDevice(mac) ?: return Result.failure(IOException("Dispositivo no encontrado: $mac"))

        if (socket?.isConnected == true && device.address == connectedDevice?.address) {
            Log.d(TAG, "Ya se está conectado al mismo dispositivo, reutilizando socket.")
            return Result.success(Unit)
        }
        
        closeConnectionInternal()

        var lastEx: Exception? = null
        for (attempt in 1..retries + 1) {
            try {
                Log.d(TAG, "Intento de conexión $attempt a $mac...")
                if (currentAdapter.isDiscovering) {
                    currentAdapter.cancelDiscovery()
                }

                val tmpSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                
                withTimeout(timeoutMs) {
                    tmpSocket.connect()
                }
                
                try {
                    Log.d(TAG, "Enviando comando de inicialización ESC @ a la impresora.")
                    tmpSocket.outputStream.write(byteArrayOf(0x1B, 0x40))
                    tmpSocket.outputStream.flush()
                } catch (e: IOException) {
                    Log.w(TAG, "No se pudo enviar el comando de inicialización. Se ignora el error.", e)
                }

                socket = tmpSocket
                connectedDevice = device
                Log.i(TAG, "Conectado exitosamente a ${device.name} ($mac)")
                return Result.success(Unit)

            } catch (e: Exception) {
                lastEx = e
                Log.w(TAG, "Intento de conexión $attempt fallido para $mac: ${e.message}")
                closeConnectionInternal()
                delay(200L * attempt)
            }
        }
        return Result.failure(IOException("No se pudo conectar a $mac después de ${retries + 1} intentos", lastEx))
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
                Log.d(TAG, "Socket para ${connectedDevice?.address} cerrado.")
            }
        } catch (_: IOException) {
        } finally {
            socket = null
            connectedDevice = null
        }
    }
}
