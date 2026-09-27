package dev.ceseasons.bootstrap;

import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginBootstrap;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;

/** Registers real server biomes before the dynamic registries are frozen. */
public final class SeasonsBootstrap implements PluginBootstrap {
    public static final String PACK_ID = "season_biomes";

    @Override
    public void bootstrap(BootstrapContext context) {
        // Resolve pack.mcmeta rather than a directory entry: jars need not contain directory entries.
        var version = dev.ceseasons.platform.MinecraftVersion.fromId(
                io.papermc.paper.ServerBuildInfo.buildInfo().minecraftVersionId());
        URL metadata = SeasonsBootstrap.class.getResource("/" + version.datapackRoot() + "/pack.mcmeta");
        if (metadata == null) {
            throw new IllegalStateException("CESeasons is missing the generated datapack for Minecraft "
                    + version.id() + "; run tools/generate_biomes.py before packaging");
        }
        context.getLifecycleManager().registerEventHandler(LifecycleEvents.DATAPACK_DISCOVERY, event -> {
            try {
                var root = new URL(metadata, ".").toURI();
                var discovered = event.registrar().discoverPack(root, PACK_ID,
                        options -> options.autoEnableOnServerStart(true));
                if (discovered == null) {
                    throw new IllegalStateException("CESeasons season biome datapack discovery returned null: " + root);
                }
            } catch (IOException | URISyntaxException failure) {
                throw new IllegalStateException("CESeasons cannot discover its mandatory season biome datapack", failure);
            }
        });
    }
}
