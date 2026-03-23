package com.example.LineFollowerRobot

import android.bluetooth.*
import android.os.SystemClock
import android.util.Log
import java.util.UUID
import java.util.ArrayDeque

object BLEConnection {

    var gatt: BluetoothGatt? = null
    var writeCharacteristic: BluetoothGattCharacteristic? = null
    var notifyCharacteristic: BluetoothGattCharacteristic? = null

    // ===== ESTADO REAL =====
    enum class BleState { DISCONNECTED, CONNECTING, CONNECTED, READY }

    @Volatile var state: BleState = BleState.DISCONNECTED
    @Volatile var lastError: String = ""

    fun isReady(): Boolean = (state == BleState.READY) && (gatt != null) && (writeCharacteristic != null)

    // ticks expandido (W/A/S/D por tick)
    val routeList = mutableListOf<String>()

    // ACK replay
    @Volatile var lastAckSeq: Long = -1L
    @Volatile var lastAckRaw: String = ""

    // REQ replay (Arduino pide siguiente bloque)
    @Volatile var lastReqSeq: Long = -1L
    @Volatile var lastReqRaw: String = ""

    // obstáculo (Arduino emite OBS,<cm>)
    @Volatile var lastObsDistCm: Int = -1
    @Volatile var lastObsRaw: String = ""
    @Volatile var lastObsAtMs: Long = 0L

    // estadísticas guardado
    @Volatile var lostTicks: Long = 0L
    @Volatile var expectedSeq: Long = -1L

    // dedupe (seqStart)
    private val recentBlocks = ArrayDeque<Long>()
    private val recentSet = HashSet<Long>()
    private const val RECENT_MAX = 200

    // buffer para recibir varias líneas por notificación
    private val rxBuffer = StringBuilder(512)

    private val SERVICE_UUID =
        UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
    private val CHAR_UUID =
        UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
    private val CCCD_UUID =
        UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    fun resetAcksAndSeq() {
        lastAckSeq = -1L
        lastAckRaw = ""

        lastReqSeq = -1L
        lastReqRaw = ""

        lastObsDistCm = -1
        lastObsRaw = ""
        lastObsAtMs = 0L

        lostTicks = 0L
        expectedSeq = -1L

        synchronized(routeList) { routeList.clear() }

        synchronized(recentBlocks) {
            recentBlocks.clear()
            recentSet.clear()
        }

        synchronized(rxBuffer) {
            rxBuffer.clear()
        }
    }

    fun resetConnectionState() {
        state = BleState.DISCONNECTED
        lastError = ""
        writeCharacteristic = null
        notifyCharacteristic = null
    }

    fun closeGatt() {
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
        writeCharacteristic = null
        notifyCharacteristic = null
        state = BleState.DISCONNECTED
    }

    val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {

            if (status != BluetoothGatt.GATT_SUCCESS) {
                lastError = "GATT error status=$status"
                Log.e("BLE", lastError)

                // cerrar y marcar desconectado
                try { gatt.close() } catch (_: Exception) {}
                if (this@BLEConnection.gatt == gatt) {
                    this@BLEConnection.gatt = null
                }
                writeCharacteristic = null
                notifyCharacteristic = null
                state = BleState.DISCONNECTED
                return
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d("BLE", "Conectado → discoverServices()")
                state = BleState.CONNECTED
                // OJO: READY aún no. Falta discoverServices.
                gatt.discoverServices()

            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d("BLE", "Desconectado")
                if (this@BLEConnection.gatt == gatt) {
                    this@BLEConnection.gatt = null
                }
                writeCharacteristic = null
                notifyCharacteristic = null
                state = BleState.DISCONNECTED
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                lastError = "Servicios no descubiertos (status=$status)"
                Log.e("BLE", lastError)
                state = BleState.DISCONNECTED
                return
            }

            val service = gatt.getService(SERVICE_UUID) ?: run {
                lastError = "Servicio FFE0 NO encontrado"
                Log.e("BLE", lastError)
                state = BleState.DISCONNECTED
                return
            }

            val char = service.getCharacteristic(CHAR_UUID) ?: run {
                lastError = "Característica FFE1 NO encontrada"
                Log.e("BLE", lastError)
                state = BleState.DISCONNECTED
                return
            }

            writeCharacteristic = char
            notifyCharacteristic = char
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

            enableNotifications(gatt, char)
            Log.d("BLE", "FFE1 lista (write+notify)")

            // ✅ AHORA sí: listo para usar
            state = BleState.READY
            lastError = ""
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid != CHAR_UUID) return

