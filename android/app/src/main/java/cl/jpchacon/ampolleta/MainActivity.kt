package cl.jpchacon.ampolleta

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import cl.jpchacon.ampolleta.ble.BulbManager
import cl.jpchacon.ampolleta.ui.AmpolletaUi

class AmpolletaApp : Application() {
    val manager by lazy { BulbManager(this) }
}

class MainActivity : ComponentActivity() {
    private val manager get() = (application as AmpolletaApp).manager

    private val permissions: Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasPermissions()) ensureBluetoothOn()
    }

    private val enableBluetooth = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        manager.startScan()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AmpolletaUi(manager)
        }
        if (!hasPermissions()) permissionLauncher.launch(permissions)
    }

    override fun onResume() {
        super.onResume()
        if (hasPermissions()) ensureBluetoothOn()
    }

    override fun onPause() {
        super.onPause()
        manager.stopScan()
    }

    private fun hasPermissions() = permissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun ensureBluetoothOn() {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter ?: return
        if (adapter.isEnabled) {
            manager.startScan()
        } else {
            try {
                enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            } catch (e: SecurityException) {
                manager.log("Activa el Bluetooth para buscar las ampolletas")
            }
        }
    }
}
