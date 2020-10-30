package not.hub.antispeed;

import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.UUID;

public class SpaceTimeContinuumCheck {
    private final int timespan;
    private final double violations;
    
    static HashMap<UUID, ActionFrequency> violationData;

    static {
        SpaceTimeContinuumCheck.violationData = new HashMap<>();
    }

    public SpaceTimeContinuumCheck(int timespanSeconds, double violationsPerSecond) {
        this.timespan = timespanSeconds;
        this.violations = violationsPerSecond;
    }

    public boolean violatedTheLawsOfPhysics(final Player player) {
        if (!SpaceTimeContinuumCheck.violationData.containsKey(player.getUniqueId())) {
            SpaceTimeContinuumCheck.violationData.put(player.getUniqueId(), new ActionFrequency(timespan * 10, 100L));
        }

        return this.isRuptured(SpaceTimeContinuumCheck.violationData.get(player.getUniqueId()));
    }

    public boolean isRuptured(final ActionFrequency frequency) {
        frequency.add(System.currentTimeMillis(), 1.0f);
        final long duration = frequency.bucketDuration() * frequency.numberOfBuckets();
        final double amount = frequency.score(1.0f) * 1000.0f / duration;
        return amount > this.violations;
    }

    public void onPlayerQuit(final Player p) {
        SpaceTimeContinuumCheck.violationData.remove(p.getUniqueId());
    }
}
