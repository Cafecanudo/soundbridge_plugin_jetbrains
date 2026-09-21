package com.soundbridge.plugin

import com.intellij.icons.AllIcons
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.DocumentAdapter
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
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.event.DocumentEvent

sealed interface SendSource {
    data class FileOrDir(val file: VirtualFile) : SendSource
    data class TextSelection(val content: String) : SendSource
}

private fun shellDisplay(cmd: List<String>): String = cmd.joinToString(" ") { a ->
    if (a.isEmpty() || a.any { it == ' ' || it == '"' || it == '\n' || it == '\t' }) {
        "\"" + a.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
    } else {
        a
    }
}

class SendDialog(private val project: Project?, private val source: SendSource) : DialogWrapper(project) {

    private val settings =
        project?.let { SoundbridgeSettings.getInstance(it).state } ?: SoundbridgeSettings.State()
    private val vfile: VirtualFile? = (source as? SendSource.FileOrDir)?.file
    private val isText = source is SendSource.TextSelection
    private val isDir = vfile?.isDirectory == true
    private val initialText = (source as? SendSource.TextSelection)?.content ?: ""
    private val sourceLabel = when (source) {
        is SendSource.FileOrDir -> if (source.file.isDirectory) "Pasta:" else "Arquivo:"
        is SendSource.TextSelection -> "Texto:"
    }
    private val defaultName = if (source is SendSource.FileOrDir) source.file.name else ""
    private val initialPreset =
        Preset.entries.firstOrNull { it.name == settings.lastPreset } ?: Preset.AUTO
    private val autoSendOnOpen = settings.lastAutoSend

    private val textInputArea = JBTextArea(initialText, 6, 50).apply {
        lineWrap = true
        wrapStyleWord = true
    }
    private val pathField = JBTextField(if (source is SendSource.FileOrDir) source.file.path else "")

