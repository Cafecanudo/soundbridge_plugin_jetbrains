package com.soundbridge.plugin

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import java.awt.Dimension
import java.awt.event.ItemEvent
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent
import javax.swing.JProgressBar

class SendDialog(project: Project?, private val file: VirtualFile) : DialogWrapper(project) {

    private val settings = SoundbridgeSettings.getInstance().state

    private val deviceModel = DefaultComboBoxModel<SoundbridgeDevice>()
    private val deviceCombo = ComboBox(deviceModel).apply {
        renderer = SimpleListCellRenderer.create("Carregando devices…") { d: SoundbridgeDevice -> d.label() }
    }
    private val deviceWarning = JBLabel("").apply {
        foreground = JBColor.ORANGE
        isVisible = false
    }

    private lateinit var manualRadio: JBRadioButton
    private val modCombo = ComboBox(DefaultComboBoxModel(Modulation.entries.toTypedArray())).apply {
        selectedItem = Modulation.QAM64
        renderer = SimpleListCellRenderer.create("") { m: Modulation -> m.label }
        isEnabled = false
    }
    private val fecCombo = ComboBox(arrayOf("r12", "r23", "r34", "none")).apply { isEnabled = false }

    private val zipCheck = JBCheckBox("zip")
    private val nameField = JBTextField(file.name)
    private val profileField = JBTextField()
    private val copyCheck = JBCheckBox("copymemory")

    private val progressBar = JProgressBar().apply { isVisible = false }
    private val logArea = JBTextArea(8, 56).apply {
        isEditable = false
        lineWrap = true
    }

    init {
        title = "Enviar por Áudio — SoundBridge"
        deviceCombo.addItemListener { e ->
            if (e.stateChange == ItemEvent.SELECTED) updateDeviceWarning()
        }
        init()
        setOKButtonText("Enviar")
        setCancelButtonText("Fechar")
        isOKActionEnabled = false
        loadDevicesAsync()
    }

    override fun createCenterPanel(): JComponent = panel {
        row("Arquivo:") { label(file.path) }
        row("Device:") {
            cell(deviceCombo).align(AlignX.FILL)
            contextHelp(
                "Saída de áudio ligada ao cabo que vai para o PC receptor. O RX exige 48000 Hz — " +
                    "prefira um device WASAPI 48000Hz.",
                "Device",
            )
        }
        row("") { cell(deviceWarning) }
        buttonsGroup {
            row("Modo:") {
                radioButton("AUTO (recomendado)").applyToComponent {
                    isSelected = true
                    addActionListener { updateManualEnabled() }
                }
                radioButton("Manual").applyToComponent {
                    manualRadio = this
                    addActionListener { updateManualEnabled() }
                }
                contextHelp(
                    "AUTO escolhe modulação e FEC pelo tamanho do arquivo (recomendado). " +
                        "Manual permite fixar os dois.",
                    "Modo",
                )
            }
        }
        row("Modulação:") {
            cell(modCombo)
            contextHelp(
                "Densidade da modulação. Mais densa = mais rápida, menos robusta. " +
                    "64-QAM é o teto robusto.",
                "Modulação",
            )
            label("FEC:")
            cell(fecCombo)
            contextHelp(
                "Correção de erro. r12 robusto; r23/r34 mais leves e rápidos; none sem proteção.",
                "FEC",
            )
        }
        row("Name:") {
            cell(nameField).align(AlignX.FILL)
            contextHelp(
                "Nome do arquivo salvo no receptor. Default = nome do arquivo. Aceita subpasta (docs/a.txt).",
                "Name",
            )
        }
        row("Profile:") {
            cell(profileField).align(AlignX.FILL)
            contextHelp("Perfil de recepção; o RX resolve a pasta de destino. Opcional.", "Profile")
        }
        row("") {
            cell(zipCheck)
            contextHelp("Comprime os dados antes de enviar (o RX descomprime). Grande ganho em texto/dados.", "zip")
            cell(copyCheck)
            contextHelp("O receptor copia o conteúdo recebido para a área de transferência.", "copymemory")
        }
        row("") { cell(progressBar).align(AlignX.FILL) }
        row("") {
            cell(JBScrollPane(logArea).apply { preferredSize = Dimension(560, 160) })
                .align(Align.FILL)
        }.resizableRow()
    }

    override fun doOKAction() {
        val device = deviceCombo.selectedItem as? SoundbridgeDevice
        if (device == null) {
            appendLog("Selecione um device de saída.")
            return
        }
        settings.lastDeviceIndex = device.index
        val opts = SendOptions(
            deviceIndex = device.index,
            auto = !manualRadio.isSelected,
            modulation = modCombo.selectedItem as? Modulation ?: Modulation.QAM64,
            fec = fecCombo.selectedItem as? String ?: "r12",
            zip = zipCheck.isSelected,
            name = nameField.text.trim(),
            profile = profileField.text.trim(),
            copymemory = copyCheck.isSelected,
        )
        val cmd = SoundbridgeCommand.buildCommand(settings, opts, file.path)
        appendLog("Comando: " + cmd.joinToString(" "))
    }

    private fun updateManualEnabled() {
        val manual = manualRadio.isSelected
        modCombo.isEnabled = manual
        fecCombo.isEnabled = manual
    }

    private fun loadDevicesAsync() {
        appendLog("Listando devices…")
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = SoundbridgeRunner.listDevices(settings.commandBase)
            ApplicationManager.getApplication().invokeLater({
                when (result) {
                    is DeviceListResult.Ok -> populateDevices(result.devices)
                    is DeviceListResult.Err -> onDeviceError(result.message)
                }
            }, ModalityState.any())
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
        isOKActionEnabled = true
        appendLog("${devices.size} device(s) carregado(s).")
    }

    private fun onDeviceError(message: String) {
        isOKActionEnabled = false
        appendLog("ERRO ao listar devices:\n$message")
    }

    private fun updateDeviceWarning() {
        val d = deviceCombo.selectedItem as? SoundbridgeDevice
        if (d != null && d.sampleRate != 48000) {
            deviceWarning.text = "⚠ Device em ${d.sampleRate}Hz; o RX exige 48000Hz. Prefira um WASAPI 48000."
            deviceWarning.isVisible = true
        } else {
            deviceWarning.isVisible = false
        }
    }

    private fun appendLog(line: String) {
        logArea.append(line + "\n")
        logArea.caretPosition = logArea.document.length
    }
}
