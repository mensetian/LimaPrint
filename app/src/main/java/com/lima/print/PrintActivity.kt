package com.lima.print

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Activity transparente que recibe limaprint:// links desde el navegador.
 * Formato admitido (variantes comunes):
 *   limaprint:base64,SGVsbG8gV29ybGQ=
 *   limaprint://base64,SGVsbG8gV29ybGQ=
 *   limaprint://print?data=SGVsbG8=
 *
 * Decodifica y envía bytes a la impresora guardada en SharedPreferences.
 */
class PrintActivity : ComponentActivity() {
    private val prefsName = "lima_prefs"
    private val keyMac = "default_printer_mac"

    companion object {
        private const val TAG = "PrintActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate: Actividad iniciada.")
        BluetoothManager.init(applicationContext)

        lifecycleScope.launch {
            processIntent()
            Log.d(TAG, "onCreate: Procesamiento finalizado. Cerrando actividad.")
            finish()
        }
    }

    private suspend fun processIntent() {
        Log.d(TAG, "processIntent: Iniciando procesamiento de intent.")
        try {
            val data: Uri? = intent?.data
            if (data == null) {
                Log.e(TAG, "processIntent: No hay datos (Uri) en el intent.")
                showToast("No hay datos en el intent")
                return
            }
            Log.d(TAG, "processIntent: URI recibida: $data")

            val raw = data.schemeSpecificPart ?: data.toString()
            val base64Payload = extractBase64(raw, data)
            if (base64Payload.isNullOrBlank()) {
                Log.e(TAG, "processIntent: No se pudo extraer el payload Base64 de la URI: $raw")
                showToast("Formato de URL inválido. Se esperaba 'base64,DATA'.")
                return
            }
            Log.d(TAG, "processIntent: Payload Base64 extraído (primeros 50 chars): ${base64Payload.take(50)}...")

            val bytes = try {
                Base64.decode(base64Payload, Base64.DEFAULT)
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "processIntent: El payload Base64 es inválido.", e)
                showToast("Payload Base64 inválido")
                return
            }
            Log.d(TAG, "processIntent: Payload decodificado a ${bytes.size} bytes.")

            val prefs = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            val mac = prefs.getString(keyMac, null)
            if (mac.isNullOrBlank()) {
                Log.e(TAG, "processIntent: No se encontró MAC guardada en SharedPreferences.")
                showToast("No hay impresora predeterminada. Abre LimaPrint y selecciona una.")
                return
            }
            Log.d(TAG, "processIntent: MAC de impresora encontrada: $mac")

            // --- COMPROBACIÓN DE PERMISOS ---
            if (!hasRequiredPermissions()) {
                Log.e(TAG, "processIntent: Faltan permisos de Bluetooth. Abortando.")
                showToast("Faltan permisos de Bluetooth. Abre LimaPrint para otorgarlos.")
                return
            }

            if (!BluetoothManager.isBluetoothAvailable()) {
                Log.e(TAG, "processIntent: Bluetooth no está disponible en el dispositivo.")
                showToast("Bluetooth no soportado en este dispositivo.")
                return
            }
            if (!BluetoothManager.isEnabled()) {
                Log.e(TAG, "processIntent: Bluetooth está desactivado.")
                showToast("Bluetooth está desactivado. Actívalo e intenta de nuevo.")
                return
            }

            Log.d(TAG, "processIntent: Todo listo. Enviando ${bytes.size} bytes a $mac...")
            val result = BluetoothManager.sendBytesRaw(
                mac = mac,
                payload = bytes,
                keepAlive = true
            )

            if (result.isSuccess) {
                Log.i(TAG, "processIntent: Impresión enviada con éxito.")
                showToast("Impresión enviada")
            } else {
                val error = result.exceptionOrNull()
                Log.e(TAG, "processIntent: Fallo al imprimir: ${error?.message}", error)
                showToast("Error de impresión: ${error?.message ?: "Error desconocido"}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "processIntent: Error crítico inesperado.", e)
            showToast("Error crítico en PrintActivity: ${e.message}")
        }
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
        Log.d(TAG, "hasRequiredPermissions: ¿Tiene permisos? $hasPermission")
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
        runOnUiThread { Toast.makeText(this@PrintActivity, text, Toast.LENGTH_LONG).show() }
    }
}
