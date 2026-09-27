package dev.ceseasons.agriculture;

import dev.ceseasons.season.Season;
import java.util.Set;

/** Pure rules: callers supply native biome classification, never season-adjusted temperature. */
public final class FertilityRules {
    private FertilityRules() {}
    public enum OffSeason { SLOW, STOP, WITHER }
    public enum Decision { ALLOW, DENY, WITHER }
    public record Environment(Season season, boolean tropical, boolean nativeCold,
                              boolean greenhouse, boolean underground) {}

    public static boolean fertile(Set<Season> allowed, Environment environment) {
        if (allowed == null || allowed.isEmpty()) return true; // not a configured crop
        if (environment.greenhouse() || environment.underground()) return true;
        Season local = environment.nativeCold() ? Season.WINTER
                : environment.tropical() ? Season.SUMMER : environment.season();
        return local == null || allowed.contains(local); // snapshot not ready: no destructive action
    }

    /** roll is uniform [0,6); consume exactly one roll per attempted growth. */
    public static Decision decide(Set<Season> allowed, Environment environment, OffSeason policy, int roll) {
        return decide(allowed, environment, policy, roll, 6);
    }

    public static Decision decide(Set<Season> allowed, Environment environment, OffSeason policy, int roll, int denominator) {
        if (denominator < 1 || roll < 0 || roll >= denominator) throw new IllegalArgumentException("Invalid roll/denominator");
        if (fertile(allowed, environment)) return Decision.ALLOW;
        return switch (policy) {
            case SLOW -> roll == 0 ? Decision.ALLOW : Decision.DENY;
            case STOP -> Decision.DENY;
            case WITHER -> Decision.WITHER;
        };
    }
}
