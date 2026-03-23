package com.example.LineFollowerRobot

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.io.File

class FollowRouteActivity : AppCompatActivity() {

    private lateinit var spinnerRoutes: Spinner
    private lateinit var btnPower: Button
    private lateinit var btnBack: Button
    private lateinit var btnDeleteSelected: Button
    private lateinit var btnDeleteAll: Button

    private lateinit var tvRunStatus: TextView
    private lateinit var routePreview: RoutePreviewView
    private lateinit var tvPreviewStats: TextView

    private val handler = Handler(Looper.getMainLooper())

    private var powerOn = false

    private var tickMs = 15L
    private val blockSize = 20

    private var commands: List<String> = emptyList()

    // para detectar OBS nuevo
    private var lastObsSeenAtMs: Long = 0L

    // Poll para REQ
    private val reqPollRunnable = object : Runnable {
        override fun run() {
            if (!powerOn) return

            val req = BLEConnection.lastReqSeq
            if (req >= 0) {
                BLEConnection.lastReqSeq = -1L
                sendBlockAndPrefetch(req)
            }

            handler.postDelayed(this, 10L)
        }
    }

    // ✅ NUEVO: poll de obstáculo OBS,<cm>
    private val obsPollRunnable = object : Runnable {
        override fun run() {
            if (!powerOn) return

            val obsAt = BLEConnection.lastObsAtMs
            if (obsAt != 0L && obsAt != lastObsSeenAtMs) {
                lastObsSeenAtMs = obsAt
                val d = BLEConnection.lastObsDistCm

                val msg = if (d > 0) {
                    "Ruta interrumpida por obstáculo (${d} cm)"
                } else {
                    "Ruta interrumpida por obstáculo"
                }

                tvRunStatus.text = msg
                Toast.makeText(this@FollowRouteActivity, msg, Toast.LENGTH_LONG).show()

                // parar todo y mandar STOP para desbloquear Arduino
                sendCommand("STOP")
                stopRouteUi(interrupted = true)
                return
            }

            handler.postDelayed(this, 30L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_follow_route)

        spinnerRoutes = findViewById(R.id.spinnerRoutes)
        btnPower = findViewById(R.id.btnPowerFollow)
        btnBack = findViewById(R.id.btnBackFollow)
        btnDeleteSelected = findViewById(R.id.btnDeleteSelected)
        btnDeleteAll = findViewById(R.id.btnDeleteAll)

        tvRunStatus = findViewById(R.id.tvRunStatus)
        routePreview = findViewById(R.id.routePreview)
        tvPreviewStats = findViewById(R.id.tvPreviewStats)

        spinnerRoutes.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                v.parent?.requestDisallowInterceptTouchEvent(true)
            }
            false
        }

        loadRoutes()

