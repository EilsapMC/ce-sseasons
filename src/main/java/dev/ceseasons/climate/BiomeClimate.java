package dev.ceseasons.climate;

import org.bukkit.block.Biome;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Paper's Biome API does not expose base temperature or hasPrecipitation.
 * This narrow, read-only Mojang-mapped bridge avoids a CraftEngine/NMS dependency.
 * Failure disables block climate rather than guessing custom biome properties.
 */
final class BiomeClimate {
    record Properties(double baseTemperature, boolean precipitation) {
    }

    private record Access(Method handle, Method temperature, Method precipitation) {
    }

    private final Logger logger;
    private final AtomicBoolean warned = new AtomicBoolean();
    private final ClassValue<Access> access = new ClassValue<>() {
        @Override
        protected Access computeValue(Class<?> type) {
            try {
                Class<?> nativeBiome = Class.forName("net.minecraft.world.level.biome.Biome", false, type.getClassLoader());
                return new Access(type.getMethod("getHandle"), nativeBiome.getMethod("getBaseTemperature"),
                        nativeBiome.getMethod("hasPrecipitation"));
            } catch (ReflectiveOperationException failure) {
                warn(failure);
                return new Access(null, null, null);
            }
        }
    };

    BiomeClimate(Logger logger) {
        this.logger = logger;
    }

    Properties read(Biome biome) {
        Access methods = access.get(biome.getClass());
        if (methods.handle() == null) {
            return null;
        }
        try {
            Object nativeBiome = methods.handle().invoke(biome);
            // Native temperatures are floats: widening 0.8f directly would
            // incorrectly blacklist plains (0.800000011920929 > 0.8).
            float nativeTemperature = ((Number) methods.temperature().invoke(nativeBiome)).floatValue();
            double base = Double.parseDouble(Float.toString(nativeTemperature));
            return new Properties(base, (Boolean) methods.precipitation().invoke(nativeBiome));
        } catch (ReflectiveOperationException | ClassCastException failure) {
            warn(failure);
            return null;
        }
    }

    private void warn(Exception failure) {
        if (warned.compareAndSet(false, true)) {
            logger.warning("Climate biome access unavailable; snow/ice edits are skipped (weather remains active): "
                    + failure.getClass().getSimpleName() + ": " + failure.getMessage());
        }
    }
}
