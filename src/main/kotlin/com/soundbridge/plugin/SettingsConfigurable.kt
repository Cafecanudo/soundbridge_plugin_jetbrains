package com.soundbridge.plugin

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.*
import javax.swing.JComponent

class SettingsConfigurable(project: Project) : Configurable {

    private val state = SoundbridgeSettings.getInstance(project).state
    private var panel: DialogPanel? = null

    override fun getDisplayName(): String = "SoundBridge"

    override fun createComponent(): JComponent {
        val built = panel {
            group("soundbridge-tx") {
                row("Comando base:") {
                    textField()
                        .bindText(state::commandBase)
                        .columns(COLUMNS_LARGE)
                        .comment("Ex.: soundbridge-tx ou python -m soundbridge_tx.cli")
                    contextHelp(
                        "Como o plugin invoca o transmissor. Pode ser só o comando (resolve no PATH) " +
                            "ou o caminho completo do Python com o módulo, para ambientes venv.",
                        "Comando base",
                    )
                }
            }
            group("Canal e banda") {
                row("Band-high (Hz):") {
                    intTextField(0..48000).bindIntText(state::bandHigh)
                    contextHelp(
                        "Frequência máxima da banda OFDM, em Hz. Mais alto = mais largura = mais " +
                            "velocidade. Recomendado 22000 para o cabo.",
                        "Band-high",
                    )
                }
                row {
                    checkBox("Estéreo (2 canais)").bindSelected(state::stereo)
                    contextHelp(
                        "Usa os dois canais do cabo, dobrando a velocidade. Desligue só se o canal " +
                            "direito não estiver disponível.",
                        "Estéreo",
                    )
                }
            }
            group("Avançado") {
                row("Peak (0–1):") {
                    textField().bindText(state::peak).comment("Vazio = usa o default do tx")
                    contextHelp(
                        "Pico de amplitude do sinal (0 a 1). Reduza se a entrada do receptor estiver " +
                            "saturando/clipando.",
                        "Peak",
                    )
                }
                row("Guard (s):") {
                    textField().bindText(state::guard).comment("Vazio = usa o default do tx")
                    contextHelp(
                        "Silêncio de guarda, em segundos, no início e fim do sinal. Ajuda o receptor " +
                            "a detectar começo e fim da transmissão.",
                        "Guard",
                    )
                }
            }
        }
        panel = built
        return built
    }

    override fun isModified(): Boolean = panel?.isModified() ?: false

    override fun apply() {
        panel?.apply()
    }

    override fun reset() {
        panel?.reset()
    }

    override fun disposeUIResources() {
        panel = null
    }
}
