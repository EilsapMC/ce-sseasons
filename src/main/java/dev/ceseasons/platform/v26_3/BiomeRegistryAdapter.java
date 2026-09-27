package dev.ceseasons.platform.v26_3;

import dev.ceseasons.visual.BiomeMappings;
import net.momirealms.craftengine.bukkit.util.RegistryUtils;
import net.momirealms.craftengine.proxy.minecraft.core.RegistryProxy;
import net.momirealms.craftengine.proxy.minecraft.core.registries.RegistriesProxy;
import net.momirealms.craftengine.proxy.minecraft.resources.IdentifierProxy;

import java.util.List;
import java.util.Set;

/** Only runs at enable/reload. Never enumerates a Bukkit enum or guesses raw IDs. */
public final class BiomeRegistryAdapter {
    private BiomeRegistryAdapter() {}

    public static BiomeMappings load(List<BiomeMappings.Definition> definitions, Set<String> blacklist) {
        Object registry = RegistryUtils.lookupOrThrow(RegistriesProxy.BIOME);
        int size = RegistryUtils.currentBiomeRegistrySize();
        return new BiomeMappings(size, definitions, key -> {
            Object identifier = IdentifierProxy.INSTANCE.tryParse(key);
            if (identifier == null) return -1;
            Object value = RegistryUtils.getRegistryValue(registry, identifier);
            if (value == null) return -1;
            Object actualKey = RegistryProxy.INSTANCE.getKey(registry, value);
            // Detect any defaulted lookup silently returning a fallback biome.
            if (actualKey == null || !key.equals(actualKey.toString())) return -1;
            return RegistryProxy.INSTANCE.getId(registry, value);
        }, blacklist);
    }
}
