package com.example.LineFollowerRobot

import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class ManualActivity : AppCompatActivity() {

    private var powerOn = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_manual)

        val btnPower = findViewById<Button>(R.id.btnPowerManual)
        val btnUp = findViewById<Button>(R.id.btnUp)
        val btnDown = findViewById<Button>(R.id.btnDown)
        val btnLeft = findViewById<Button>(R.id.btnLeft)
        val btnRight = findViewById<Button>(R.id.btnRight)
        val btnBack = findViewById<Button>(R.id.btnBackManual)

        fun sendCommand(command: String) {
            val gatt = BLEConnection.gatt
            val characteristic = BLEConnection.writeCharacteristic

            if (gatt == null || characteristic == null) {
                Toast.makeText(this, "No hay conexión BLE", Toast.LENGTH_SHORT).show()
                return
            }

            characteristic.value = (command + "\n").toByteArray()
            gatt.writeCharacteristic(characteristic)
        }

        // Inicialmente deshabilitadas
        btnUp.isEnabled = false
        btnDown.isEnabled = false
        btnLeft.isEnabled = false
        btnRight.isEnabled = false

        // BOTÓN ENCENDER / APAGAR
        btnPower.setOnClickListener {
            if (!powerOn) {
                btnPower.text = "Apagar"
                btnUp.isEnabled = true
                btnDown.isEnabled = true
                btnLeft.isEnabled = true
                btnRight.isEnabled = true
            } else {
                btnPower.text = "Encender"
                btnUp.isEnabled = false
                btnDown.isEnabled = false
                btnLeft.isEnabled = false
                btnRight.isEnabled = false
                sendCommand("STOP")
            }
            powerOn = !powerOn
        }

        // CONTROLES (activados si esta encendido)
        btnUp.setOnClickListener { if (powerOn) sendCommand("W") }
        btnDown.setOnClickListener { if (powerOn) sendCommand("S") }
        btnLeft.setOnClickListener { if (powerOn) sendCommand("A") }
        btnRight.setOnClickListener { if (powerOn) sendCommand("D") }

        btnBack.setOnClickListener {
            sendCommand("STOP")
            finish()
        }

    }
}