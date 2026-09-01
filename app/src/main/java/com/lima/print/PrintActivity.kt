package com.lima.print

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Activity transparente que recibe limaprint:// links desde el navegador.
 */
class PrintActivity : ComponentActivity() {
    private val prefsName = "lima_prefs"
    private val keyMac = "default_printer_mac"
    private val notificationTitle = "LIMA"

    companion object {
        private const val TAG = "PrintActivity"
        private const val NOTIFICATION_CHANNEL_ID = "PRINT_STATUS_CHANNEL"
        private const val NOTIFICATION_PRINT_STATUS_ID = 100
        private const val PRINTER_WIDTH_PX = 384 // Ancho en píxeles para 58mm @ 203dpi
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No llamamos a setContentView para mantenerlo transparente

        BluetoothManager.init(applicationContext)

        CoroutineScope(Dispatchers.Main).launch {
            processIntent()
            finish() // Finalizar Activity después de procesar
        }
    }

    private suspend fun processIntent() {
        Log.d(TAG, "processIntent: Iniciando procesamiento de intent.")
        try {
            val data: Uri? = intent?.data
            if (data == null) {
                showToast("No hay datos en el intent")
                return
            }

            val raw = data.schemeSpecificPart ?: data.toString()
            val base64Payload = extractBase64(raw, data)
            if (base64Payload.isNullOrBlank()) {
                showToast("Formato de URL inválido. Se esperaba 'base64,DATA'.")
                return
            }

            val bytes = try {
                Base64.decode(base64Payload, Base64.DEFAULT)
            } catch (_: IllegalArgumentException) {
                showToast("Payload Base64 inválido")
                return
            }

            val prefs = getSharedPreferences(prefsName, MODE_PRIVATE)
            val mac = prefs.getString(keyMac, null)
            if (mac.isNullOrBlank()) {
                showToast("No hay impresora predeterminada. Abre LimaPrint y selecciona una.")
                return
            }

            if (!hasRequiredPermissions()) {
                showToast("Faltan permisos de Bluetooth. Abre LimaPrint para otorgarlos.")
                return
            }

            if (!BluetoothManager.isBluetoothAvailable() || !BluetoothManager.isEnabled()) {
                showToast("Bluetooth no disponible")
                return
            }

            // 1. INTENTO DE CONEXIÓN
            val connectResult = withContext(Dispatchers.IO) {
                BluetoothManager.establishConnection(mac)
            }

            if (connectResult.isFailure) {
                showToast("Error al conectar: ${connectResult.exceptionOrNull()?.message}")
                return
            }

            // 2. CHEQUEO DEL ESTADO DEL PAPEL
            val paperStatus = BluetoothManager.checkPaperStatus()

            when (paperStatus) {
                PrinterPaperStatus.OUT_OF_PAPER -> {
                    showNotification("🚨️ La impresora no puede imprimir. ¡Cargue un nuevo rollo ahora!")
                    return
                }
                PrinterPaperStatus.LOW_PAPER -> {
                    showNotification("⚠️ Papel casi agotado. El rollo está por terminarse. Reemplácelo pronto.")
                }
                PrinterPaperStatus.OK -> {
                    // Continuar
                }
                PrinterPaperStatus.ERROR, PrinterPaperStatus.DISCONNECTED -> {
                    showToast("Error al obtener estado de papel. Intentando imprimir...")
                }
            }

            // 3. DETECCIÓN DE TIPO Y DELEGACIÓN DE IMPRESIÓN (NUEVA LÓGICA)
            val printResult = if (isPngImage(bytes)) {
                Log.d(TAG, "processIntent: Detectado tipo IMAGEN PNG. Procesando...")
                printImageData(mac, bytes)
            } else {
                Log.d(TAG, "processIntent: Detectado tipo ESC/POS. Enviando datos crudos...")
                printEscPosData(mac, bytes)
            }

            if (printResult.isSuccess) {
                showToast("Impresión enviada")
            } else {
                val error = printResult.exceptionOrNull()
                showToast("Error de impresión: ${error?.message ?: "Error desconocido"}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "processIntent: Error crítico inesperado.", e)
            showToast("Error crítico en PrintActivity: ${e.message}")
        }
    }

    // =========================================================================
    // FUNCIONES DE IMPRESIÓN Y CONVERSIÓN
    // =========================================================================

    /**
     * Detecta si el ByteArray decodificado comienza con la firma PNG.
     */
    private fun isPngImage(data: ByteArray): Boolean {
        // PNG siempre comienza con esta firma de 8 bytes
        val pngSignature = byteArrayOf(
            0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(),
            0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte()
        )
        if (data.size < pngSignature.size) return false
        return data.sliceArray(0 until pngSignature.size).contentEquals(pngSignature)
    }

    /**
     * Procesa y envía datos crudos ESC/POS.
     */
    private suspend fun printEscPosData(mac: String, escPosData: ByteArray): Result<Unit> {
        // Log para depuración: Muestra los bytes que se van a enviar en formato hexadecimal.
        // Búscalo en el Logcat de Android Studio con el tag "PrintActivity".
        Log.d(TAG, "Datos crudos ESC/POS recibidos: ${escPosData.toHexString()}")

        return withContext(Dispatchers.IO) {
            BluetoothManager.sendBytesRaw(
                mac = mac,
                payload = escPosData,
                keepAlive = true
            )
        }
    }

    /**
     * Decodifica PNG, convierte a comandos ESC/POS Raster y envía.
     */
    private suspend fun printImageData(mac: String, pngData: ByteArray): Result<Unit> {
        return try {
            // 1. Decodificar PNG a Bitmap
            val bitmap = BitmapFactory.decodeByteArray(pngData, 0, pngData.size)
                ?: throw IOException("No se pudo decodificar la imagen PNG.")

            // 2. Convertir a comandos ESC/POS Raster
            val escPosCommands = convertBitmapToEscPosRaster(bitmap)

            // 3. Enviar
            withContext(Dispatchers.IO) {
                BluetoothManager.sendBytesRaw(
                    mac = mac,
                    payload = escPosCommands,
                    keepAlive = true
                )
            }
        } catch (e: Exception) {
            Result.failure(IOException("Error al imprimir imagen: ${e.message}", e))
        }
    }

    /**
     * Convierte un Bitmap a datos raster monocromáticos en formato ESC/POS (GS v 0).
     */
    private fun convertBitmapToEscPosRaster(bitmap: Bitmap): ByteArray {
        // Ajustar y escalar Bitmap
        val aspectRatio = bitmap.height.toFloat() / bitmap.width.toFloat()
        val targetHeight = (PRINTER_WIDTH_PX * aspectRatio).toInt()

        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, PRINTER_WIDTH_PX, targetHeight, true)

        val bwBitmap = convertToMonochrome(scaledBitmap)

        // Calcular dimensiones
        val widthBytes = (PRINTER_WIDTH_PX + 7) / 8

        // Construir comando ESC/POS
        val command = ByteArrayOutputStream()

        // Inicializar impresora (ESC @)
        command.write(0x1B) // ESC
        command.write(0x40) // @

        // Comando GS v 0 (Descarga de imagen raster)
        command.write(0x1D) // GS
        command.write(0x76) // v
        command.write(0x30) // 0
        command.write(0x00) // m (modo normal)

        // Ancho (xL, xH) - Cumple el protocolo de 2 bytes
        command.write(widthBytes and 0xFF)      // xL (low byte)
        command.write((widthBytes shr 8) and 0xFF) // xH (high byte - será 0)

        // Alto (yL, yH) - Uso directo de targetHeight
        command.write(targetHeight and 0xFF)
        command.write((targetHeight shr 8) and 0xFF)

        // Datos raster (1 bit por píxel)
        for (y in 0 until targetHeight) {
            for (x in 0 until widthBytes) {
                var byte = 0
                for (bit in 0 until 8) {
                    val px = x * 8 + bit
                    if (px < PRINTER_WIDTH_PX) {
                        val pixel = bwBitmap.getPixel(px, y)
                        // Si el píxel es negro, establecer el bit (umbral 128)
                        if (Color.red(pixel) < 128) {
                            // MSB primero (bit 7 = primer píxel del byte)
                            byte = byte or (1 shl (7 - bit))
                        }
                    }
                }
                command.write(byte)
            }
        }

        // Avanzar papel y cortar
        command.write(0x0A) // LF
        command.write(0x0A) // LF
        command.write(0x0A) // LF
        command.write(0x1D) // GS
        command.write(0x56) // V
        command.write(0x01) // 01 (Corte parcial)

        return command.toByteArray()
    }

    /**
     * Convierte un Bitmap de color a blanco y negro puro (monocromático) con un umbral de 128.
     */
    private fun convertToMonochrome(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val bwBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = bitmap.getPixel(x, y)
                // Calcular escala de grises
                val gray = (Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)) / 3
                // Umbral de 128 para convertir a blanco o negro
                val bw = if (gray > 128) Color.WHITE else Color.BLACK
                bwBitmap.setPixel(x, y, bw)
            }
        }

        return bwBitmap
    }

    // =========================================================================
    // FUNCIONES AUXILIARES
    // =========================================================================

    private fun showNotification(message: String) {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channelId = NOTIFICATION_CHANNEL_ID

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (notificationManager.getNotificationChannel(channelId) == null) {
                val name = "Estado de Impresión"
                val importance = NotificationManager.IMPORTANCE_HIGH
                val channel = NotificationChannel(channelId, name, importance)
                notificationManager.createNotificationChannel(channel)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Permiso POST_NOTIFICATIONS denegado. No se puede mostrar la notificación.")
                return
            }
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(notificationTitle)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_PRINT_STATUS_ID, notification)

        Log.d(TAG, "Notificación '$notificationTitle' enviada con éxito.")
    }

    private fun hasRequiredPermissions(): Boolean {
        val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        return hasPermission
    }


    /**
     * Extrae la carga útil Base64 de la URI, comprobando múltiples formatos.
     */
    private fun extractBase64(rawPart: String, uri: Uri): String? {
        val base64Marker = "base64,"

        // Prioridad 1: limaprint://base64,DATA...
        if (rawPart.startsWith(base64Marker)) {
            return rawPart.substringAfter(base64Marker)
        }

        // Prioridad 2: limaprint://...?data=DATA
        val queryData = uri.getQueryParameter("data")
        if (!queryData.isNullOrBlank()) {
            return queryData
        }

        // Prioridad 3 (Legacy): limaprint://.../base64,DATA
        if (rawPart.contains(base64Marker)) {
            return rawPart.substringAfter(base64Marker)
        }

        // Prioridad 4 (Legacy): limaprint:////base64,DATA
        val alt = rawPart.substringAfter("//base64,", "")
        if (alt.isNotBlank()) {
            return alt
        }

        return null
    }

    private fun showToast(text: String) {
        // Uso de applicationContext para evitar crashes si la Activity es destruida rápidamente
        runOnUiThread { Toast.makeText(applicationContext, text, Toast.LENGTH_LONG).show() }
    }
}

/**
 * Convierte un ByteArray a una cadena hexadecimal para facilitar la depuración.
 */
private fun ByteArray.toHexString() = joinToString(separator = " ") { "%02X".format(it) }
