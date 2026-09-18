package com.soundbridge.plugin

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

@Service(Service.Level.APP)
@State(name = "SoundbridgeSettings", storages = [Storage("soundbridge.xml")])
class SoundbridgeSettings : PersistentStateComponent<SoundbridgeSettings.State> {

    data class State(
        var commandBase: String = "soundbridge-tx",
        var bandHigh: Int = 22000,
        var stereo: Boolean = true,
        var peak: String = "",
        var guard: String = "",
        var lastDeviceIndex: Int = -1,
        var lastPreset: String = "AUTO",
        var lastModulation: String = "QAM64",
        var lastFec: String = "r12",
        var lastResync: String = "10",
        var lastParity: String = "16",
        var lastZip: Boolean = true,
        var lastProfile: String = "",
        var lastCopymemory: Boolean = false,
        var lastGenerateWav: Boolean = false,
        var lastAutoSend: Boolean = false,
        var lastZipText: Boolean = false,
        var lastShowLog: Boolean = false,
        var lastAutoClose: Boolean = false,
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    companion object {
        fun getInstance(): SoundbridgeSettings = service()
    }
}
