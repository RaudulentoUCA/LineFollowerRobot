package com.example.LineFollowerRobot

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

class AutomaticActivity : AppCompatActivity() {

    private lateinit var btnBack: Button
    private lateinit var btnPower: Button
    private lateinit var chkSaveRoute: CheckBox
    private lateinit var tvStatus: TextView

    private val handler = Handler(Looper.getMainLooper())

    private var powerOn = false
    private var routeSavedThisSession = false

    private var lastObsSeenAtMs: Long = 0L

    private val routeList = BLEConnection.routeList

    // vigilar obstáculo durante modo auto
    private val obsPollRunnable = object : Runnable {
        override fun run() {
            if (!powerOn) return

            val obsAt = BLEConnection.lastObsAtMs
            if (obsAt != 0L && obsAt != lastObsSeenAtMs) {
                lastObsSeenAtMs = obsAt
                val d = BLEConnection.lastObsDistCm

                // STOP para cerrar estado
                sendStop()

                powerOn = false
                btnPower.text = "Encender"

                val msg = if (d > 0) "Interrumpido por obstáculo (${d} cm). Auto detenido."
                else "Interrumpido por obstáculo. Auto detenido."

                tvStatus.text = msg
                Toast.makeText(this@AutomaticActivity, msg, Toast.LENGTH_LONG).show()

                // Si estaba grabando y aún no se guardó, guardamos lo que haya
                if (chkSaveRoute.isChecked && !routeSavedThisSession) {
                    saveRouteToJsonRLE()
                    routeSavedThisSession = true
                }
                return
            }

            handler.postDelayed(this, 50L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_automatic)

        btnBack = findViewById(R.id.btnBack)
        btnPower = findViewById(R.id.btnPower)
        chkSaveRoute = findViewById(R.id.chkSaveRoute)
        tvStatus = findViewById(R.id.tvStatus)

        val gatt = BLEConnection.gatt
        val characteristic = BLEConnection.writeCharacteristic

        if (gatt == null || characteristic == null) {
            Toast.makeText(this, "No hay conexión BLE", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        tvStatus.text = "Estado: listo"

        btnPower.setOnClickListener {
            if (!powerOn) {
                // ENCENDER
                routeSavedThisSession = false
                BLEConnection.lastObsAtMs = 0L
                lastObsSeenAtMs = 0L

                if (chkSaveRoute.isChecked) {
                    BLEConnection.resetAcksAndSeq()
                    tvStatus.text = "Grabando…\nTicks: 0 | Perdidos: 0"
                } else {
                    BLEConnection.lastAckRaw = ""
                    BLEConnection.lastAckSeq = -1
                    tvStatus.text = "Auto ON (sin guardar)"
                }

                val cmd = if (chkSaveRoute.isChecked) "2G\n" else "2\n"
                characteristic.value = cmd.toByteArray()
                val ok = gatt.writeCharacteristic(characteristic)

                if (!ok) {
                    Toast.makeText(this, "No se pudo enviar comando", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                btnPower.text = "Apagar"
                powerOn = true

                handler.removeCallbacks(obsPollRunnable)
                handler.post(obsPollRunnable)

            } else {
                // APAGAR
                handler.removeCallbacks(obsPollRunnable)
                sendStop()

                btnPower.text = "Encender"
                powerOn = false

                if (chkSaveRoute.isChecked && !routeSavedThisSession) {
                    saveRouteToJsonRLE()
                    routeSavedThisSession = true
                } else {
                    tvStatus.text = "Estado: detenido"
                }
            }
        }

        btnBack.setOnClickListener {
            handler.removeCallbacks(obsPollRunnable)
            sendStop()
            if (chkSaveRoute.isChecked && !routeSavedThisSession) {
                saveRouteToJsonRLE()
                routeSavedThisSession = true
            }
            finish()
        }
    }

    private fun sendStop() {
        val gatt = BLEConnection.gatt
        val characteristic = BLEConnection.writeCharacteristic
        if (gatt != null && characteristic != null) {
            characteristic.value = "STOP\n".toByteArray()
            gatt.writeCharacteristic(characteristic)
        }
    }

    // Guardado RLE del stream expandido (W/A/D/S por tick)
    private fun saveRouteToJsonRLE() {
        val snapshot: List<String> = synchronized(routeList) { routeList.toList() }

        if (snapshot.isEmpty()) {
            tvStatus.text = "Estado: no hay ruta para guardar"
            Toast.makeText(this, "No hay ninguna ruta guardada", Toast.LENGTH_SHORT).show()
            return
        }

        val rle = JSONArray()
        var last = snapshot[0]
        var count = 1

        fun pushRun(cmd: String, n: Int) {
            val pair = JSONArray()
            pair.put(cmd)
            pair.put(n)
            rle.put(pair)
        }

        for (i in 1 until snapshot.size) {
            val cur = snapshot[i]
            if (cur == last) count++ else {
                pushRun(last, count)
                last = cur
                count = 1
            }
        }
        pushRun(last, count)

        val jsonObject = JSONObject()
        jsonObject.put("tick_ms", 15)
        jsonObject.put("route_rle", rle)

        val timestamp = java.text.SimpleDateFormat(
            "yyyy-MM-dd_HH-mm-ss",
            Locale.getDefault()
        ).format(java.util.Date())

        val fileName = "ruta_$timestamp.json"
        File(filesDir, fileName).writeText(jsonObject.toString())

        val ticks = snapshot.size.toLong()
        val lost = BLEConnection.lostTicks
        val lossPct = if (ticks > 0) (lost.toDouble() * 100.0 / ticks.toDouble()) else 0.0

        tvStatus.text = buildString {
            append("Ruta guardada: ").append(fileName).append('\n')
            append("Ticks: ").append(ticks).append('\n')
            append("Perdidos: ").append(lost).append('\n')
            append("Pérdida: ").append(String.format(Locale.getDefault(), "%.2f", lossPct)).append("%")
        }

        Toast.makeText(this, "Ruta guardada", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(obsPollRunnable)
        sendStop()
    }
}