package best.spaghetcodes.duckdueller.bot

import best.spaghetcodes.duckdueller.DuckDueller
import best.spaghetcodes.duckdueller.core.KeyBindings
import best.spaghetcodes.duckdueller.events.packet.PacketEvent
import best.spaghetcodes.duckdueller.utils.ChatUtils
import best.spaghetcodes.duckdueller.utils.HttpUtils
import best.spaghetcodes.duckdueller.utils.RandomUtils
import best.spaghetcodes.duckdueller.utils.TimeUtils
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import net.minecraft.client.Minecraft
import net.minecraft.network.play.server.S19PacketEntityStatus
import net.minecraft.network.play.server.S3EPacketTeams
import net.minecraft.util.EnumChatFormatting
import net.minecraftforge.event.entity.player.AttackEntityEvent
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
import net.minecraftforge.fml.common.gameevent.TickEvent.ClientTickEvent
import kotlin.concurrent.thread

/**
 * Base class for all bots
 * @param queueCommand Command to join a new game
 * @param quickRefresh MS for which to quickly refresh opponent entity
 */
open class BotBase(val queueCommand: String, val quickRefresh: Int = 10000) {

    protected val mc = Minecraft.getMinecraft()

    private var toggled = false
    fun toggled() = toggled
    fun toggle() {
        toggled = !toggled
    }

    private var attackedID = -1

    private var statKeys: Map<String, String> = mapOf("wins" to "", "losses" to "", "ws" to "")

    private var playerCache: HashMap<String, String> = hashMapOf()
    private var playersSent: ArrayList<String> = arrayListOf()

    /********
     * Methods to override
     ********/

    /**
     * Called when the bot attacks the opponent
     * Triggered by the damage sound, not the clientside attack event
     */
    protected open fun onAttack() {}

    /**
     * Called when the bot is attacked
     * Triggered by the damage sound, not the clientside attack event
     */
    protected open fun onAttacked() {}

    /**
     * Called when the game starts
     */
    protected open fun onGameStart() {}

    /**
     * Called when the game ends
     */
    protected open fun onGameEnd() {}

    /**
     * Called when the bot joins a game
     */
    protected open fun onJoinGame() {}

    /**
     * Called before the game starts (1s)
     */
    protected open fun beforeStart() {}

    /**
     * Called before the bot leaves the game (dodge)
     */
    protected open fun beforeLeave() {}

    /**
     * Called when the opponent entity is found
     */
    protected open fun onFoundOpponent() {}

    /**
     * Called every tick
     */
    protected open fun onTick() {}

    /********
     * Protected Methods
     ********/

    protected fun setStatKeys(keys: Map<String, String>) {
        statKeys = keys
    }

    /********
     * Base Methods
     ********/

    @SubscribeEvent
    fun onPacket(ev: PacketEvent) {
        if (toggled) {
            when (ev.getPacket()) {
                is S19PacketEntityStatus -> { // use the status packet for attack events
                    val packet = ev.getPacket() as S19PacketEntityStatus
                    if (packet.opCode.toInt() == 2) { // damage
                        val entity = packet.getEntity(mc.theWorld)
                        if (entity != null) {
                            if (entity.entityId == attackedID) {
                                attackedID = -1
                                onAttack()
                            } else if (mc.thePlayer != null && entity.entityId == mc.thePlayer.entityId) {
                                onAttacked()
                            }
                        }
                    }
                }
                is S3EPacketTeams -> { // use this for stat checking
                    val packet = ev.getPacket() as S3EPacketTeams
                    if (packet.action == 3 && packet.name == "§7§k") {
                        val players = packet.players
                        for (player in players) {
                            TimeUtils.setTimeout(fun () { // timeout to allow ingame state to update
                                handlePlayer(player)
                            }, 1500)
                        }
                    }
                }
            }
        }
    }

    @SubscribeEvent
    fun onAttackEntityEvent(ev: AttackEntityEvent) {
        if (toggled() && ev.entity == mc.thePlayer) {
            attackedID = ev.target.entityId
        }
    }

