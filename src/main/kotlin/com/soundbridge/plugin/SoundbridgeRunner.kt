package com.soundbridge.plugin

import java.io.IOException
import java.util.concurrent.TimeUnit

data class SoundbridgeDevice(
    val index: Int,
    val name: String,
    val channels: Int,
    val sampleRate: Int,
    val api: String,
    val isDefault: Boolean,
) {
    fun label(): String {
        val def = if (isDefault) " (default)" else ""
        return "[$index] $name — ${channels}ch — ${sampleRate}Hz — $api$def"
    }
}

sealed interface DeviceListResult {
    data class Ok(val devices: List<SoundbridgeDevice>, val raw: String) : DeviceListResult
    data class Err(val message: String) : DeviceListResult
}

sealed interface TransmitResult {
    data class Success(val summary: String) : TransmitResult
    data class Failure(val message: String) : TransmitResult
}

object SoundbridgeRunner {

    private val DEVICE_LINE =
        Regex("""^\s*\[(\d+)\]\s+(.*?)\s{2,}(\d+)ch\s{2,}(\d+)Hz\s{2,}\[([^\]]+)\](\s+\(default\))?\s*$""")

    private val CRC_LINE =
        Regex("""crc32=(0x[0-9A-Fa-f]+)\s+payload=(\d+)\s+dados=(\d+)\s+band_high=(\d+)\s+resync=(\S+)\s+parity=(\S+)""")

    private val ANSI = Regex("\u001B\\[[0-9;?]*[A-Za-z]")

    private const val INSTALL_HINT = "Verifique a instalação:\npip install soundbridge-tx[live]"

    fun listDevices(commandBase: String): DeviceListResult {
        val cmd = SoundbridgeCommand.tokenize(commandBase) + "--list-devices"
        return try {
            val proc = start(cmd)
            val output = proc.inputStream.readBytes().toString(Charsets.UTF_8)
            if (!proc.waitFor(30, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                return DeviceListResult.Err("Tempo esgotado ao listar devices.")
            }
            val devices = parseDevices(output)
            when {
                devices.isNotEmpty() -> DeviceListResult.Ok(devices, output)
                proc.exitValue() != 0 ->
                    DeviceListResult.Err("O comando falhou (exit ${proc.exitValue()}):\n${output.take(600)}")
                else -> DeviceListResult.Err("Nenhum device de saída encontrado.\n${output.take(600)}")
            }
        } catch (e: IOException) {
            DeviceListResult.Err("Não consegui executar \"${cmd.joinToString(" ")}\".\n$INSTALL_HINT")
        }
    }

    fun transmit(
        command: List<String>,
        onProcess: (Process) -> Unit,
        onOutput: (String) -> Unit,
    ): TransmitResult {
        val proc = try {
            start(command)
        } catch (e: IOException) {
            return TransmitResult.Failure("Não consegui executar \"${command.joinToString(" ")}\".\n$INSTALL_HINT")
        }
        onProcess(proc)
        var summary: String? = null
        val line = StringBuilder()
        try {
            val reader = proc.inputStream.reader(Charsets.UTF_8)
            val buf = CharArray(4096)
            while (true) {
                val n = reader.read(buf)
                if (n < 0) break
                val chunk = String(buf, 0, n)
                onOutput(chunk)
                for (c in chunk) {
                    if (c == '\n' || c == '\r') {
                        CRC_LINE.find(ANSI.replace(line.toString(), ""))?.let { m ->
                            summary = "crc32=${m.groupValues[1]} · payload=${m.groupValues[2]} bytes"
                        }
                        line.setLength(0)
                    } else {
                        line.append(c)
                    }
                }
            }
        } catch (e: IOException) {
            // stream fechado (provavelmente cancelado) — segue para o waitFor
        }
        CRC_LINE.find(ANSI.replace(line.toString(), ""))?.let { m ->
            summary = "crc32=${m.groupValues[1]} · payload=${m.groupValues[2]} bytes"
        }
        val exit = proc.waitFor()
        val done = summary
        return if (exit == 0 && done != null) {
            TransmitResult.Success(done)
        } else {
            TransmitResult.Failure("Processo terminou com código $exit.")
        }
    }

    private fun start(command: List<String>): Process {
        val pb = ProcessBuilder(command).redirectErrorStream(true)
        pb.environment()["PYTHONUTF8"] = "1"
        pb.environment()["PYTHONIOENCODING"] = "utf-8"
        return pb.start()
    }

    fun parseDevices(output: String): List<SoundbridgeDevice> =
        output.lineSequence().mapNotNull { line ->
            val m = DEVICE_LINE.find(line) ?: return@mapNotNull null
            SoundbridgeDevice(
                index = m.groupValues[1].toInt(),
                name = m.groupValues[2].trim(),
                channels = m.groupValues[3].toInt(),
                sampleRate = m.groupValues[4].toInt(),
                api = m.groupValues[5].trim(),
                isDefault = m.groupValues[6].isNotBlank(),
            )
        }.toList()
}
