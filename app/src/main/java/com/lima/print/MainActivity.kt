package com.lima.print

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val prefsName = "lima_prefs"
    private val keyMac = "default_printer_mac"

    private val requestEnableBluetooth =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {}

    private val refreshSignal = mutableStateOf(0)

    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions.values.all { it }) {
                refreshSignal.value++
            } else {
                Toast.makeText(
                    this,
                    "Se requieren permisos para el funcionamiento completo de la app.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    // Receiver para escuchar cambios en el estado de Bluetooth en tiempo real.
    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                refreshSignal.value++ // Forzar un refresco de la UI.
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

    override fun onStart() {
        super.onStart()
        registerReceiver(
            bluetoothStateReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        )
        refreshSignal.value++
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(bluetoothStateReceiver)
    }

    override fun onDestroy() {
        super.onDestroy()
        BluetoothManager.closeConnection()
    }

    @SuppressLint("MissingPermission")
    @Composable
    fun MainScreen(refreshSignal: Int, onRefresh: () -> Unit) {
        val context = LocalContext.current
        val coroutineScope = rememberCoroutineScope()
        var pairedDevices by remember { mutableStateOf<List<BluetoothDevice>>(emptyList()) }
        var defaultMac by remember { mutableStateOf(getSavedMac(context)) }
        val isBtEnabled by remember(refreshSignal) { mutableStateOf(BluetoothManager.isEnabled()) }
        var connectedMac by remember(refreshSignal) { mutableStateOf(BluetoothManager.getConnectedDeviceAddress()) }

        LaunchedEffect(refreshSignal) {
            if (checkAndRequestPermissions(context)) {
                if (BluetoothManager.isEnabled()) {
                    pairedDevices = BluetoothManager.getPairedDevices().toList()
                    connectedMac = BluetoothManager.getConnectedDeviceAddress()
                } else {
                    pairedDevices = emptyList()
                    connectedMac = null
                }
            }
        }

        if (!isBtEnabled) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Bluetooth está desactivado", style = MaterialTheme.typography.headlineSmall)
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = { enableBluetooth() }) {
                    Text("Activar Bluetooth")
                }
            }
            return
        }

        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Configuración de LimaPrint",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            Text("Estado:", style = MaterialTheme.typography.titleMedium)
            Text(
                text = if (connectedMac != null) "Conectado a $connectedMac" else "Desconectado",
                color = if (connectedMac != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(16.dp))

            Button(onClick = {
                val mac = getSavedMac(context)
                if (mac.isNullOrBlank()) {
                    Toast.makeText(context, "Primero selecciona una impresora.", Toast.LENGTH_SHORT)
                        .show()
                    return@Button
                }
                coroutineScope.launch {
                    if (connectedMac == null) {
                        Toast.makeText(context, "Conectando a $mac...", Toast.LENGTH_SHORT).show()
                        val result = BluetoothManager.establishConnection(mac)
                        if (result.isSuccess) Toast.makeText(
                            context,
                            "Conexión Exitosa",
                            Toast.LENGTH_SHORT
                        ).show()
                        else Toast.makeText(
                            context,
                            "Fallo de conexión: ${result.exceptionOrNull()?.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        BluetoothManager.closeConnection()
                        Toast.makeText(context, "Desconectado", Toast.LENGTH_SHORT).show()
                    }
                    onRefresh()
                }
            }, modifier = Modifier.fillMaxWidth()) {
                Text(if (connectedMac == null) "Conectar" else "Desconectar")
            }
            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = {
                    val mac = getSavedMac(context) ?: return@Button
                    coroutineScope.launch {
                        Toast.makeText(
                            context,
                            "Imprimiendo página de prueba...",
                            Toast.LENGTH_SHORT
                        ).show()
                        val result = BluetoothManager.testPrint(mac)
                        if (!result.isSuccess) {
                            Toast.makeText(
                                context,
                                "Error al imprimir: ${result.exceptionOrNull()?.message}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                },
                enabled = connectedMac != null, // Botón deshabilitado si no hay conexión
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Imprimir Página de Prueba")
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text("Impresora predeterminada:", style = MaterialTheme.typography.titleMedium)
            Text(
                defaultMac ?: "No seleccionada",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(vertical = 8.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))
            Text("Dispositivos emparejados:", style = MaterialTheme.typography.titleMedium)
            // Se usa la forma concisa del argumento modifier para evitar warnings
            LazyColumn(Modifier.weight(1f)) {
                items(pairedDevices) { device ->
                    DeviceRow(device = device, isDefault = device.address == defaultMac) {
                        saveMac(context, device.address)
                        coroutineScope.launch {
                            Toast.makeText(context, "Guardado y conectando...", Toast.LENGTH_SHORT)
                                .show()
                            val result = BluetoothManager.establishConnection(device.address)
                            if (!result.isSuccess) {
                                Toast.makeText(context, "Fallo al conectar.", Toast.LENGTH_SHORT)
                                    .show()
                            }
                            onRefresh()
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
            // Se usa el calificador y el nombre del parámetro para legibilidad, aunque pueda generar Lint warning
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            colors = CardDefaults.cardColors(containerColor = if (isDefault) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = device.name ?: "Dispositivo sin nombre",
                        style = MaterialTheme.typography.bodyLarge
                    )
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

    private fun checkAndRequestPermissions(context: Context): Boolean {
        // Permisos base de Bluetooth
        val requiredPermissions = mutableListOf<String>().apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }

            // AGREGAR: Permiso de Notificaciones (para Android 13/API 33+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
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