        spinnerRoutes.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: android.view.View?, position: Int, id: Long) {
                val name = parent.getItemAtPosition(position) as? String ?: return

                if (name == "No hay rutas") {
                    routePreview.setRoute(emptyList())
                    tvPreviewStats.text = "Ticks: - | Distancia inicio → fin: -"
                    tvRunStatus.text = "Estado: listo"
                    return
                }

                val cmds = readRouteAsCommands(name)
                updatePreview(cmds)
                tvRunStatus.text = "Estado: listo"
            }

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        btnPower.setOnClickListener {
            if (!powerOn) startRoute() else stopRoute()
        }

        btnBack.setOnClickListener {
            stopRoute()
            finish()
        }

        btnDeleteSelected.setOnClickListener {
            stopRoute()
            deleteSelectedRoute()
        }

        btnDeleteAll.setOnClickListener {
            stopRoute()
            deleteAllRoutes()
        }
    }

    // ---------- ROUTES LIST ----------
    private fun listRouteFiles(): List<File> {
        return filesDir.listFiles { file ->
            file.name.startsWith("ruta_") && file.name.endsWith(".json")
        }?.toList() ?: emptyList()
    }

    private fun loadRoutes() {
        val files = listRouteFiles()

        if (files.isEmpty()) {
            spinnerRoutes.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_spinner_item,
                listOf("No hay rutas")
            ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

            routePreview.setRoute(emptyList())
            tvPreviewStats.text = "Ticks: - | Distancia inicio → fin: -"
            tvRunStatus.text = "Estado: listo"
            return
        }

        val previousSelection = spinnerRoutes.selectedItem as? String

        val names = files.map { it.name }.sorted()
        val adapter = ArrayAdapter(this, R.layout.spinner_item_white, names).apply {
            setDropDownViewResource(R.layout.spinner_item_white)
        }
        spinnerRoutes.adapter = adapter

        val index = names.indexOf(previousSelection)
        spinnerRoutes.setSelection(if (index >= 0) index else 0)

        val current = spinnerRoutes.selectedItem as? String
        if (current != null && current != "No hay rutas") {
            val cmds = readRouteAsCommands(current)
            updatePreview(cmds)
        }
        tvRunStatus.text = "Estado: listo"
    }

    private fun deleteSelectedRoute() {
        val selected = spinnerRoutes.selectedItem as? String
        if (selected == null || selected == "No hay rutas") {
            Toast.makeText(this, "No hay ruta seleccionada", Toast.LENGTH_SHORT).show()
            return
        }

        val file = File(filesDir, selected)
        val ok = file.exists() && file.delete()

        Toast.makeText(this, if (ok) "Ruta borrada: $selected" else "No se pudo borrar: $selected", Toast.LENGTH_SHORT).show()
        loadRoutes()
    }

    private fun deleteAllRoutes() {
        val files = listRouteFiles()
        if (files.isEmpty()) {
            Toast.makeText(this, "No hay rutas para borrar", Toast.LENGTH_SHORT).show()
            return
        }

        var deleted = 0
        for (f in files) if (f.delete()) deleted++

        Toast.makeText(this, "Rutas borradas: $deleted", Toast.LENGTH_SHORT).show()
        loadRoutes()
    }

    // ---------- READ ROUTE ----------
    private fun readRouteAsCommands(fileName: String): List<String> {
        val file = File(filesDir, fileName)
        if (!file.exists()) return emptyList()

        val json = JSONObject(file.readText())
        tickMs = if (json.has("tick_ms")) json.getLong("tick_ms").coerceIn(10L, 50L) else 15L

        return if (json.has("route_rle")) {
            expandRLE(json.getJSONArray("route_rle"))
        } else {
            val arr = json.optJSONArray("route") ?: return emptyList()
            List(arr.length()) { arr.getString(it).trim() }
                .filter { it in listOf("W", "A", "S", "D") }
        }
    }

    private fun expandRLE(rle: org.json.JSONArray): List<String> {
        val out = ArrayList<String>()
        for (i in 0 until rle.length()) {
            val pair = rle.getJSONArray(i)
            val cmd = pair.getString(0)
            val count = pair.getInt(1)
            if (cmd !in listOf("W", "A", "S", "D")) continue
            repeat(count) { out.add(cmd) }
        }
        return out
    }

    // ---------- PREVIEW ----------
    private fun updatePreview(cmds: List<String>) {
        routePreview.setRoute(cmds)
        val dist = routePreview.getStartEndDistance()
        tvPreviewStats.text = "Ticks: ${cmds.size} | Distancia inicio → fin: ${"%.1f".format(dist)} (px)"
    }

    // ---------- REPLAY PIPELINE ----------
    private fun startRoute() {
        val selectedFileName = spinnerRoutes.selectedItem as? String ?: return
        if (selectedFileName == "No hay rutas") return

        commands = readRouteAsCommands(selectedFileName)
        if (commands.isEmpty()) {
            Toast.makeText(this, "Ruta vacía o inválida", Toast.LENGTH_SHORT).show()
            return
        }

        BLEConnection.resetAcksAndSeq()
        lastObsSeenAtMs = 0L

        powerOn = true
        btnPower.text = "Apagar"
        tvRunStatus.text = "Estado: reproduciendo"

        // Enviar 2 bloques al inicio (prefetch)
        sendBlockIfExists(0L)
        sendBlockIfExists(blockSize.toLong())

        // Empezar a escuchar REQ y OBS
        handler.removeCallbacks(reqPollRunnable)
        handler.post(reqPollRunnable)

        handler.removeCallbacks(obsPollRunnable)
        handler.post(obsPollRunnable)
    }

    private fun sendBlockAndPrefetch(seqStart: Long) {
        if (!powerOn) return

        val sentLen = sendBlockIfExists(seqStart)
        if (sentLen <= 0) {
            sendCommand("STOP")
            stopRouteUi(interrupted = false)
            return
        }

        val nextSeq = seqStart + sentLen
        sendBlockIfExists(nextSeq)
    }

    private fun sendBlockIfExists(seqStart: Long): Int {
        val start = seqStart.toInt()
        if (start < 0 || start >= commands.size) return 0

        val end = minOf(start + blockSize, commands.size)
        val payload = buildString {
            for (i in start until end) append(commands[i])
        }
        if (payload.isEmpty()) return 0

        val cmd = "B,$tickMs,$seqStart,$payload"

        sendCommand(cmd)
        handler.postDelayed({
            if (powerOn) sendCommand(cmd)
        }, 8L)

        return payload.length
    }

    private fun stopRoute() {
        powerOn = false
        handler.removeCallbacks(reqPollRunnable)
        handler.removeCallbacks(obsPollRunnable)
        sendCommand("STOP")
        btnPower.text = "Encender"
        tvRunStatus.text = "Estado: detenido"
    }

    private fun stopRouteUi(interrupted: Boolean) {
        powerOn = false
        handler.removeCallbacks(reqPollRunnable)
        handler.removeCallbacks(obsPollRunnable)
        btnPower.text = "Encender"
        if (!interrupted) tvRunStatus.text = "Estado: detenido"
    }

    private fun sendCommand(cmd: String): Boolean {
        val gatt = BLEConnection.gatt
        val characteristic = BLEConnection.writeCharacteristic
        if (gatt == null || characteristic == null) return false

        characteristic.value = (cmd + "\n").toByteArray()
        return gatt.writeCharacteristic(characteristic)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRoute()
    }
}