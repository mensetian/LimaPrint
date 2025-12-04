package com.lima.print

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.app.NotificationCompat

/**
 * Activity transparente que recibe limaprint:// links desde el navegador.
 */
class PrintActivity : ComponentActivity() {
    private val prefsName = "lima_prefs"
    private val keyMac = "default_printer_mac"
    // Uso de camelCase para la propiedad privada
    private val notificationTitle = "LIMA"

    companion object {
        private const val TAG = "PrintActivity"
        private const val NOTIFICATION_CHANNEL_ID = "PRINT_STATUS_CHANNEL"
        // ID único ya que la función simplificada solo envía errores o advertencias
        private const val NOTIFICATION_PRINT_STATUS_ID = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No llamamos a setContentView para mantenerlo transparente

        BluetoothManager.init(applicationContext)

        CoroutineScope(Dispatchers.Main).launch {
            processIntent()
            moveTaskToBack(true)
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


            Log.d(TAG, "processIntent: Todo listo. Enviando ${bytes.size} bytes a $mac...")
            val result = withContext(Dispatchers.IO) {
                BluetoothManager.sendBytesRaw(
                    mac = mac,
                    payload = bytes,
                    keepAlive = true
                )
            }

            if (result.isSuccess) {
                showToast("Impresión enviada")
            } else {
                val error = result.exceptionOrNull()
                showToast("Error de impresión: ${error?.message ?: "Error desconocido"}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "processIntent: Error crítico inesperado.", e)
            showToast("Error crítico en PrintActivity: ${e.message}")
        }
    }

    // --- FUNCIÓN DE NOTIFICACIÓN ---
    // Función simplificada para solo recibir el mensaje, usando el título constante.
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

        // Usamos un ID fijo para que las notificaciones de estado se reemplacen.
        notificationManager.notify(NOTIFICATION_PRINT_STATUS_ID, notification)

        Log.d(TAG, "Notificación '$notificationTitle' enviada con éxito.")
    }

    // --- FUNCIONES AUXILIARES ---
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

    private fun extractBase64(rawPart: String, uri: Uri): String? {
        val base64Marker = "base64,"
        if (rawPart.startsWith(base64Marker)) {
            return rawPart.substringAfter(base64Marker)
        }

        val q = uri.getQueryParameter("data")
        if (!q.isNullOrBlank()) return q

        if (rawPart.contains(base64Marker)) {
            return rawPart.substringAfter(base64Marker)
        }
        val alt = rawPart.substringAfter("//base64,", "")
        if (alt.isNotBlank()) return alt

        return null
    }

    private fun showToast(text: String) {
        // Uso de applicationContext para evitar crashes si la Activity es destruida rápidamente
        runOnUiThread { Toast.makeText(applicationContext, text, Toast.LENGTH_LONG).show() }
    }
}