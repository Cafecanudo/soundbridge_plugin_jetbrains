package com.soundbridge.plugin

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.SegmentedButton
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.AsyncProcessIcon
import java.awt.Dimension
import java.awt.event.ItemEvent
import java.io.File
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent

class SendDialog(project: Project?, private val file: VirtualFile) : DialogWrapper(project) {

    private val settings = SoundbridgeSettings.getInstance().state
    private val isDir = file.isDirectory
    private val initialPreset =
        Preset.entries.firstOrNull { it.name == settings.lastPreset } ?: Preset.AUTO

    private val deviceModel = DefaultComboBoxModel<SoundbridgeDevice>()
    private val deviceCombo = ComboBox(deviceModel).apply {
        renderer = SimpleListCellRenderer.create("Carregando devices…") { d: SoundbridgeDevice -> d.label() }
    }
    private val deviceWarning = JBLabel("").apply {
        foreground = JBColor.ORANGE
        isVisible = false
    }

    private lateinit var presetBar: SegmentedButton<Preset>
    private val modCombo = ComboBox(DefaultComboBoxModel(Modulation.entries.toTypedArray())).apply {
        selectedItem = Modulation.entries.firstOrNull { it.name == settings.lastModulation } ?: Modulation.QAM64
        renderer = SimpleListCellRenderer.create("") { m: Modulation -> m.label }
        isEnabled = false
    }
    private val fecCombo = ComboBox(arrayOf("r12", "r23", "r34", "none")).apply {
        selectedItem = settings.lastFec
        isEnabled = false
    }
    private val resyncCombo = ComboBox(arrayOf("off", "10", "25", "5")).apply {
        selectedItem = settings.lastResync
        isEnabled = false
    }
    private val parityCombo = ComboBox(arrayOf("off", "8", "16", "32")).apply {
        selectedItem = settings.lastParity
        isEnabled = false
    }

    private val zipCheck = JBCheckBox("zip").apply { isSelected = settings.lastZip }
    private val nameField = JBTextField(if (isDir) "" else file.name).apply { isEnabled = !isDir }
    private val profileField = JBTextField(settings.lastProfile)
    private val copyCheck = JBCheckBox("copymemory").apply { isSelected = settings.lastCopymemory }
    private val wavCheck = JBCheckBox("Gerar WAV (não toca)").apply {
        isSelected = !isDir && settings.lastGenerateWav
        isEnabled = !isDir
    }

    private val processIcon = AsyncProcessIcon("soundbridge-send").apply { isVisible = false }
    private val statusLabel = JBLabel("").apply { isVisible = false }
    private val logArea = JBTextArea(8, 56).apply {
        isEditable = false
        lineWrap = true
    }

    @Volatile
    private var process: Process? = null
    private var running = false
    private var cancelled = false
    private var savedWavPath: String? = null

    private val logText = StringBuilder()
    private var screen: AnsiScreen? = null

    init {
        title = "Enviar por Áudio — SoundBridge"
        Disposer.register(disposable, processIcon)
        deviceCombo.addItemListener { e ->
            if (e.stateChange == ItemEvent.SELECTED) updateDeviceWarning()
        }
        wavCheck.addActionListener {
            updateWavMode()
            updateOkEnabled()
        }
        init()
        setCancelButtonText("Fechar")
        updateWavMode()
        applyPreset(currentPreset())
        updateOkEnabled()
        loadDevicesAsync()
    }