    @SubscribeEvent
    fun onClientTick(ev: ClientTickEvent) {
        if (toggled) {
            onTick()
        }

        if (KeyBindings.toggleBotKeyBinding.isPressed) {
            toggle()
            ChatUtils.info("Duck Dueller has been toggled ${if (toggled()) "${EnumChatFormatting.GREEN}on" else "${EnumChatFormatting.RED}off"}")
            if (toggled()) {
                joinGame()
            }
        }
    }

    /********
     * Private Methods
     ********/

    /**
     * Called for each player that joins
     */
    private fun handlePlayer(player: String) {
        thread { // move into new thread to avoid blocking the main thread
            if (StateManager.state == StateManager.States.GAME) { // make sure we're in-game, to not spam the api
                if (player.length > 2) { // hypixel sends a bunch of fake 1-2 char entities
                    var uuid: String? = null
                    if (playerCache.containsKey(player)) { // check if the player is in the cache
                        uuid = playerCache[player]
                    } else {
                        uuid = HttpUtils.usernameToUUID(player)
                    }

                    if (uuid == null) { // nicked or fake player
                        //TODO: Check the list of players the bot has lost to
                    } else {
                        playerCache[player] = uuid // cache this player
                        if (!playersSent.contains(player)) { // don't send the same player twice
                            playersSent.add(player)
                            if (mc.thePlayer != null) {
                                if (player == mc.thePlayer.displayNameString) { // if the player is the bot
                                    onJoinGame()
                                }
                            } else {
                                val stats = HttpUtils.getPlayerStats(uuid) ?: return@thread
                                handleStats(stats, player)
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Handle the response from the hypixel api
     */
    private fun handleStats(stats: JsonObject, player: String) {
        if (toggled() && stats.get("success").asBoolean) {
            if (statKeys.containsKey("wins") && statKeys.containsKey("losses") && statKeys.containsKey("ws")) {
                fun getStat(key: String): JsonElement? {
                    var tmpObj = stats

                    for (p in key.split(".")) {
                        if (tmpObj.has(p) && tmpObj.get(p).isJsonObject)
                            tmpObj = tmpObj.get(p).asJsonObject
                        else if (tmpObj.has(p))
                            return tmpObj.get(p)
                        else
                            return null
                    }
                    return null
                }

                val wins = getStat(statKeys["wins"]!!)?.asInt ?: 0
                val losses = getStat(statKeys["losses"]!!)?.asInt ?: 0
                val ws = getStat(statKeys["ws"]!!)?.asInt ?: 0
                val wlr = wins / (if (losses == 0) 1 else losses)

                ChatUtils.info("$player ${EnumChatFormatting.GOLD} >> ${EnumChatFormatting.GOLD}Wins: ${EnumChatFormatting.GREEN}$wins ${EnumChatFormatting.GOLD} WLR: ${EnumChatFormatting.GREEN} $wlr ${EnumChatFormatting.GOLD}WS: ${EnumChatFormatting.GREEN} $ws")

                var dodge = false

                if (DuckDueller.config?.enableDodging == true) {
                    val config = DuckDueller.config
                    if (wins > config?.dodgeWins!!) {
                        dodge = true
                    } else if (wlr > config.dodgeWLR) {
                        dodge = true
                    } else if (ws > config.dodgeWS) {
                        dodge = true
                    }
                }

                if (dodge) {
                    beforeLeave()
                    leaveGame()
                }
            }
        } else if (toggled()) {
            ChatUtils.error("Error getting stats! Check the log for more information.")
            println("Error getting stats! success == false")
            println(DuckDueller.gson.toJson(stats))
        }
    }

    private fun leaveGame() {
        TimeUtils.setTimeout(fun () {
            ChatUtils.sendAsPlayer("/l")
        }, RandomUtils.randomIntInRange(100, 300))
    }

    private fun joinGame() {
        TimeUtils.setTimeout(fun () {
            ChatUtils.sendAsPlayer(queueCommand)
        }, RandomUtils.randomIntInRange(100, 300))
    }

}