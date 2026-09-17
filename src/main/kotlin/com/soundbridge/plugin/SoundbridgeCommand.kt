package com.soundbridge.plugin

enum class Modulation(val label: String, val flag: String?) {
    QPSK("QPSK", null),
    QAM16("16-QAM", "--qam16"),
    QAM64("64-QAM", "--qam64"),
    QAM256("256-QAM", "--qam256"),
    QAM1024("1024-QAM", "--qam1024"),
}

data class SendOptions(
    val deviceIndex: Int,
    val outWav: String?,
    val auto: Boolean,
    val modulation: Modulation,
    val fec: String,
    val zip: Boolean,
    val name: String,
    val profile: String,
    val copymemory: Boolean,
)

object SoundbridgeCommand {

    fun tokenize(commandBase: String): List<String> =
        commandBase.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

    fun buildCommand(settings: SoundbridgeSettings.State, opts: SendOptions, filePath: String): List<String> {
        val cmd = mutableListOf<String>()
        cmd += tokenize(settings.commandBase)
        cmd += listOf("--in", filePath)
        if (opts.outWav != null) {
            cmd += listOf("--out", opts.outWav)
        } else {
            cmd += listOf("--play", "--device", opts.deviceIndex.toString())
        }
        if (settings.stereo) cmd += "--stereo"
        cmd += listOf("--band-high", settings.bandHigh.toString())
        cmd += listOf("--resync", settings.resync)
        cmd += listOf("--parity", settings.parity)
        if (settings.peak.isNotBlank()) cmd += listOf("--peak", settings.peak)
        if (settings.guard.isNotBlank()) cmd += listOf("--guard", settings.guard)
        if (opts.auto) {
            cmd += "--auto"
        } else {
            opts.modulation.flag?.let { cmd += it }
            cmd += listOf("--fec", opts.fec)
        }
        if (opts.zip) cmd += "--zip"
        if (opts.name.isNotBlank()) cmd += listOf("--name", opts.name)
        if (opts.profile.isNotBlank()) cmd += listOf("--profile", opts.profile)
        if (opts.copymemory) cmd += "--copymemory"
        cmd += "--verbose"
        return cmd
    }
}