    override fun createCenterPanel(): JComponent = panel {
        row(if (isDir) "Pasta:" else "Arquivo:") { label(file.path) }
        row("Dispositivo:") {
            cell(deviceCombo).align(AlignX.FILL)
            contextHelp(
                "Saída de áudio ligada ao cabo que vai para o PC receptor. O RX exige 48000 Hz — " +
                    "prefira um device WASAPI 48000Hz.",
                "Device",
            )
        }
        row("") { cell(deviceWarning) }
        row("") {
            cell(wavCheck)
            contextHelp(
                "Gera um arquivo .wav na pasta do arquivo (<nome>.wav) em vez de tocar ao vivo. " +
                    "O botão vira Salvar e o device é ignorado.",
                "Gerar WAV",
            )
        }
        row("Qualidade:") {
            presetBar = segmentedButton(Preset.entries.toList()) { text = it.label }
            presetBar.whenItemSelected { applyPreset(it) }
            presetBar.selectedItem = initialPreset
            contextHelp(
                "Perfil de transmissão: preenche modulação, FEC, resync e paridade. " +
                    "AUTO escolhe pelo tamanho; CUSTOM deixa tudo editável.",
                "Qualidade",
            )
        }
        row("Modulação:") {
            cell(modCombo)
            contextHelp(
                "Densidade da modulação. Mais densa = mais rápida, menos robusta. " +
                    "64-QAM é o teto robusto. Editável só no CUSTOM.",
                "Modulação",
            )
            label("FEC:")
            cell(fecCombo)
            contextHelp(
                "Correção de erro. r12 robusto; r23/r34 mais leves; none sem proteção. Editável só no CUSTOM.",
                "FEC",
            )
        }
        row("Resync:") {
            cell(resyncCombo)
            contextHelp(
                "Re-sincronização contra drift de clock a cada N blocos. Menor = mais robusto. " +
                    "Editável só no CUSTOM.",
                "Resync",
            )
            label("Paridade:")
            cell(parityCombo)
            contextHelp(
                "Blocos de paridade para recuperar perdas. Maior = mais robusto. Editável só no CUSTOM.",
                "Paridade",
            )
        }
        row("Nome:") {
            cell(nameField).align(AlignX.FILL).columns(COLUMNS_LARGE)
            contextHelp(
                "Nome do arquivo salvo no receptor. Default = nome do arquivo. Aceita subpasta (docs/a.txt).",
                "Name",
            )
        }
        row("Perfil:") {
            cell(profileField).align(AlignX.FILL).columns(COLUMNS_LARGE)
            contextHelp("Perfil de recepção; o RX resolve a pasta de destino. Opcional.", "Profile")
        }
        row("") {
            cell(zipCheck)
            contextHelp("Comprime os dados antes de enviar (o RX descomprime). Grande ganho em texto/dados.", "zip")
            cell(copyCheck)
            contextHelp("O receptor copia o conteúdo recebido para a área de transferência.", "copymemory")
        }
        row("") {
            cell(processIcon)
            cell(statusLabel)
        }
        row("") {
            cell(JBScrollPane(logArea).apply { preferredSize = Dimension(1040, 260) })
                .align(Align.FILL)
        }.resizableRow()
    }

    override fun doOKAction() {
        val wav = wavCheck.isSelected
        val device = deviceCombo.selectedItem as? SoundbridgeDevice
        if (!wav && device == null) {
            appendLog("Selecione um device de saída.")
            return
        }
        val preset = currentPreset()
        val outWav = if (wav) wavOutputPath() else null
        if (device != null) settings.lastDeviceIndex = device.index
        settings.lastPreset = preset.name
        if (preset.isCustom) {
            settings.lastModulation = (modCombo.selectedItem as? Modulation ?: Modulation.QAM64).name
            settings.lastFec = fecCombo.selectedItem as? String ?: "r12"
            settings.lastResync = resyncCombo.selectedItem as? String ?: "10"
            settings.lastParity = parityCombo.selectedItem as? String ?: "16"
        }
        settings.lastZip = zipCheck.isSelected
        settings.lastProfile = profileField.text.trim()
        settings.lastCopymemory = copyCheck.isSelected
        if (!isDir) settings.lastGenerateWav = wav

        val mod: Modulation?
        val fec: String?
        val resync: String?
        val parity: String?
        when {
            preset.isAuto -> { mod = null; fec = null; resync = null; parity = null }
            preset.isCustom -> {
                mod = modCombo.selectedItem as? Modulation
                fec = fecCombo.selectedItem as? String
                resync = resyncCombo.selectedItem as? String
                parity = parityCombo.selectedItem as? String
            }
            else -> { mod = preset.modulation; fec = preset.fec; resync = preset.resync; parity = preset.parity }
        }
        val opts = SendOptions(
            deviceIndex = device?.index ?: -1,
            outWav = outWav,
            auto = preset.isAuto,
            modulation = mod,
            fec = fec,
            resync = resync,
            parity = parity,
            zip = zipCheck.isSelected,
            name = if (isDir) "" else nameField.text.trim(),
            profile = profileField.text.trim(),
            copymemory = copyCheck.isSelected,
        )
        val cmd = SoundbridgeCommand.buildCommand(settings, opts, file.path)
        startTransmit(cmd, outWav)
    }

    override fun doCancelAction() {
        if (running) {
            cancelled = true
            appendLog("Cancelando…")
            process?.destroyForcibly()
            return
        }
        super.doCancelAction()
    }

    private fun currentPreset(): Preset =
        if (::presetBar.isInitialized) presetBar.selectedItem ?: Preset.AUTO else initialPreset

    private fun applyPreset(p: Preset) {
        if (!p.isCustom && !p.isAuto) {
            p.modulation?.let { modCombo.selectedItem = it }
            p.fec?.let { fecCombo.selectedItem = it }
            p.resync?.let { resyncCombo.selectedItem = it }
            p.parity?.let { parityCombo.selectedItem = it }
        }
        val editable = p.isCustom
        modCombo.isEnabled = editable
        fecCombo.isEnabled = editable
        resyncCombo.isEnabled = editable
        parityCombo.isEnabled = editable
    }

