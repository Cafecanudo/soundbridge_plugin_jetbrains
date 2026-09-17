package com.soundbridge.plugin

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys

class SendByAudioAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible = file != null && !file.isDirectory
    }

    override fun actionPerformed(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        if (file.isDirectory) return
        SendDialog(e.project, file).show()
    }
}
