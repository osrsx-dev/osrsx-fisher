package io.osrsx.plugins.skilling

import io.osrsx.api.ItemRef
import io.osrsx.api.RestockSpec
import io.osrsx.api.Skill
import io.osrsx.api.loadout
import io.osrsx.config.PluginConfig
import io.osrsx.config.isFalse
import io.osrsx.config.isTrue
import io.osrsx.plugin.HasOverlay
import io.osrsx.plugin.ScriptGui
import io.osrsx.script.Script
import io.osrsx.script.ScriptDslPlugin

/**
 * Fishing plugin, authored with the **Script DSL** ([ScriptDslPlugin]) over the shared [skillGatherScript].
 * Fishing spots are NPCs (not objects), so the resource lambda queries [npcs] and the gather action is the
 * fishing method ("Small Net", "Bait", "Lure", "Cage", "Harpoon").
 *
 * Unlike the old fisher — which never provisioned a tool, so it could neither fish nor bank — this one
 * declares the method's **tool and bait as a Loadout** (a small fishing net, or a rod + feathers/fishing
 * bait) and gathers them through the Loadouts API before fishing: it web-walks to a bank, withdraws the gear
 * (buying any shortfall off the GE when "Buy missing from GE" is on), then travels to a spot and fishes,
 * banking or dropping the catch when full. "Auto" picks the best method for your level and its fish.
 */
class FisherPlugin : ScriptDslPlugin(), HasOverlay {

    object Config : PluginConfig("fisher") {
        var auto by boolItem("auto", "Auto-select method", false,
            "Pick the best fishing method for your level (ignores Method/Catch below)", section = "Setup")
        // The manual spot/method/catch are ignored while "Auto-select" is on — so hide them then.
        var spot by npcItem("spot", "Spot name", "Fishing spot", "NPC name of the fishing spot",
            filter = listOf("fishing"), browse = true, visibleIf = isFalse("auto"))
        var method by stringItem("method", "Method", "Small Net", "Fishing action: Small Net, Bait, Lure, Cage or Harpoon", visibleIf = isFalse("auto"))
        var catch by itemListItem("catch", "Catch names", "Raw shrimps,Raw anchovies", "Fish to drop/bank", visibleIf = isFalse("auto"))
        var bank by boolItem("bank", "Bank catch", false, "Bank the catch when full (else drop it)")
        var buyMissingFromGE by boolItem("buyMissingFromGE", "Buy missing from GE", false,
            "If the bank can't cover the fishing tool / bait, buy the shortfall off the Grand Exchange", section = "Setup")
        var walk by boolItem("walk", "Walk to spots", true,
            "When no fishing spot is nearby, web-walk to the nearest catalogued one", section = "Setup")
        // Return tile only matters when banking; power-drop delays only when dropping.
        var home by stringItem("home", "Spot tile", "", "Optional 'x,y[,plane]' to walk back to after banking",
            visibleIf = isTrue("bank"))

        var minDrop by intItem("minDrop", "Min drop (ms)", 90, 20, 2000, "Fastest per-fish pause when power-dropping", "Catch", visibleIf = isFalse("bank"))
        var maxDrop by intItem("maxDrop", "Max drop (ms)", 230, 20, 3000, "Slowest per-fish pause when power-dropping", "Catch", visibleIf = isFalse("bank"))

        var lockInput by boolItem("lockInput", "Lock user input", false,
            "While running, ignore physical mouse/keyboard input so it can't disrupt the bot", section = "Antiban")

        var stopAtLevel by intItem("stopAtLevel", "Stop at level", 0, 0, 99, "Stop when Fishing hits this level (0 = never)", "Stopping")
        var stopAtFish by intItem("stopAtFish", "Stop at fish", 0, 0, 1_000_000, "Stop after this many fish (0 = never)", "Stopping")
        var stopAtGp by intItem("stopAtGp", "Stop at GP", 0, 0, 2_000_000_000, "Stop once the catch is worth this many GP (0 = never)", "Stopping")
        var stopAfterMins by intItem("stopAfterMins", "Stop after (min)", 0, 0, 100_000, "Stop after this many minutes (0 = never)", "Stopping")
    }