    private val deviceModel = DefaultComboBoxModel<SoundbridgeDevice>()
    private val deviceCombo = ComboBox(deviceModel).apply {
        renderer = SimpleListCellRenderer.create("Carregando devices…") { d: SoundbridgeDevice -> d.label() }
    }
    private val deviceWarning = JBLabel("").apply {
        foreground = JBColor.ORANGE
        isVisible = false
    }
    private val refreshButton = JButton(AllIcons.Actions.Refresh).apply {
        toolTipText = "Atualizar a lista de dispositivos"
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

    private val zipCheck = JBCheckBox("zip").apply {
        isSelected = if (isText) settings.lastZipText else settings.lastZip
    }
    private val nameField = JBTextField(if (isDir) "" else defaultName).apply { isEnabled = !isDir }
    private val relativePathCheck = JBCheckBox("Nome = caminho relativo").apply {
        isEnabled = !isDir && !isText
    }
    private val replForceCheck = JBCheckBox("--repl-force").apply { isSelected = settings.lastReplForce }
    private val profileField = JBTextField(settings.lastProfile)
    private val copyCheck = JBCheckBox("copymemory").apply {
        isSelected = isText
        isEnabled = isText
    }
    private val wavCheck = JBCheckBox("Gerar WAV (não toca)").apply {
        isSelected = !isDir && !isText && settings.lastGenerateWav
        isEnabled = !isDir && !isText
    }
    private val showLogCheck = JBCheckBox("Mostrar log").apply { isSelected = settings.lastShowLog }
    private val autoSendCheck = JBCheckBox("Auto-Enviar").apply { isSelected = settings.lastAutoSend }
    private val autoCloseCheck = JBCheckBox("Auto-Close").apply { isSelected = settings.lastAutoClose }

    private val processIcon = AsyncProcessIcon("soundbridge-send").apply { isVisible = false }
    private val statusLabel = JBLabel("").apply { isVisible = false }
    private val logArea = JBTextArea(8, 56).apply {
        isEditable = false
        lineWrap = true
    }
    private val logScroll = JBScrollPane(logArea).apply {
        preferredSize = Dimension(640, 180)
        isVisible = settings.lastShowLog
    }

    @Volatile
    private var process: Process? = null
    private var running = false
    private var cancelled = false
    private var savedWavPath: String? = null
    private var tempTextFile: File? = null
    private var autoSendFired = false

    private val logText = StringBuilder()
    private var screen: AnsiScreen? = null

    init {
        title = "Soundbridge TX" + (pluginVersion()?.let { " - $it" } ?: "")
        Disposer.register(disposable, processIcon)
        deviceCombo.addItemListener { e ->
            if (e.stateChange == ItemEvent.SELECTED) updateDeviceWarning()
        }
        wavCheck.addActionListener {
            updateWavMode()
            updateOkEnabled()
        }
        zipCheck.addActionListener {
            if (isText) settings.lastZipText = zipCheck.isSelected
            else settings.lastZip = zipCheck.isSelected
        }
        showLogCheck.addActionListener {
            settings.lastShowLog = showLogCheck.isSelected
            logScroll.isVisible = showLogCheck.isSelected
            window?.pack()
        }
        autoCloseCheck.addActionListener { settings.lastAutoClose = autoCloseCheck.isSelected }
        relativePathCheck.addActionListener { applyRelativePathMode() }
        replForceCheck.addActionListener { settings.lastReplForce = replForceCheck.isSelected }
        pathField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                if (relativePathCheck.isSelected) nameField.text = relativePath()
            }
        })
        refreshButton.addActionListener { refreshDevices() }
        init()
        setCancelButtonText("Fechar")
        updateWavMode()
        applyPreset(currentPreset())
        updateOkEnabled()
        loadDevicesInitial()
    }

    override fun createCenterPanel(): JComponent = panel {
        if (isText) {
            row("Texto:") {
                cell(JBScrollPane(textInputArea).apply { preferredSize = Dimension(640, 110) })
                    .align(AlignX.FILL)
            }
        } else {
            row(sourceLabel) {
                cell(pathField).align(AlignX.FILL).columns(COLUMNS_LARGE)
                contextHelp("Caminho do arquivo/pasta a transmitir (--in). Editável, caso precise ajustar.", "Caminho")
            }
        }
        row("Dispositivo:") {
            cell(deviceCombo).align(AlignX.FILL)
            cell(refreshButton)
            contextHelp(
                "Saída de áudio ligada ao cabo que vai para o PC receptor. O RX exige 48000 Hz — " +
                    "prefira um device WASAPI 48000Hz. O botão ↻ reconsulta a lista.",
                "Device",
            )
        }
        row("") { cell(deviceWarning) }
        row("") {
            cell(wavCheck)
            contextHelp(
                "Gera um arquivo .wav na pasta do arquivo (<nome>.wav) em vez de tocar ao vivo. " +
                    "O botão vira Salvar; device ignorado. Não se aplica a texto/pasta.",
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
                "Nome do arquivo salvo no receptor. No texto, se vazio o RX usa a área de transferência. " +
                    "Aceita subpasta (docs/a.txt).",
                "Name",
            )
        }
        row("") {
            cell(relativePathCheck)
            contextHelp(
                "Usa o caminho do arquivo relativo à raiz do projeto como nome (ex.: src/Main.java), " +
                    "para o RX recriar a estrutura de pastas.",
                "Caminho relativo",
            )
        }
        row("") {
            cell(replForceCheck)
            contextHelp("Adiciona a flag --repl-force à linha de comando.", "repl-force")
        }
        row("Perfil:") {
            cell(profileField).align(AlignX.FILL).columns(COLUMNS_LARGE)
            contextHelp("Perfil de recepção; o RX resolve a pasta de destino. Opcional.", "Profile")
        }
        row("") {
            cell(zipCheck)
            contextHelp("Comprime os dados antes de enviar (o RX descomprime). No texto, envia como arquivo.", "zip")
            cell(copyCheck)
            contextHelp("O receptor copia o conteúdo para a área de transferência. Só para texto selecionado.", "copymemory")
        }
        row("") {
            cell(showLogCheck)
            contextHelp("Mostra/oculta o log detalhado da transmissão.", "Mostrar log")
            cell(autoSendCheck)
            contextHelp("Quando ligado, na próxima vez a janela abre e já envia automaticamente.", "Auto-Enviar")
            cell(autoCloseCheck)
            contextHelp("Fecha a janela automaticamente após um envio bem-sucedido.", "Auto-Close")
        }
        row("") {
            cell(processIcon)
            cell(statusLabel)
        }
        row("") {
            cell(logScroll).align(Align.FILL)
        }.resizableRow()
    }

    override fun doOKAction() {
        val wav = wavCheck.isSelected
        val device = deviceCombo.selectedItem as? SoundbridgeDevice
        if (!wav && device == null) {
            appendLog("Selecione um device de saída.")
            return
        }
        val content = if (isText) textInputArea.text else ""
        if (isText && content.isBlank()) {
            appendLog("Texto vazio.")
            return
        }
        val preset = currentPreset()
        val outWav = if (wav) wavOutputPath() else null
        val name = when {
            isText -> nameField.text.trim().ifBlank { "texto.txt" }
            isDir -> ""
            else -> nameField.text.trim()
        }

        if (device != null) settings.lastDeviceIndex = device.index
        settings.lastPreset = preset.name
        if (preset.isCustom) {
            settings.lastModulation = (modCombo.selectedItem as? Modulation ?: Modulation.QAM64).name
            settings.lastFec = fecCombo.selectedItem as? String ?: "r12"
            settings.lastResync = resyncCombo.selectedItem as? String ?: "10"
            settings.lastParity = parityCombo.selectedItem as? String ?: "16"
        }
        settings.lastProfile = profileField.text.trim()
        if (!isDir && !isText) settings.lastGenerateWav = wav
        settings.lastAutoSend = autoSendCheck.isSelected

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

        val input: List<String> = when {
            isText -> {
                tempTextFile = createTempTextFile(content)
                listOf("--in", tempTextFile!!.path)
            }
            else -> listOf("--in", pathField.text.trim())
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
            name = name,
            profile = profileField.text.trim(),
            copymemory = copyCheck.isSelected,
            replForce = replForceCheck.isSelected,
        )
        val cmd = SoundbridgeCommand.buildCommand(settings, opts, input)
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
        appendLog("$ " + shellDisplay(cmd))
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
        tempTextFile?.delete()
        tempTextFile = null
        when {
            cancelled -> appendLog("⏹ Cancelado.")
            result is TransmitResult.Success && savedWavPath != null ->
                appendLog("✅ WAV salvo em: $savedWavPath — ${result.summary}")
            result is TransmitResult.Success -> appendLog("✅ Concluído — ${result.summary}")
            result is TransmitResult.Failure -> {
                appendLog("❌ Falhou:\n${result.message}")
                if (!logScroll.isVisible) {
                    showLogCheck.isSelected = true
                    logScroll.isVisible = true
                    window?.pack()
                }
            }
        }
        if (autoCloseCheck.isSelected && !cancelled && result is TransmitResult.Success) {
            close(OK_EXIT_CODE)
        }
    }

    private fun wavOutputPath(): String {
        val base = nameField.text.trim().ifBlank { defaultName }
        val dir = File(pathField.text.trim()).parent ?: return "$base.wav"
        return File(dir, "$base.wav").path
    }

    private fun applyRelativePathMode() {
        nameField.text = if (relativePathCheck.isSelected) relativePath() else defaultName
        nameField.isEnabled = !isDir
    }

    private fun relativePath(): String {
        val proj = project
        val f = vfile
        if (proj != null && f != null) {
            val root = ProjectRootManager.getInstance(proj).fileIndex.getContentRootForFile(f)
            if (root != null) VfsUtilCore.getRelativePath(f, root, '/')?.let { return it }
        }
        val path = pathField.text.trim().replace('\\', '/')
        val base = proj?.basePath?.replace('\\', '/')?.trimEnd('/')
        return if (base != null && path.startsWith("$base/", ignoreCase = true)) path.substring(base.length + 1)
        else path.substringAfterLast('/')
    }

    private fun createTempTextFile(content: String): File {
        val tmp = File.createTempFile("soundbridge-", ".txt")
        tmp.deleteOnExit()
        tmp.writeText(content, Charsets.UTF_8)
        return tmp
    }

    private fun setInputsEnabled(enabled: Boolean) {
        val custom = currentPreset().isCustom
        deviceCombo.isEnabled = enabled && !wavCheck.isSelected
        refreshButton.isEnabled = enabled
        wavCheck.isEnabled = enabled && !isDir && !isText
        modCombo.isEnabled = enabled && custom
        fecCombo.isEnabled = enabled && custom
        resyncCombo.isEnabled = enabled && custom
        parityCombo.isEnabled = enabled && custom
        zipCheck.isEnabled = enabled
        copyCheck.isEnabled = enabled && isText
        nameField.isEnabled = enabled && !isDir
        profileField.isEnabled = enabled
        pathField.isEnabled = enabled && !isText
        relativePathCheck.isEnabled = enabled && !isDir && !isText
        replForceCheck.isEnabled = enabled
        autoSendCheck.isEnabled = enabled
        autoCloseCheck.isEnabled = enabled
        textInputArea.isEnabled = enabled
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

    private fun maybeAutoSend() {
        if (autoSendOnOpen && !autoSendFired && !running && isOKActionEnabled) {
            autoSendFired = true
            doOKAction()
        }
    }

    private fun ui(block: () -> Unit) {
        ApplicationManager.getApplication().invokeLater(block, ModalityState.any())
    }

    private fun pluginVersion(): String? =
        PluginManagerCore.getPlugin(PluginId.getId("com.soundbridge.plugin"))?.version

    private fun loadDevicesInitial() {
        val cached = settings.cachedDevicesRaw
        val devices = if (cached.isBlank()) emptyList() else SoundbridgeRunner.parseDevices(cached)
        if (devices.isNotEmpty()) populateDevices(devices, fromCache = true)
        else refreshDevices()
    }

    private fun refreshDevices() {
        refreshButton.isEnabled = false
        appendLog("Listando devices…")
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = SoundbridgeRunner.listDevices(settings.commandBase)
            ui {
                refreshButton.isEnabled = true
                when (result) {
                    is DeviceListResult.Ok -> {
                        settings.cachedDevicesRaw = result.raw
                        populateDevices(result.devices)
                    }
                    is DeviceListResult.Err -> onDeviceError(result.message)
                }
            }
        }
    }

    private fun populateDevices(devices: List<SoundbridgeDevice>, fromCache: Boolean = false) {
        deviceModel.removeAllElements()
        devices.forEach { deviceModel.addElement(it) }
        val pick = devices.firstOrNull { it.index == settings.lastDeviceIndex }
            ?: devices.firstOrNull { it.sampleRate == 48000 && it.api.contains("WASAPI", ignoreCase = true) }
            ?: devices.firstOrNull { it.isDefault }
            ?: devices.firstOrNull()
        deviceCombo.selectedItem = pick
        updateDeviceWarning()
        updateOkEnabled()
        appendLog("${devices.size} device(s)" + if (fromCache) " (do cache)." else " carregado(s).")
        maybeAutoSend()
    }

    private fun onDeviceError(message: String) {
        appendLog("ERRO ao listar devices:\n$message")
        if (!logScroll.isVisible) {
            showLogCheck.isSelected = true
            logScroll.isVisible = true
            window?.pack()
        }
        updateOkEnabled()
        maybeAutoSend()
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
