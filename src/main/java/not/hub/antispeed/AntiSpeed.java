package not.hub.antispeed;

import com.google.common.collect.EvictingQueue;
import io.papermc.lib.PaperLib;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@SuppressWarnings("UnstableApiUsage")
public final class AntiSpeed extends JavaPlugin implements Listener {

    public static final Logger LOGGY = LogManager.getLogger("AntiSpeed");

    private static final int MAX_BPS = 80;

    private Map<UUID, Location> lastTickLocations;
    private Map<UUID, Location> lastCheckLocations;
    private Map<UUID, EvictingQueue<Double>> historicalDistances;

    private DecimalFormat bpsFormat;

    public void calcPlayerDistanceDiff(Player player) {

        if (!lastTickLocations.containsKey(player.getUniqueId()) || !historicalDistances.containsKey(player.getUniqueId())) {
            resetPlayerData(player, "player unknown");
            return;
        }

        if (!lastTickLocations.get(player.getUniqueId()).getWorld().equals(player.getWorld())) {
            resetPlayerData(player, "player world changed");
            return;
        }

        Location currentLocation = player.getLocation();

        EvictingQueue<Double> historicalDistancesPlayer = historicalDistances.get(player.getUniqueId());
        historicalDistancesPlayer.add(currentLocation.distance(lastTickLocations.get(player.getUniqueId())));
        historicalDistances.put(player.getUniqueId(), historicalDistancesPlayer);

        lastTickLocations.put(player.getUniqueId(), currentLocation);

    }

    private void violationAction(Player player, double bps) {

        LOGGY.info("speed for " + player.getName() + ": " + bpsFormat.format(bps) + "m/s (" + bpsFormat.format(bps * 3.6) + "km/h)");

        Location lastLocation = lastCheckLocations.get(player.getUniqueId());
        Location newLocation = new Location(lastLocation.getWorld(), lastLocation.getX(), lastLocation.getY(), lastLocation.getZ(), lastLocation.getYaw() * -1, -90);

        player.setGliding(false);

        PaperLib.teleportAsync(player, newLocation).thenAccept(result -> {

            String location = newLocation.toString();

            if (result) {
                LOGGY.info("Teleported " + player.getName() + " to " + location);
                player.sendMessage(ChatColor.LIGHT_PURPLE + "0bOp whispers: Dude slow down or the server will catch fire!");
            } else {
                LOGGY.warn("Unable to teleport " + player.getName() + " to " + location);
            }

        });

    }

    public void resetPlayerData(Player player, String reason) {
        LOGGY.info("Resetting data for: " + player.getName() + " Reason: " + reason);
        lastTickLocations.put(player.getUniqueId(), player.getLocation());
        lastCheckLocations.put(player.getUniqueId(), player.getLocation());
        historicalDistances.put(player.getUniqueId(), EvictingQueue.create(20));
    }

    private void schedulePlayerDataReset(Player player, String reason) {
        LOGGY.info("Scheduling data reset for: " + player.getName() + " Reason: " + reason);
        getServer().getScheduler().scheduleSyncDelayedTask(this, () ->
                resetPlayerData(player,
                        reason), 1L);
    }

    @Override
    public void onEnable() {

        this.lastTickLocations = new HashMap<>();
        this.lastCheckLocations = new HashMap<>();
        this.historicalDistances = new HashMap<>();

        bpsFormat = new DecimalFormat("#.000");
        bpsFormat.setRoundingMode(RoundingMode.FLOOR);

        getServer().getPluginManager().registerEvents(this, this);

        getServer().getScheduler().scheduleSyncRepeatingTask(this, () -> getServer().getOnlinePlayers().forEach(this::calcPlayerDistanceDiff), 20L, 1L);

        getServer().getScheduler().scheduleSyncRepeatingTask(this, () -> getServer().getOnlinePlayers().forEach(player -> {

            double bps = historicalDistances.get(player.getUniqueId()).stream().collect(Collectors.summarizingDouble(Double::doubleValue)).getSum();

            if (bps > MAX_BPS) {
                violationAction(player, bps);
            }

            lastCheckLocations.put(player.getUniqueId(), player.getLocation());

        }), 40L, 20L);

    }

    @Override
    public void onDisable() {
        HandlerList.unregisterAll((Plugin) this);
        getServer().getScheduler().cancelTasks(this);
    }

    @EventHandler
    public void onPlayerTeleport(final PlayerTeleportEvent playerTeleportEvent) {
        if (playerTeleportEvent.getCause().equals(PlayerTeleportEvent.TeleportCause.UNKNOWN)) {
            return;
        }
        schedulePlayerDataReset(playerTeleportEvent.getPlayer(), "PlayerTeleportEvent (" + playerTeleportEvent.getCause().toString() + ")");
    }

    @EventHandler
    public void onPlayerRespawn(final PlayerRespawnEvent playerRespawnEvent) {
        schedulePlayerDataReset(playerRespawnEvent.getPlayer(), "PlayerRespawnEvent");
    }

    @EventHandler
    public void onPlayerJoinEvent(final PlayerJoinEvent playerJoinEvent) {
        resetPlayerData(playerJoinEvent.getPlayer(), "PlayerJoinEvent");
    }

    @EventHandler
    public void onPlayerQuitEvent(final PlayerQuitEvent playerQuitEvent) {
        resetPlayerData(playerQuitEvent.getPlayer(), "PlayerQuitEvent");
    }

}