    override fun config() = Config

    private val stats by lazy { SkillStats(ctx, Skill.FISHING) }
    private val stops by lazy {
        StopTargets(stats,
            level = { Config.stopAtLevel }, count = { Config.stopAtFish },
            gp = { Config.stopAtGp }, minutes = { Config.stopAfterMins },
            gpEach = { catchNames().maxOfOrNull { prices.price(it) } ?: 0 })
    }

    private fun activeMethod(): FishMethod = SkillTiers.bestForLevel(SkillTiers.FISH_METHODS, skills.real(Skill.FISHING), "Auto")

    /** The catalogued method for the current config — its tool/bait drive the loadout. Null when a manual
     *  Method string matches no known method (then the user provides their own gear). */
    private fun resolvedMethod(): FishMethod? =
        if (Config.auto) activeMethod()
        else SkillTiers.FISH_METHODS.firstOrNull { it.action.equals(Config.method, true) || it.name.equals(Config.method, true) }

    private fun methodAction(): String = if (Config.auto) activeMethod().action else Config.method
    private fun catchNames(): List<String> =
        if (Config.auto) activeMethod().fish else Config.catch.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    override fun onScriptStart() {
        stats.start()
        stats.carried = { catchNames().sumOf { inventory.count(it) } }
    }

    override fun onScriptStop() { if (input.isLocked()) input.unlock() }

    override fun script(): Script = skillGatherScript(
        GatherSpec(
            name = "fisher",
            loadout = {
                loadout("fisher") {
                    val m = resolvedMethod()
                    if (m != null) {
                        // The fishing tool is held (not worn); withdraw it, buying one only when the bank is dry.
                        item(ItemRef(m.tool), restock = if (Config.buyMissingFromGE) RestockSpec(ItemRef(m.tool), 1) else null)
                        // Feathers / fishing bait: withdraw the bank's whole stock (fill toward a large target).
                        m.bait?.let { b ->
                            item(ItemRef(b), quantity = BAIT_QTY, minimum = 1,
                                restock = if (Config.buyMissingFromGE) RestockSpec(ItemRef(b), BAIT_QTY) else null)
                        }
                    }
                }
            },
            required = {
                resolvedMethod()?.let { m -> listOfNotNull(ToolNeed(m.tool), m.bait?.let { ToolNeed(it, 1) }) } ?: emptyList()
            },
            findResource = { npcs.closest(Config.spot, methodAction()) },
            action = { methodAction() },
            products = { catchNames().map { ItemRef(it) } },
            bank = { Config.bank },
            resourceName = { Config.spot.takeIf { Config.walk && it.isNotBlank() } },
            resourceKind = ResourceKind.NPC,
            homeTile = { configuredTile(Config.home) },
            buyMissingFromGE = { Config.buyMissingFromGE },
            dropParams = { DropParams(Config.minDrop, Config.maxDrop, 5, 400, 900) },
            lockInput = { Config.lockInput },
            stopReason = { stops.reason() },
            stats = stats,
        )
    )

    override fun overlayTitle() = "Fishing"

    override fun onOverlay(gui: ScriptGui) {
        val each = catchNames().maxOfOrNull { prices.price(it) } ?: 0
        SkillOverlay.render(gui, stats, listOf(
            "Method" to methodAction(),
            (if (Config.bank) "Fish banked" else "Fish dropped")
                to "${SkillOverlay.commas(stats.output())} (${SkillOverlay.compact(stats.perHour(stats.output() * each))} gp/hr)",
        ))
    }

    private companion object {
        /** Target feather/bait stack to withdraw — large, so the loadout takes the bank's whole supply. */
        const val BAIT_QTY = 20_000
    }
}
