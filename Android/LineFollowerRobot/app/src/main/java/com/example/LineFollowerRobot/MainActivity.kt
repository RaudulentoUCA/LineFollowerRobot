package com.example.LineFollowerRobot

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

class MainActivity : AppCompatActivity() {

    private lateinit var btnConnect: Button
    private lateinit var btnManual: Button
    private lateinit var btnAutomatic: Button
    private lateinit var btnFollowRoute: Button
    private lateinit var tvMainStatus: TextView

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private val handler = Handler(Looper.getMainLooper())

    private val DEVICE_ADDRESS = "7C:4F:24:9A:0C:3F"

    private var connectStartMs: Long = 0L
    private val CONNECT_TIMEOUT_MS = 6000L

    private val statusPollRunnable = object : Runnable {
        override fun run() {
            updateUiFromBleState()

            // timeout
            if (BLEConnection.state == BLEConnection.BleState.CONNECTING) {
                val elapsed = SystemClock.elapsedRealtime() - connectStartMs
                if (elapsed > CONNECT_TIMEOUT_MS) {
                    BLEConnection.lastError = "Timeout de conexión"
                    BLEConnection.closeGatt()
                    updateUiFromBleState()
                    Toast.makeText(this@MainActivity, "No se pudo conectar (timeout)", Toast.LENGTH_SHORT).show()
                }
            }

            handler.postDelayed(this, 200L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnConnect = findViewById(R.id.btnConnect)
        btnManual = findViewById(R.id.btnManual)
        btnAutomatic = findViewById(R.id.btnAutomatic)
        btnFollowRoute = findViewById(R.id.btnFollowRoute)
        tvMainStatus = findViewById(R.id.tvMainStatus)

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        btnConnect.setOnClickListener { connectBluetooth() }

        btnManual.setOnClickListener { startActivity(Intent(this, ManualActivity::class.java)) }
        btnAutomatic.setOnClickListener { startActivity(Intent(this, AutomaticActivity::class.java)) }
        btnFollowRoute.setOnClickListener { startActivity(Intent(this, FollowRouteActivity::class.java)) }

        // refrescar estado
        handler.removeCallbacks(statusPollRunnable)
        handler.post(statusPollRunnable)
        updateUiFromBleState()
    }

    private fun updateUiFromBleState() {
        val ready = BLEConnection.isReady()

        // Botones solo si READY
        btnManual.isEnabled = ready
        btnAutomatic.isEnabled = ready
        btnFollowRoute.isEnabled = ready

        tvMainStatus.text = when (BLEConnection.state) {
            BLEConnection.BleState.DISCONNECTED -> "Estado: desconectado"
            BLEConnection.BleState.CONNECTING -> "Estado: conectando…"
            BLEConnection.BleState.CONNECTED -> "Estado: conectado (buscando servicios…)"
            BLEConnection.BleState.READY -> "Estado: conectado"
        }

        // si hay error, lo reflejamos (sin machacar el estado READY)
        if (BLEConnection.state != BLEConnection.BleState.READY && BLEConnection.lastError.isNotBlank()) {
            tvMainStatus.text = "Estado: error (${BLEConnection.lastError})"
        }
    }

    private fun connectBluetooth() {
        if (!::bluetoothAdapter.isInitialized || !bluetoothAdapter.isEnabled) {
            Toast.makeText(this, "Activa el Bluetooth", Toast.LENGTH_SHORT).show()
            BLEConnection.lastError = "Bluetooth desactivado"
            BLEConnection.state = BLEConnection.BleState.DISCONNECTED
            updateUiFromBleState()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.BLUETOOTH_CONNECT),
                    1
                )
                return
            }
        }

        // cerrar cualquier conexión anterior
        BLEConnection.closeGatt()
        BLEConnection.resetConnectionState()

        val device: BluetoothDevice = bluetoothAdapter.getRemoteDevice(DEVICE_ADDRESS)

        BLEConnection.state = BLEConnection.BleState.CONNECTING
        BLEConnection.lastError = ""
        connectStartMs = SystemClock.elapsedRealtime()
        updateUiFromBleState()

        Toast.makeText(this, "Conectando...", Toast.LENGTH_SHORT).show()

        BLEConnection.gatt = device.connectGatt(this, false, BLEConnection.gattCallback)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(statusPollRunnable)
        BLEConnection.closeGatt()
        updateUiFromBleState()
    }
}