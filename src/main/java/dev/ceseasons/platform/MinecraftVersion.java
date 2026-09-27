package dev.ceseasons.platform;

/** Exact server versions whose registries and packet layouts are supported. */
public enum MinecraftVersion {
    V26_1_2("26.1.2", "season_datapack_26_1_2"),
    V26_2("26.2", "season_datapack_26_2"),
    V26_3("26.3", "season_datapack");

    private final String id;
    private final String datapackRoot;

    MinecraftVersion(String id, String datapackRoot) {
        this.id = id;
        this.datapackRoot = datapackRoot;
    }

    public String id() { return id; }
    public String datapackRoot() { return datapackRoot; }

    public static MinecraftVersion fromId(String id) {
        for (MinecraftVersion version : values()) {
            if (version.id.equals(id)) return version;
        }
        throw new IllegalStateException("Supported Minecraft versions: 26.1.2, 26.2, 26.3; found " + id);
    }
}
