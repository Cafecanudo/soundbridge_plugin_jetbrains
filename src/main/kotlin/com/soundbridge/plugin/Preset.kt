package com.soundbridge.plugin

enum class Preset(
    val label: String,
    val modulation: Modulation?,
    val fec: String?,
    val resync: String?,
    val parity: String?,
) {
    CUSTOM("CUSTOM", null, null, null, null),
    AUTO("AUTO", null, null, null, null),
    RAPIDO("RÁPIDO", Modulation.QAM64, "r12", "25", "8"),
    BALANCEADO("BALANCEADO", Modulation.QAM64, "r12", "10", "16"),
    ROBUSTO("ROBUSTO", Modulation.QAM16, "r12", "5", "32"),
    EXPERIMENTAL("EXPERIMENTAL", Modulation.QAM256, "r34", "10", "16");

    val isCustom: Boolean get() = this == CUSTOM
    val isAuto: Boolean get() = this == AUTO
}
