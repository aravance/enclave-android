package app.enclavepower

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.IOException
import java.util.UUID

/**
 * Remote-control protocol of the Enclave CineHome Smart Center, as used by the
 * official app (com.enclaveaudio.app 1.0.33) and confirmed against firmware V152.
 *
 * Transport: Bluetooth Classic, insecure RFCOMM to the Serial Port Profile. The
 * device itself asks for no pairing (BlueZ connects unpaired), but Android's
 * stack starts Just Works pairing with a consent prompt before opening RFCOMM,
 * so on Android the device has to be bonded once up front.
 *
 * Handshake:
 *   device -> "W\r\n"
 *   host   -> "EBRC\n"
 *   device -> "V<firmware>\r\n"
 *
 * Command (4 bytes): [cmd, value + 0x80, (cmd + value + 0x80) & 0xFF, '\n']
 *   144 power (0/1)      145 mute (0/1)       146 master volume
 *   147 woofer level     148 center level     149 rear level
 *   150 rear balance     151/152 rear delay L/R
 *   153 Pro Logic mode   154 dynamic range    155 CEC mode
 *   156 input (0-2 HDMI 1-3, 3 optical, 4 aux/ARC, 5 Bluetooth)
 *   157 test noise       158 reset speakers   159 status request
 *
 * Status reply: 15 bytes + checksum (sum of the 15, & 0xFF) + "\r\n". Each byte
 * is value + 0x80, in command order starting at power; byte 0 is 0x80 when off.
 * In standby the device answers status slowly or not at all.
 */
object EnclaveClient {
    private const val TAG = "EnclaveClient"
    private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private const val CMD_POWER = 144
    private const val CMD_STATUS = 159

    private const val CONNECT_ATTEMPTS = 3
    private const val HANDSHAKE_TIMEOUT_MS = 5_000L
    private const val CONFIRM_TIMEOUT_MS = 15_000L

    sealed class Result {
        /** Status reply confirmed the system is on. */
        data class On(val firmware: String) : Result()

        /** Command was sent but no status reply confirmed it in time. */
        data class Unconfirmed(val firmware: String) : Result()
        data class Failed(val reason: String) : Result()
    }

    /** Blocking; call off the main thread. */
    @SuppressLint("MissingPermission")
    fun powerOn(context: Context, address: String): Result {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: return Result.Failed("No Bluetooth adapter")
        if (!adapter.isEnabled) return Result.Failed("Bluetooth is off")
        val device = try {
            adapter.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            return Result.Failed("Bad address $address")
        }
        try {
            // Connecting unbonded would raise a pairing prompt on screen at every trigger.
            if (device.bondState != BluetoothDevice.BOND_BONDED) return Result.Failed("Not paired")
        } catch (e: SecurityException) {
            return Result.Failed("Bluetooth permission not granted")
        }

        var lastError = "unknown"
        repeat(CONNECT_ATTEMPTS) { attempt ->
            try {
                device.createInsecureRfcommSocketToServiceRecord(SPP).use { socket ->
                    socket.connect()
                    return powerOn(socket)
                }
            } catch (e: IOException) {
                lastError = e.message ?: e.toString()
                Log.w(TAG, "Attempt ${attempt + 1} failed: $lastError")
            } catch (e: SecurityException) {
                return Result.Failed("Bluetooth permission not granted")
            }
            // Right after wake the Bluetooth stack may not be ready yet.
            SystemClock.sleep(2_000)
        }
        return Result.Failed(lastError)
    }

    private fun powerOn(socket: BluetoothSocket): Result {
        val out = socket.outputStream
        val who = readLine(socket, HANDSHAKE_TIMEOUT_MS)
        if (who == null || who.firstOrNull() != 'W'.code.toByte()) {
            throw IOException("No greeting from device")
        }
        out.write("EBRC\n".toByteArray())
        out.flush()
        val version = readLine(socket, HANDSHAKE_TIMEOUT_MS)
        if (version == null || version.firstOrNull() != 'V'.code.toByte()) {
            throw IOException("No version from device")
        }
        val firmware = String(version, 1, version.size - 1).trim()

        out.write(command(CMD_POWER, 1))
        out.flush()

        val deadline = SystemClock.elapsedRealtime() + CONFIRM_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            out.write(command(CMD_STATUS, 0))
            out.flush()
            val status = readLine(socket, 1_500)
            if (status != null && isPoweredOn(status)) return Result.On(firmware)
        }
        return Result.Unconfirmed(firmware)
    }

    private fun command(cmd: Int, value: Int): ByteArray = byteArrayOf(cmd.toByte(), (value + 128).toByte(), (cmd + value + 128).toByte(), '\n'.code.toByte())

    private fun isPoweredOn(status: ByteArray): Boolean {
        if (status.size < 18) return false
        val sum = (0 until 15).sumOf { status[it].toInt() and 0xFF } and 0xFF
        return sum == (status[15].toInt() and 0xFF) && (status[0].toInt() and 0xFF) != 0x80
    }

    /**
     * Reads one CRLF-terminated message, or null on timeout. BluetoothSocket
     * streams have no read timeout, so this polls available() instead.
     */
    private fun readLine(socket: BluetoothSocket, timeoutMs: Long): ByteArray? {
        val input = socket.inputStream
        val buf = ByteArray(256)
        var len = 0
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val available = input.available()
            if (available <= 0) {
                SystemClock.sleep(50)
                continue
            }
            val n = input.read(buf, len, minOf(available, buf.size - len))
            if (n < 0) throw IOException("Connection closed")
            len += n
            if (len > 2 && buf[len - 2] == '\r'.code.toByte() && buf[len - 1] == '\n'.code.toByte()) {
                return buf.copyOf(len)
            }
            if (len == buf.size) throw IOException("Unexpected data from device")
        }
        return null
    }
}
