package dev.ceseasons.integration;

import dev.ceseasons.season.Season;
import dev.ceseasons.season.SeasonSnapshot;
import net.momirealms.craftengine.core.plugin.context.CommonConditions;
import net.momirealms.craftengine.core.plugin.context.Context;
import net.momirealms.craftengine.core.plugin.context.Condition;
import net.momirealms.craftengine.core.plugin.context.parameter.DirectContextParameters;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.World;
import net.momirealms.craftengine.core.world.WorldPosition;

import java.util.EnumSet;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Function;

/** Registered during onLoad; snapshots may legitimately be unavailable then. */
final class SeasonCondition implements Condition<Context> {
    private final Function<UUID, SeasonSnapshot> snapshots;
    private final EnumSet<Season> seasons;

    private SeasonCondition(Function<UUID, SeasonSnapshot> snapshots, EnumSet<Season> seasons) {
        this.snapshots = snapshots;
        this.seasons = seasons;
    }

    static void register(Function<UUID, SeasonSnapshot> snapshots) {
        CommonConditions.register(Key.of("ce_seasons:season"), section -> {
            EnumSet<Season> accepted = EnumSet.noneOf(Season.class);
            for (String name : section.getStringList("seasons")) {
                accepted.add(Season.valueOf(name.toUpperCase(Locale.ROOT)));
            }
            if (accepted.isEmpty()) throw new IllegalArgumentException("ce_seasons:season requires non-empty seasons");
            return new SeasonCondition(snapshots, accepted);
        });
    }

    @Override
    public boolean test(Context context) {
        World world = context.getOptionalParameter(DirectContextParameters.WORLD)
                .orElseGet(() -> context.getOptionalParameter(DirectContextParameters.POSITION)
                        .map(WorldPosition::world).orElse(null));
        if (world == null) return false;
        SeasonSnapshot snapshot = snapshots.apply(world.uuid());
        return snapshot != null && seasons.contains(snapshot.season());
    }
}