            val chunk = characteristic.value?.toString(Charsets.UTF_8) ?: return
            if (chunk.isEmpty()) return

            val linesToProcess = ArrayList<String>()

            synchronized(rxBuffer) {
                rxBuffer.append(chunk)

                while (true) {
                    val idx = rxBuffer.indexOf("\n")
                    if (idx < 0) break
                    val line = rxBuffer.substring(0, idx).trim()
                    rxBuffer.delete(0, idx + 1)
                    if (line.isNotEmpty()) linesToProcess.add(line)
                }

                if (rxBuffer.length > 4096) {
                    rxBuffer.delete(0, rxBuffer.length - 1024)
                }
            }

            for (line in linesToProcess) {
                handleLine(line)
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid == CCCD_UUID) {
                Log.d("BLE", "CCCD status=$status")
            }
        }
    }

    private fun handleLine(value: String) {

        // obstáculo: OBS,<cm>
        if (value.startsWith("OBS,")) {
            lastObsRaw = value
            val s = value.substringAfter("OBS,").trim()
            lastObsDistCm = s.toIntOrNull() ?: -1
            lastObsAtMs = SystemClock.elapsedRealtime()
            return
        }

        // REQ replay: REQ,40
        if (value.startsWith("REQ,")) {
            lastReqRaw = value
            val s = value.substringAfter("REQ,").trim()
            lastReqSeq = s.toLongOrNull() ?: -1L
            return
        }

        // ACK replay
        if (value.startsWith("ACK,")) {
            lastAckRaw = value
            val s = value.substringAfter("ACK,").trim()
            lastAckSeq = s.toLongOrNull() ?: -1L
            return
        }

        if (value.startsWith("RCV,")) return

        // Guardado por bloques: G,seqStart,payload
        if (value.startsWith("G,")) {
            val firstComma = value.indexOf(',')
            val secondComma = value.indexOf(',', firstComma + 1)
            if (firstComma < 0 || secondComma < 0) return

            val seqStr = value.substring(firstComma + 1, secondComma).trim()
            val payload = value.substring(secondComma + 1).trim()

            val seqStart = seqStr.toLongOrNull() ?: return
            if (payload.isEmpty()) return

            // anti-duplicado fuerte
            if (expectedSeq != -1L && seqStart < expectedSeq) {
                return
            }

            // dedupe por ventana
            synchronized(recentBlocks) {
                if (recentSet.contains(seqStart)) return
                recentSet.add(seqStart)
                recentBlocks.addLast(seqStart)
                while (recentBlocks.size > RECENT_MAX) {
                    val old = recentBlocks.removeFirst()
                    recentSet.remove(old)
                }
            }

            // contar pérdidas
            if (expectedSeq == -1L) expectedSeq = seqStart
            if (seqStart > expectedSeq) {
                lostTicks += (seqStart - expectedSeq)
            }

            // añadir payload
            synchronized(routeList) {
                for (ch in payload) {
                    when (ch) {
                        'W','A','S','D' -> routeList.add(ch.toString())
                    }
                }
            }

            expectedSeq = seqStart + payload.length
            return
        }
    }

    private fun enableNotifications(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        gatt.setCharacteristicNotification(characteristic, true)
        val descriptor = characteristic.getDescriptor(CCCD_UUID) ?: return
        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        gatt.writeDescriptor(descriptor)
    }
}