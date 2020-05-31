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
import java.util.Random;
import java.util.UUID;
import java.util.stream.Collectors;

@SuppressWarnings("UnstableApiUsage")
public final class AntiSpeed extends JavaPlugin implements Listener {

    public static final Logger LOGGY = LogManager.getLogger("AntiSpeed");

    private static int configMaxBps;
    private static boolean configIgnoreY;
    private static String configViolationMessage;
    private static boolean configViolationMessageEnabled;

    private Map<UUID, Location> lastTickLocations;
    private Map<UUID, EvictingQueue<Double>> historicalDistances;
    private Map<UUID, Location> rubberbandLocations;

    private DecimalFormat bpsFormatter;
    private DecimalFormat locFormatter;
    private Random random;

    @Override
    public void onEnable() {

        this.lastTickLocations = new HashMap<>();
        this.rubberbandLocations = new HashMap<>();
        this.historicalDistances = new HashMap<>();

        bpsFormatter = new DecimalFormat("#.000");
        bpsFormatter.setRoundingMode(RoundingMode.FLOOR);

        locFormatter = new DecimalFormat("#.00");
        locFormatter.setRoundingMode(RoundingMode.FLOOR);

        random = new Random();

        loadConfig();
        configMaxBps = getConfig().getInt("blocks-traveled-per-20-ticks-limit");
        configIgnoreY = getConfig().getBoolean("ignore-y-movement");
        configViolationMessageEnabled = getConfig().getBoolean("warn-message-enabled");
        configViolationMessage = getConfig().getString("warn-message");

        LOGGY.info("Blocks traveled per 20 ticks limit: " + configMaxBps);
        LOGGY.info("Ignoring Y movement: " + configIgnoreY);

        getServer().getPluginManager().registerEvents(this, this);

        getServer().getScheduler().scheduleSyncRepeatingTask(this, () -> getServer().getOnlinePlayers().forEach(this::calcPlayerDistanceDiff), 20L, 1L);

        getServer().getScheduler().scheduleSyncRepeatingTask(this, () -> getServer().getOnlinePlayers().forEach(player -> {

            double bps = historicalDistances.get(player.getUniqueId()).stream().collect(Collectors.summarizingDouble(Double::doubleValue)).getSum();

            if (bps > configMaxBps) {
                violationAction(player, bps);
            }

            rubberbandLocations.put(player.getUniqueId(), player.getLocation());

        }), 40L, 20L);

    }

    @Override
    public void onDisable() {
        HandlerList.unregisterAll((Plugin) this);
        getServer().getScheduler().cancelTasks(this);
    }

    public void calcPlayerDistanceDiff(Player player) {

        if (!lastTickLocations.containsKey(player.getUniqueId()) || !historicalDistances.containsKey(player.getUniqueId())) {
            resetPlayerData(player, player.getName() + " was not seen before, somehow we missed the join event");
            return;
        }

        if (!lastTickLocations.get(player.getUniqueId()).getWorld().equals(player.getWorld())) {
            resetPlayerData(player, player.getName() + " changed world, somehow we missed the teleport event");
            return;
        }

        EvictingQueue<Double> historicalDistancesPlayer = historicalDistances.get(player.getUniqueId());
        Location location = getPlayerMeasuringLocation(player);
        historicalDistancesPlayer.add(location.distance(lastTickLocations.get(player.getUniqueId())));
        historicalDistances.put(player.getUniqueId(), historicalDistancesPlayer);

        lastTickLocations.put(player.getUniqueId(), location);

    }

    private void violationAction(Player player, double bps) {

        LOGGY.info(player.getName() + " is too fast: " + bpsFormatter.format(bps) + "b/s (" + bpsFormatter.format(bps * 3.6) + "kb/h)");

        Location originalLocation = rubberbandLocations.get(player.getUniqueId());
        Location targetLocation = new Location(originalLocation.getWorld(), originalLocation.getX(), originalLocation.getY(), originalLocation.getZ(),
                random.nextInt(90) * (random.nextBoolean() ? -1 : 1),
                (random.nextInt(90 - 45) + 45) * -1
        );

        player.setGliding(false);

        PaperLib.teleportAsync(player, targetLocation).thenAccept(result -> {

            StringBuilder targetLocationString = new StringBuilder()
                    .append("world=").append(targetLocation.getWorld().getName())
                    .append(", x=").append(locFormatter.format(targetLocation.getX()))
                    .append(", y=").append(locFormatter.format(targetLocation.getY()))
                    .append(", z=").append(locFormatter.format(targetLocation.getZ()));

            if (result) {
                LOGGY.info("Teleported " + player.getName() + " back to: " + targetLocationString);
                if (configViolationMessageEnabled) {
                    player.sendMessage(configViolationMessage);
                }
            } else {
                LOGGY.warn("Unable to teleport " + player.getName() + " back to: " + targetLocationString);
            }

        });

    }

    private Location getPlayerMeasuringLocation(Player player) {
        Location location = player.getLocation();
        if (configIgnoreY) {
            location.setY(0);
        }
        return location;
    }

    public void resetPlayerData(Player player, String reason) {
        LOGGY.info("Resetting data for: " + player.getName() + " Reason: " + reason);
        lastTickLocations.put(player.getUniqueId(), getPlayerMeasuringLocation(player));
        rubberbandLocations.put(player.getUniqueId(), player.getLocation());
        historicalDistances.put(player.getUniqueId(), EvictingQueue.create(20));
    }

    public void removePlayerData(Player player, String reason) {
        LOGGY.info("Removing data for: " + player.getName() + " Reason: " + reason);
        lastTickLocations.remove(player.getUniqueId());
        rubberbandLocations.remove(player.getUniqueId());
        historicalDistances.remove(player.getUniqueId());
    }

    private void schedulePlayerDataReset(Player player, String reason) {
        LOGGY.info("Scheduling data reset for: " + player.getName() + " Reason: " + reason);
        getServer().getScheduler().scheduleSyncDelayedTask(this, () ->
                resetPlayerData(player,
                        reason), 1L);
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
        removePlayerData(playerQuitEvent.getPlayer(), "PlayerQuitEvent");
    }

    private void loadConfig() {
        getConfig().addDefault("blocks-traveled-per-20-ticks-limit", 80);
        getConfig().addDefault("ignore-y-movement", true);
        getConfig().addDefault("warn-message-enabled", true);
        getConfig().addDefault("warn-message", ChatColor.LIGHT_PURPLE + "0bOp whispers: Dude slow down or the server will catch on fire!");
        getConfig().options().copyDefaults(true);
        saveConfig();
    }

}
