plugins {
    java
}

group = "dev.ceseasons"
version = "0.1.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.momirealms.net/releases/")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
    withSourcesJar()
}

val paperVersion = providers.gradleProperty("paperVersion").getOrElse("26.1.2.build.74-stable")
val craftEngineVersion = "26.9.1"

dependencies {
    compileOnly("io.papermc.paper:paper-api:$paperVersion")
    compileOnly("net.momirealms:craft-engine-core:$craftEngineVersion")
    compileOnly("net.momirealms:craft-engine-bukkit:$craftEngineVersion")
    compileOnly("net.momirealms:craft-engine-bukkit-proxy:$craftEngineVersion")
    compileOnly("net.momirealms:antigrieflib:1.0.17")
    compileOnly("io.netty:netty-transport:4.1.137.Final")
    compileOnly("io.netty:netty-buffer:4.1.137.Final")
    testImplementation("io.netty:netty-transport:4.1.137.Final")
    testImplementation("io.netty:netty-buffer:4.1.137.Final")
    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("io.papermc.paper:paper-api:$paperVersion")
    testImplementation("net.momirealms:craft-engine-core:$craftEngineVersion")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.test {
    useJUnitPlatform()
}

tasks.processResources {
    filesMatching("paper-plugin.yml") { expand("version" to project.version) }
}

tasks.jar {
    manifest.attributes(
        "Implementation-Title" to "CraftEngineSeasons",
        "Implementation-Version" to project.version,
        "Minecraft-Version" to "26.1.2,26.2,26.3",
        "CraftEngine-Version" to craftEngineVersion
    )
}

// Run platform-independent regression tests while server adapters are being compiled.
val coreCheck = sourceSets.create("coreCheck") {
    java.srcDirs("src/main/java", "src/test/java")
    java.include("dev/ceseasons/season/**", "dev/ceseasons/storage/**")
}
configurations[coreCheck.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[coreCheck.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())
val coreTest = tasks.register<Test>("coreTest") {
    testClassesDirs = coreCheck.output.classesDirs
    classpath = coreCheck.runtimeClasspath
    useJUnitPlatform()
}

val contentPack = tasks.register<Zip>("contentPack") {
    archiveBaseName.set("ce-seasons-content")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    from("content-pack/ce_seasons") { into("ce_seasons") }
}

tasks.assemble { dependsOn(contentPack) }
