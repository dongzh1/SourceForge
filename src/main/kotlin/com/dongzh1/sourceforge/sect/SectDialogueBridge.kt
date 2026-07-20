package com.dongzh1.sourceforge.sect

import com.dongzh1.sourceforge.SourceForge
import me.th2403y.sourcetasks.api.SourceTasksAPI
import me.th2403y.sourcetasks.model.Dialogue
import me.th2403y.sourcetasks.model.DialogueOption
import me.th2403y.sourcetasks.model.DialoguePage
import org.bukkit.Bukkit

/** 将每个教派 yml 的对话定义注册到 SourceTasks，复用其 HUD、打字机、选项和 NPC 演出。 */
class SectDialogueBridge(
    private val plugin: SourceForge,
    private val sectService: SectService
) {
    private val registeredIds = linkedSetOf<String>()

    fun registerAll() {
        if (!Bukkit.getPluginManager().isPluginEnabled("SourceTasks")) {
            plugin.logger.warning("SourceTasks 未启用，教派 NPC 对话不会注册")
            return
        }
        registeredIds.forEach { runCatching { SourceTasksAPI.unregisterDialogue(it) } }
        registeredIds.clear()
        for (sect in sectService.sects()) {
            val definition = sectService.dialogueFor(sect.id) ?: continue
            val dialogue = definition.toSourceTasksDialogue()
            val ok = runCatching { SourceTasksAPI.registerDialogue(dialogue) }.getOrDefault(false)
            if (ok) registeredIds += dialogue.id
            else plugin.logger.warning("教派 ${sect.id} 的 SourceTasks 对话注册失败")
        }
        plugin.logger.info("已向 SourceTasks 注册 ${registeredIds.size} 个教派对话")
    }

    fun unregisterAll() {
        registeredIds.forEach { runCatching { SourceTasksAPI.unregisterDialogue(it) } }
        registeredIds.clear()
    }

    private fun SectDialogueDefinition.toSourceTasksDialogue(): Dialogue = Dialogue(
        id,
        pages.map { page ->
            DialoguePage(
                page.id,
                page.speaker,
                page.text,
                page.sound,
                page.options.map { option -> DialogueOption(option.text, option.goto, option.actions, option.condition) },
                page.condition,
                page.avatar,
                page.enter
            )
        },
        end,
        avatar,
        listOf(npcId),
        condition,
        stare,
        emptyMap()
    )
}
