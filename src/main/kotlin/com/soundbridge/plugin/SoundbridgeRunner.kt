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
    data class Ok(val devices: List<SoundbridgeDevice>) : DeviceListResult
    data class Err(val message: String) : DeviceListResult
}

object SoundbridgeRunner {

    private val DEVICE_LINE =
        Regex("""^\s*\[(\d+)\]\s+(.*?)\s{2,}(\d+)ch\s{2,}(\d+)Hz\s{2,}\[([^\]]+)\](\s+\(default\))?\s*$""")

    fun listDevices(commandBase: String): DeviceListResult {
        val cmd = SoundbridgeCommand.tokenize(commandBase) + "--list-devices"
        return try {
            val pb = ProcessBuilder(cmd).redirectErrorStream(true)
            pb.environment()["PYTHONUTF8"] = "1"
            pb.environment()["PYTHONIOENCODING"] = "utf-8"
            val proc = pb.start()
            val output = proc.inputStream.readBytes().toString(Charsets.UTF_8)
            if (!proc.waitFor(30, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                return DeviceListResult.Err("Tempo esgotado ao listar devices.")
            }
            val devices = parseDevices(output)
            when {
                devices.isNotEmpty() -> DeviceListResult.Ok(devices)
                proc.exitValue() != 0 ->
                    DeviceListResult.Err("O comando falhou (exit ${proc.exitValue()}):\n${output.take(600)}")
                else -> DeviceListResult.Err("Nenhum device de saída encontrado.\n${output.take(600)}")
            }
        } catch (e: IOException) {
            DeviceListResult.Err(
                "Não consegui executar \"${cmd.joinToString(" ")}\".\n" +
                    "Verifique a instalação:\npip install soundbridge-tx[live]",
            )
        }
    }

    private fun parseDevices(output: String): List<SoundbridgeDevice> =
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
