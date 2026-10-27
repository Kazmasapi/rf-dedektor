package com.example.wifiscanner

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

class MainActivity : AppCompatActivity() {

    private lateinit var wifiManager: WifiManager
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bleScanner: BluetoothLeScanner? = null

    private lateinit var sharedPreferences: SharedPreferences
    private val PREFS_NAME = "RFScannerPrefs"
    private val KEY_WHITELIST = "saved_whitelist_macs"

    private val whitelist = mutableSetOf<String>()
    private val detectedDevices = LinkedHashMap<String, String>()
    private lateinit var adapter: ArrayAdapter<String>
    private val displayList = mutableListOf<String>()
    private lateinit var btnManageWhitelist: Button

    private val rssiThreshold = -65 

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadWhitelistFromPrefs()

        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        bleScanner = bluetoothAdapter?.bluetoothLeScanner

        val listView = findViewById<ListView>(R.id.listViewResults)
        val btnScan = findViewById<Button>(R.id.btnScan)
        val etWhitelistMac = findViewById<EditText>(R.id.etWhitelistMac)
        val btnAddWhitelist = findViewById<Button>(R.id.btnAddWhitelist)
        btnManageWhitelist = findViewById(R.id.btnManageWhitelist)

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, displayList)
        listView.adapter = adapter
        updateWhitelistButtonText()

        btnAddWhitelist.setOnClickListener {
            val mac = etWhitelistMac.text.toString().trim().uppercase()
            if (isValidMac(mac)) {
                addMacToWhitelist(mac)
                etWhitelistMac.text.clear()
                Toast.makeText(this, "$mac beyaz listeye eklendi", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Geçersiz MAC adresi formatı!", Toast.LENGTH_SHORT).show()
            }
        }

        btnManageWhitelist.setOnClickListener {
            showWhitelistManageDialog()
        }

        listView.setOnItemLongClickListener { _, _, position, _ ->
            val rawText = displayList[position]
            val mac = extractMacFromText(rawText)
            if (mac != null) {
                AlertDialog.Builder(this)
                    .setTitle("Beyaz Listeye Al")
                    .setMessage("$mac adresini izin verilenlere eklemek istiyor musunuz?")
                    .setPositiveButton("Evet") { _, _ ->
                        addMacToWhitelist(mac)
                        Toast.makeText(this, "$mac güvenli olarak işaretlendi", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("İptal", null)
                    .show()
            }
            true
        }

        btnScan.setOnClickListener {
            if (hasRequiredPermissions()) {
                startCombinedScan()
            } else {
                requestRequiredPermissions()
            }
        }

        registerReceiver(
            wifiScanReceiver,
            IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        )
    }

    private fun loadWhitelistFromPrefs() {
        val savedSet = sharedPreferences.getStringSet(KEY_WHITELIST, emptySet())
        whitelist.clear()
        if (savedSet != null) {
            whitelist.addAll(savedSet)
        }
    }

    private fun addMacToWhitelist(mac: String) {
        whitelist.add(mac)
        sharedPreferences.edit()
            .putStringSet(KEY_WHITELIST, HashSet(whitelist))
            .apply()
        updateWhitelistButtonText()
        refreshDisplay()
    }

    private fun removeMacFromWhitelist(mac: String) {
        if (whitelist.remove(mac)) {
            sharedPreferences.edit()
                .putStringSet(KEY_WHITELIST, HashSet(whitelist))
                .apply()
            updateWhitelistButtonText()
            refreshDisplay()
        }
    }

    private fun updateWhitelistButtonText() {
        btnManageWhitelist.text = "Beyaz Listeyi Yönet (${whitelist.size})"
    }

    private fun showWhitelistManageDialog() {
        if (whitelist.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Beyaz Liste Boş")
                .setMessage("Henüz izin verilenler listesine eklenmiş bir verici bulunmuyor.")
                .setPositiveButton("Tamam", null)
                .show()
            return
        }

        val macArray = whitelist.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Kayıtlı Cihazlar (${whitelist.size})")
            .setItems(macArray) { _, which ->
                val selectedMac = macArray[which]
                AlertDialog.Builder(this)
                    .setTitle("İzni Kaldır")
                    .setMessage("$selectedMac beyaz listeden silinsin mi?")
                    .setPositiveButton("Sil") { _, _ ->
                        removeMacFromWhitelist(selectedMac)
                        Toast.makeText(this, "$selectedMac kaldırıldı", Toast.LENGTH_SHORT).show()
                        showWhitelistManageDialog()
                    }
                    .setNegativeButton("İptal", null)
                    .show()
            }
            .setNegativeButton("Kapat", null)
            .setNeutralButton("Tümünü Temizle") { _, _ ->
                whitelist.clear()
                sharedPreferences.edit().remove(KEY_WHITELIST).apply()
                updateWhitelistButtonText()
                refreshDisplay()
                Toast.makeText(this, "Beyaz liste tamamen temizlendi", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun startCombinedScan() {
        detectedDevices.clear()
        refreshDisplay()
        wifiManager.startScan()
        startBleScan()
        Toast.makeText(this, "Tarama yapılıyor (${whitelist.size} cihaz filtrelendi)...", Toast.LENGTH_SHORT).show()
    }

    private fun startBleScan() {
        if (!hasRequiredPermissions() || bleScanner == null) return
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        bleScanner?.startScan(null, settings, bleScanCallback)
    }

    private val bleScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result ?: return
            val bssid = result.device.address.uppercase()
            val rssi = result.rssi

            if (whitelist.contains(bssid) || rssi < rssiThreshold) return

            val tagType = identifyBleDevice(result.scanRecord)
            val name = result.device.name ?: "Bilinmiyor"
            val text = "[$tagType]\nİsim: $name\nMAC: $bssid | Sinyal: $rssi dBm"

            detectedDevices[bssid] = text
            refreshDisplay()
        }
    }

    private fun identifyBleDevice(record: ScanRecord?): String {
        if (record == null) return "BLE Cihazı"
        val appleData = record.getManufacturerSpecificData(0x004C)
        if (appleData != null && appleData.isNotEmpty()) {
            if (appleData[0].toInt() == 0x12) return "DİKKAT: Apple AirTag / Find My"
            return "Apple BLE Cihazı"
        }
        val samsungData = record.getManufacturerSpecificData(0x0075)
        if (samsungData != null) return "Samsung Takip Cihazı / SmartTag"
        return "Standart BLE Verici"
    }

    private val wifiScanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) 
                != PackageManager.PERMISSION_GRANTED) return

            val results = wifiManager.scanResults
            for (res in results) {
                val bssid = res.BSSID.uppercase()
                val rssi = res.level

                if (whitelist.contains(bssid) || rssi < rssiThreshold) continue

                val ssid = if (res.SSID.isNullOrEmpty()) "[GİZLİ WI-FI AĞI]" else res.SSID
                val text = "[WI-FI AĞI]\nSSID: $ssid\nMAC: $bssid | Sinyal: $rssi dBm"

                detectedDevices[bssid] = text
            }
            refreshDisplay()
        }
    }

    private fun refreshDisplay() {
        displayList.clear()
        for ((mac, desc) in detectedDevices) {
            if (!whitelist.contains(mac)) {
                displayList.add(desc)
            }
        }
        adapter.notifyDataSetChanged()
    }

    private fun isValidMac(mac: String): Boolean {
        val macPattern = "^([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})$".toRegex()
        return mac.matches(macPattern)
    }

    private fun extractMacFromText(itemText: String): String? {
        val macPattern = "([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})".toRegex()
        return macPattern.find(itemText)?.value?.uppercase()
    }

    private fun hasRequiredPermissions(): Boolean {
        val locationOk = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            locationOk &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            locationOk
        }
    }

    private fun requestRequiredPermissions() {
        val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 1001)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(wifiScanReceiver)
        if (hasRequiredPermissions()) {
            bleScanner?.stopScan(bleScanCallback)
        }
    }
}
