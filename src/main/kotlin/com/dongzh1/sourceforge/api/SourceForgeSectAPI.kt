package com.dongzh1.sourceforge.api

import com.dongzh1.sourceforge.sect.JourneyPreparationResult
import com.dongzh1.sourceforge.sect.SectDefinition
import com.dongzh1.sourceforge.sect.SectService
import org.bukkit.entity.Player

/** SourceForge 教派 API，供 SourceDungeon 等出行/副本插件调用。 */
object SourceForgeSectAPI {
    @Volatile
    private var service: SectService? = null

    @JvmStatic
    fun bind(instance: SectService) { service = instance }

    @JvmStatic
    fun sects(): List<SectDefinition> = service?.sects() ?: emptyList()

    @JvmStatic
    fun selected(player: Player): SectDefinition? = service?.selected(player)

    @JvmStatic
    fun isMember(player: Player, sectId: String): Boolean = service?.isMember(player, sectId) ?: false

    @JvmStatic
    fun select(player: Player, sectId: String): Boolean = service?.select(player, sectId) != null

    @JvmStatic
    fun prepareJourney(player: Player): JourneyPreparationResult =
        service?.prepareJourney(player)
            ?: JourneyPreparationResult(com.dongzh1.sourceforge.sect.JourneyPreparationStatus.NO_SECT)

    @JvmStatic
    fun finishJourney(player: Player): Boolean = service?.finishJourney(player) ?: false

    @JvmStatic
    fun isJourneyPrepared(player: Player): Boolean = service?.isJourneyPrepared(player) ?: false

    @JvmStatic
    fun goldenWineRemainingSeconds(player: Player): Long = service?.goldenWineRemainingSeconds(player) ?: 0L
}
