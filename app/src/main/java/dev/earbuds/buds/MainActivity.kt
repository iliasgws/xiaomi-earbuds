package dev.earbuds.buds

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import dev.earbuds.buds.connection.EarbudsService
import dev.earbuds.buds.ui.BudsApp

data class PairedEarbuds(val name: String, val address: String)

class MainActivity : ComponentActivity() {
    private var devices by mutableStateOf(emptyList<PairedEarbuds>())
    private var bluetoothAllowed by mutableStateOf(false)
    private var notificationsAllowed by mutableStateOf(false)
    private var promotedAllowed by mutableStateOf(false)
    private val permissions=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refreshDevices()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BudsApp(devices,bluetoothAllowed,notificationsAllowed,promotedAllowed,
                savedAddress=getPreferences(MODE_PRIVATE).getString("device",null),
                requestPermissions={
                    val required=mutableListOf<String>()
                    if (Build.VERSION.SDK_INT>=31) required+=Manifest.permission.BLUETOOTH_CONNECT
                    if (Build.VERSION.SDK_INT>=33) required+=Manifest.permission.POST_NOTIFICATIONS
                    if (required.isNotEmpty()) permissions.launch(required.toTypedArray()) else refreshDevices()
                },
                openBluetooth={startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))},
                connect={ address ->
                    getPreferences(MODE_PRIVATE).edit { putString("device",address) }
                    ContextCompat.startForegroundService(this,
                        Intent(this,EarbudsService::class.java).setAction(EarbudsService.CONNECT)
                            .putExtra(EarbudsService.ADDRESS,address))
                },
                disconnect={startService(Intent(this,EarbudsService::class.java).setAction(EarbudsService.DISCONNECT))},
                openLiveSettings={
                    val action=if (Build.VERSION.SDK_INT>=36) Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS
                        else Settings.ACTION_APP_NOTIFICATION_SETTINGS
                    startActivity(Intent(action).putExtra(Settings.EXTRA_APP_PACKAGE,packageName))
                })
        }
    }
    override fun onResume() { super.onResume(); refreshDevices() }
    @SuppressLint("MissingPermission")
    private fun refreshDevices() {
        bluetoothAllowed=Build.VERSION.SDK_INT<31 || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED
        notificationsAllowed=Build.VERSION.SDK_INT<33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED
        promotedAllowed=Build.VERSION.SDK_INT>=36 && getSystemService(NotificationManager::class.java).canPostPromotedNotifications()
        devices=if (bluetoothAllowed) runCatching {
            getSystemService(BluetoothManager::class.java).adapter?.bondedDevices.orEmpty()
                .filter { it.name?.contains("Buds 6 Pro",ignoreCase=true)==true ||
                    it.bluetoothClass?.majorDeviceClass==BluetoothClass.Device.Major.AUDIO_VIDEO }
                .map { PairedEarbuds(it.name ?: "Paired headphones",it.address) }
                .sortedBy { if (it.name.contains("Buds 6 Pro",true)) "0" else it.name }
        }.getOrDefault(emptyList()) else emptyList()
    }
}
