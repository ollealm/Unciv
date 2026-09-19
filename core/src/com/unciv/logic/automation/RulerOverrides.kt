package com.unciv.logic.automation

import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.ruleset.nation.Personality
import java.util.concurrent.ConcurrentHashMap

/**
 *  Strategic overrides set from outside the game by a civ's "ruler" (see desktop RulerHost).
 *  Not serialized with the save - the host persists and re-applies them.
 */
object RulerOverrides {
    /** civID -> personality replacing the nation's ruleset personality for all AI decisions */
    val personalities = ConcurrentHashMap<String, Personality>()

    /** civIDs of AI civs that have a ruler - these keep their notifications, as the advisor reports from them */
    val ruledCivs: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     *  Asked before the automation moves each unit of a ruled civ. Returning true means the ruler's own order
     *  handled this unit this turn and the automation must leave it alone.
     */
    @Volatile var unitHandler: ((MapUnit) -> Boolean)? = null
}
