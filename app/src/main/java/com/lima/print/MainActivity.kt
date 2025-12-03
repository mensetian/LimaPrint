package com.lima.print

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private val prefsName = "lima_prefs"
    private val keyMac = "default_printer_mac"

    // Lanzador para la solicitud de activación de Bluetooth
    private val requestEnableBluetooth = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // El estado se actualizará en onResume
    }

    // Estado para forzar la recomposición y actualización de la lista de dispositivos
    private val refreshSignal = mutableStateOf(0)

    // Lanzador para la solicitud de permisos
    private val requestPermissionsLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        if (permissions.values.all { it }) {
            // Si se otorgan los permisos, refrescamos la lista de dispositivos
            refreshSignal.value++
        } else {
            Toast.makeText(this, "Se requieren permisos de Bluetooth para buscar impresoras.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Inicializa el BluetoothManager con el contexto de la aplicación. Es vital.
        BluetoothManager.init(applicationContext)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(
                        refreshSignal = refreshSignal.value,
                        onRefresh = { refreshSignal.value++ }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Forzar un refresco por si el usuario activó Bluetooth desde fuera de la app
        refreshSignal.value++
    }

    @SuppressLint("MissingPermission")
    @Composable
    fun MainScreen(refreshSignal: Int, onRefresh: () -> Unit) {
        val context = LocalContext.current
        var pairedDevices by remember { mutableStateOf<List<BluetoothDevice>>(emptyList()) }
        var defaultMac by remember { mutableStateOf(getSavedMac(context)) }
        val isBtEnabled by remember(refreshSignal) { mutableStateOf(BluetoothManager.isEnabled()) }

        // Efecto que se ejecuta cuando refreshSignal cambia
        LaunchedEffect(refreshSignal) {
            if (checkAndRequestPermissions(context)) {
                if (BluetoothManager.isEnabled()) {
                    pairedDevices = BluetoothManager.getPairedDevices().toList()
                }
            }
        }

        Column(modifier = Modifier.padding(16.dp)) {
            Text("Configuración de LimaPrint", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 16.dp))

            // Sección de estado y acciones de Bluetooth
            if (isBtEnabled) {
                Button(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
                    Text("Refrescar lista de impresoras")
                }
            } else {
                Button(onClick = { enableBluetooth() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Activar Bluetooth")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Sección de impresora predeterminada
            Text("Impresora predeterminada:", style = MaterialTheme.typography.titleMedium)
            Text(defaultMac ?: "No seleccionada", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 8.dp))

            Spacer(modifier = Modifier.height(16.dp))

            // Lista de dispositivos emparejados
            Text("Dispositivos emparejados:", style = MaterialTheme.typography.titleMedium)
            if (!isBtEnabled) {
                Text("Bluetooth está desactivado.", modifier = Modifier.padding(top = 8.dp))
            } else if (pairedDevices.isEmpty()) {
                Text("No hay impresoras emparejadas. Ve a los Ajustes de Bluetooth para emparejar tu impresora.", modifier = Modifier.padding(top = 8.dp).clickable { openBluetoothSettings() })
            } else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(pairedDevices) { device ->
                        DeviceRow(device = device, isDefault = device.address == defaultMac) {
                            saveMac(context, device.address)
                            defaultMac = device.address
                            Toast.makeText(context, "Impresora guardada: ${device.name}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    @Composable
    fun DeviceRow(device: BluetoothDevice, isDefault: Boolean, onSelect: () -> Unit) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .clickable { onSelect() },
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            colors = CardDefaults.cardColors(containerColor = if (isDefault) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = device.name ?: "Dispositivo sin nombre", style = MaterialTheme.typography.bodyLarge)
                    Text(text = device.address, style = MaterialTheme.typography.bodySmall)
                }
                if (isDefault) {
                    Text("Predet.", color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }

    private fun saveMac(context: Context, mac: String) {
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        prefs.edit().putString(keyMac, mac).apply()
    }

    private fun getSavedMac(context: Context): String? {
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        return prefs.getString(keyMac, null)
    }

    private fun enableBluetooth() {
        val enableIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
        requestEnableBluetooth.launch(enableIntent)
    }

    private fun openBluetoothSettings() {
        startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
    }

    private fun checkAndRequestPermissions(context: Context): Boolean {
        val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }

        return if (missingPermissions.isEmpty()) {
            true
        } else {
            requestPermissionsLauncher.launch(missingPermissions.toTypedArray())
            false
        }
    }
}