    private fun startTransmit(cmd: List<String>, outWav: String?) {
        running = true
        cancelled = false
        savedWavPath = outWav
        setInputsEnabled(false)
        isOKActionEnabled = false
        setCancelButtonText("Cancelar")
        statusLabel.text = if (outWav != null) "Salvando…" else "Enviando…"
        statusLabel.isVisible = true
        processIcon.isVisible = true
        processIcon.resume()
        appendLog("")
        appendLog("$ " + cmd.joinToString(" "))
        screen = AnsiScreen()
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = SoundbridgeRunner.transmit(
                cmd,
                onProcess = { process = it },
                onOutput = { chunk -> ui { feedOutput(chunk) } },
            )
            ui { onTransmitDone(result) }
        }
    }

    private fun onTransmitDone(result: TransmitResult) {
        running = false
        process = null
        processIcon.suspend()
        processIcon.isVisible = false
        statusLabel.isVisible = false
        setInputsEnabled(true)
        setCancelButtonText("Fechar")
        updateOkEnabled()
        screen?.let {
            val text = it.render()
            logText.append(text)
            if (text.isNotEmpty() && !text.endsWith("\n")) logText.append('\n')
        }
        screen = null
        when {
            cancelled -> appendLog("⏹ Cancelado.")
            result is TransmitResult.Success && savedWavPath != null ->
                appendLog("✅ WAV salvo em: $savedWavPath — ${result.summary}")
            result is TransmitResult.Success -> appendLog("✅ Concluído — ${result.summary}")
            result is TransmitResult.Failure -> appendLog("❌ Falhou:\n${result.message}")
        }
    }

    private fun wavOutputPath(): String {
        val base = nameField.text.trim().ifBlank { file.name }
        val dir = file.parent?.path ?: return "$base.wav"
        return File(dir, "$base.wav").path
    }

    private fun setInputsEnabled(enabled: Boolean) {
        val custom = currentPreset().isCustom
        deviceCombo.isEnabled = enabled && !wavCheck.isSelected
        wavCheck.isEnabled = enabled && !isDir
        modCombo.isEnabled = enabled && custom
        fecCombo.isEnabled = enabled && custom
        resyncCombo.isEnabled = enabled && custom
        parityCombo.isEnabled = enabled && custom
        zipCheck.isEnabled = enabled
        nameField.isEnabled = enabled && !isDir
        profileField.isEnabled = enabled
        copyCheck.isEnabled = enabled
    }

    private fun updateWavMode() {
        val wav = wavCheck.isSelected
        deviceCombo.isEnabled = !wav
        setOKButtonText(if (wav) "Salvar" else "Enviar")
        updateDeviceWarning()
    }

    private fun updateOkEnabled() {
        isOKActionEnabled = !running && (wavCheck.isSelected || deviceModel.size > 0)
    }

    private fun ui(block: () -> Unit) {
        ApplicationManager.getApplication().invokeLater(block, ModalityState.any())
    }

    private fun loadDevicesAsync() {
        appendLog("Listando devices…")
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = SoundbridgeRunner.listDevices(settings.commandBase)
            ui {
                when (result) {
                    is DeviceListResult.Ok -> populateDevices(result.devices)
                    is DeviceListResult.Err -> onDeviceError(result.message)
                }
            }
        }
    }

    private fun populateDevices(devices: List<SoundbridgeDevice>) {
        deviceModel.removeAllElements()
        devices.forEach { deviceModel.addElement(it) }
        val pick = devices.firstOrNull { it.index == settings.lastDeviceIndex }
            ?: devices.firstOrNull { it.sampleRate == 48000 && it.api.contains("WASAPI", ignoreCase = true) }
            ?: devices.firstOrNull { it.isDefault }
            ?: devices.firstOrNull()
        deviceCombo.selectedItem = pick
        updateDeviceWarning()
        updateOkEnabled()
        appendLog("${devices.size} device(s) carregado(s).")
    }

    private fun onDeviceError(message: String) {
        appendLog("ERRO ao listar devices:\n$message")
        updateOkEnabled()
    }

    private fun updateDeviceWarning() {
        val d = deviceCombo.selectedItem as? SoundbridgeDevice
        if (!wavCheck.isSelected && d != null && d.sampleRate != 48000) {
            deviceWarning.text = "⚠ Device em ${d.sampleRate}Hz; o RX exige 48000Hz. Prefira um WASAPI 48000."
            deviceWarning.isVisible = true
        } else {
            deviceWarning.isVisible = false
        }
    }

    private fun appendLog(line: String) {
        logText.append(line).append('\n')
        renderLog()
    }

    private fun feedOutput(chunk: String) {
        (screen ?: AnsiScreen().also { screen = it }).feed(chunk)
        renderLog()
    }

    private fun renderLog() {
        logArea.text = logText.toString() + (screen?.render() ?: "")
        logArea.caretPosition = logArea.document.length
    }
}
