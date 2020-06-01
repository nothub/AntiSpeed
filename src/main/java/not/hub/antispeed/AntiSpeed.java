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
import java.util.*;
import java.util.stream.Collectors;

@SuppressWarnings("UnstableApiUsage")
public final class AntiSpeed extends JavaPlugin implements Listener {

    private static int configMaxBps;
    private static boolean configIgnoreVertical;
    private static boolean configRandomizeRotations;
    private static boolean configViolationMessageEnabled;
    private static String configViolationMessage;
    private static boolean configVerboseLogging;

    private Map<UUID, Location> lastTickLocations;
    private Map<UUID, EvictingQueue<Double>> historicalDistances;
    private Map<UUID, Location> rubberbandLocations;
    private Map<UUID, Boolean> onCooldown;

    private DecimalFormat bpsFormatter;
    private DecimalFormat locFormatter;
    private Random random;

    @Override
    public void onEnable() {

        this.lastTickLocations = new HashMap<>();
        this.rubberbandLocations = new HashMap<>();
        this.historicalDistances = new HashMap<>();
        this.onCooldown = new HashMap<>();

        bpsFormatter = new DecimalFormat("#.000");
        bpsFormatter.setRoundingMode(RoundingMode.FLOOR);

        locFormatter = new DecimalFormat("#.00");
        locFormatter.setRoundingMode(RoundingMode.FLOOR);

        random = new Random();

        initConfig();

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

    private void initConfig() {

        loadConfig();

        configVerboseLogging = getConfig().getBoolean("verbose-logging");

        configMaxBps = getConfig().getInt("blocks-traveled-per-20-ticks-limit");
        configIgnoreVertical = getConfig().getBoolean("ignore-vertical-movement");
        configRandomizeRotations = getConfig().getBoolean("randomize-rotations-on-violation");
        configViolationMessageEnabled = getConfig().getBoolean("warn-message-enabled");
        configViolationMessage = getConfig().getString("warn-message");

        Log.debug("blocks-traveled-per-20-ticks-limit=" + configMaxBps);
        Log.debug("ignore-vertical-movement=" + configIgnoreVertical);
        Log.debug("randomize-rotations-on-violation=" + configRandomizeRotations);
        Log.debug("warn-message-enabled=" + configViolationMessageEnabled);
        Log.debug("warn-message=\"" + configViolationMessage + "\"");
        Log.debug("verbose-logging=" + configVerboseLogging);

    }

    @Override
    public void onDisable() {
        HandlerList.unregisterAll((Plugin) this);
        getServer().getScheduler().cancelTasks(this);
    }

    public void calcPlayerDistanceDiff(Player player) {

        if (!lastTickLocations.containsKey(player.getUniqueId()) || !historicalDistances.containsKey(player.getUniqueId())) {
            enableCooldown(player);
            resetPlayerData(player, player.getName() + " was not seen before");
            return;
        }

        if (!lastTickLocations.get(player.getUniqueId()).getWorld().equals(player.getWorld())) {
            enableCooldown(player);
            resetPlayerData(player, player.getName() + " changed world: " + lastTickLocations.get(player.getUniqueId()).getWorld().getName() + " -> " + player.getWorld().getName());
            return;
        }

        EvictingQueue<Double> historicalDistancesPlayer = historicalDistances.get(player.getUniqueId());
        Location location = getPlayerMeasuringLocation(player);
        historicalDistancesPlayer.add(location.distance(lastTickLocations.get(player.getUniqueId())));
        historicalDistances.put(player.getUniqueId(), historicalDistancesPlayer);

        lastTickLocations.put(player.getUniqueId(), location);

    }

    private void violationAction(Player player, double bps) {

        if (isOnCooldown(player)) {
            Log.debug(player.getName() + " is too fast, but hes on cooldown so its cool :)");
            return;
        }

        Log.info(ChatColor.YELLOW + player.getName() + " is too fast: " + bpsFormatter.format(bps) + "b/s (" + bpsFormatter.format(bps * 3.6) + "kb/h)");

        Location originalLocation = rubberbandLocations.get(player.getUniqueId());

        float yaw, pitch;

        if (configRandomizeRotations) {
            yaw = random.nextInt(90) * (random.nextBoolean() ? -1 : 1);
            pitch = (random.nextInt(90 - 45) + 45) * -1;
        } else {
            yaw = player.getLocation().getYaw();
            pitch = player.getLocation().getPitch();
        }

        Location targetLocation = new Location(
                originalLocation.getWorld(),
                originalLocation.getX(),
                originalLocation.getY(),
                originalLocation.getZ(),
                yaw,
                pitch
        );

        player.setGliding(false);

        PaperLib.teleportAsync(player, targetLocation).thenAccept(result -> {

            StringBuilder targetLocationString = new StringBuilder()
                    .append("world=").append(targetLocation.getWorld().getName())
                    .append(", x=").append(locFormatter.format(targetLocation.getX()))
                    .append(", y=").append(locFormatter.format(targetLocation.getY()))
                    .append(", z=").append(locFormatter.format(targetLocation.getZ()));

            if (result) {
                Log.info(ChatColor.YELLOW + "Teleported " + player.getName() + " back to: " + targetLocationString);
                if (configViolationMessageEnabled) {
                    player.sendMessage(configViolationMessage);
                }
            } else {
                Log.error(ChatColor.RED + "Unable to teleport " + player.getName() + " back to: " + targetLocationString);
            }

        });

    }

    private Location getPlayerMeasuringLocation(Player player) {
        Location location = player.getLocation();
        if (configIgnoreVertical) {
            location.setY(0);
        }
        return location;
    }

    private void schedulePlayerDataReset(Player player, String reason) {
        Log.debug("Scheduling data reset for: " + player.getName() + " Reason: " + reason);
        getServer().getScheduler().scheduleSyncDelayedTask(this, () ->
                resetPlayerData(player, reason), 1L);
    }

    public void resetPlayerData(Player player, String reason) {
        Log.debug("Resetting data for: " + player.getName() + " Reason: " + reason);
        lastTickLocations.put(player.getUniqueId(), getPlayerMeasuringLocation(player));
        rubberbandLocations.put(player.getUniqueId(), player.getLocation());
        historicalDistances.put(player.getUniqueId(), EvictingQueue.create(20));
    }

    public void removePlayerData(Player player, String reason) {
        Log.debug("Removing data for: " + player.getName() + " Reason: " + reason);
        lastTickLocations.remove(player.getUniqueId());
        rubberbandLocations.remove(player.getUniqueId());
        historicalDistances.remove(player.getUniqueId());
        onCooldown.remove(player.getUniqueId());
    }

    private void enableCooldown(Player player) {
        onCooldown.put(player.getUniqueId(), true);
        Log.debug(player.getName() + " is on cooldown now for 100 ticks");
        getServer().getScheduler().scheduleSyncDelayedTask(this, () ->
        {
            onCooldown.put(player.getUniqueId(), false);
            Log.debug(player.getName() + " is off cooldown again");
        }, 100L);
    }

    private boolean isOnCooldown(Player player) {
        return Optional.ofNullable(onCooldown.get(player.getUniqueId())).orElse(false);
    }

    @EventHandler
    public void onPlayerTeleport(final PlayerTeleportEvent playerTeleportEvent) {
        PlayerTeleportEvent.TeleportCause cause = playerTeleportEvent.getCause();
        if (cause.equals(PlayerTeleportEvent.TeleportCause.UNKNOWN)
                || cause.equals(PlayerTeleportEvent.TeleportCause.NETHER_PORTAL)
                || cause.equals(PlayerTeleportEvent.TeleportCause.END_PORTAL)
        ) {
            Log.debug("Ignoring Teleport event of type: " + playerTeleportEvent.getCause().toString());
            return;
        }
        Log.debug("Detected Teleport event of type: " + playerTeleportEvent.getCause().toString());
        enableCooldown(playerTeleportEvent.getPlayer());
        schedulePlayerDataReset(playerTeleportEvent.getPlayer(), "PlayerTeleportEvent (" + playerTeleportEvent.getCause().toString() + ")");
    }

    @EventHandler
    public void onPlayerRespawn(final PlayerRespawnEvent playerRespawnEvent) {
        enableCooldown(playerRespawnEvent.getPlayer());
        schedulePlayerDataReset(playerRespawnEvent.getPlayer(), "PlayerRespawnEvent");
    }

    @EventHandler
    public void onPlayerJoinEvent(final PlayerJoinEvent playerJoinEvent) {
        enableCooldown(playerJoinEvent.getPlayer());
        resetPlayerData(playerJoinEvent.getPlayer(), "PlayerJoinEvent");
    }

    @EventHandler
    public void onPlayerQuitEvent(final PlayerQuitEvent playerQuitEvent) {
        removePlayerData(playerQuitEvent.getPlayer(), "PlayerQuitEvent");
    }

    private void loadConfig() {
        getConfig().addDefault("blocks-traveled-per-20-ticks-limit", 80);
        getConfig().addDefault("ignore-vertical-movement", true);
        getConfig().addDefault("randomize-rotations-on-violation", true);
        getConfig().addDefault("warn-message-enabled", true);
        getConfig().addDefault("warn-message", ChatColor.LIGHT_PURPLE + "Leeeunderscore whispers: Dude slow down or I will ban you!");
        getConfig().addDefault("verbose-logging", false);
        getConfig().options().copyDefaults(true);
        saveConfig();
    }

    static class Log {

        public static final Logger LOGGY = LogManager.getLogger("AntiSpeed");

        public static void debug(String message) {
            if (configVerboseLogging) {
                LOGGY.info(message);
            } else {
                LOGGY.debug(message);
            }
        }

        public static void info(String message) {
            LOGGY.info(message);
        }

        public static void warn(String message) {
            LOGGY.warn(message);
        }

        public static void error(String message) {
            LOGGY.error(message);
        }

    }

}
