package not.hub.antispeed;

import com.google.common.collect.EvictingQueue;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@SuppressWarnings("UnstableApiUsage")
public final class AntiSpeed extends JavaPlugin implements Listener {

    // TODO: clear player data on leave event or keep for distance diff compare after relog?

    public static final Logger LOGGY = LogManager.getLogger("AntiSpeed");

    Map<UUID, Location> lastLocations;
    Map<UUID, EvictingQueue<Double>> historicalDistances;

    public void resetPlayer(Player player) {
        lastLocations.put(player.getUniqueId(), player.getLocation());
        historicalDistances.put(player.getUniqueId(), EvictingQueue.create(10));
    }

    public void calcPlayerDistanceDiff(Player player) {

        if (!lastLocations.containsKey(player.getUniqueId()) || !lastLocations.get(player.getUniqueId()).getWorld().equals(player.getWorld())) {
            resetPlayer(player);
            return;
        }

        Location currentLocation = player.getLocation();
        Double distanceCurrentToLast = Math.round(currentLocation.distance(lastLocations.get(player.getUniqueId())) * 10) / 10.0;

        EvictingQueue<Double> historicalDistancesPlayer = historicalDistances.get(player.getUniqueId());
        historicalDistancesPlayer.add(distanceCurrentToLast);
        historicalDistances.put(player.getUniqueId(), historicalDistancesPlayer);

        lastLocations.put(player.getUniqueId(), currentLocation);

        // TODO: do action on speed violation
        LOGGY.info(historicalDistances.toString());
        historicalDistances.forEach((uuid, doubles) ->
                LOGGY.info(uuid + " average distance diff: " + doubles.stream().collect(
                        Collectors.summarizingDouble(Double::doubleValue))));

    }

    @Override
    public void onEnable() {

        this.lastLocations = new HashMap<>();
        this.historicalDistances = new HashMap<>();

        getServer().getPluginManager().registerEvents(this, this);

        getServer().getScheduler().scheduleSyncRepeatingTask(this, () ->
                getServer().getOnlinePlayers().forEach(
                        this::calcPlayerDistanceDiff
                ), 0L, 10L);

    }

    @EventHandler
    public void onPlayerTeleport(final PlayerTeleportEvent playerTeleportEvent) {

        if (playerTeleportEvent.getCause().equals(PlayerTeleportEvent.TeleportCause.UNKNOWN)) {
            return;
        }

        LOGGY.info("Resetting stats for " + playerTeleportEvent.getPlayer().getName() + " because of PlayerTeleportEvent (" + playerTeleportEvent.getCause().toString() + ")");

        getServer().getScheduler().scheduleSyncDelayedTask(this, () ->
                resetPlayer(playerTeleportEvent.getPlayer()
                ), 1L);

    }

    @EventHandler
    public void onPlayerRespawn(final PlayerRespawnEvent playerRespawnEvent) {

        LOGGY.info("Resetting stats for " + playerRespawnEvent.getPlayer().getName() + " because of PlayerRespawnEvent");

        getServer().getScheduler().scheduleSyncDelayedTask(this, () ->
                resetPlayer(playerRespawnEvent.getPlayer()
                ), 1L);

    }

}
