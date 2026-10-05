plugins {
    java
    id("com.gradleup.shadow") version "9.3.1"
}

group = "org.hyzionstudios"
version = "2.0.0"

repositories {
    mavenCentral()
    maven ( url = "https://maven.hytale.com/release")
    maven ( url = "https://maven.hytale.com/pre-release")
    maven ( url = "https://repo.codemc.io/repository/creatorfromhell/")
    maven ( url = "https://repo.helpch.at/releases/")
}

val hytaleInstallPath: String by project
val hytaleServerJarPath: String by project

val resolvedServerJar = hytaleServerJarPath.ifBlank { "$hytaleInstallPath/Server/HytaleServer.jar" }

dependencies {
    // Hytale Server API from official Maven repository
    compileOnly("com.hypixel.hytale:Server:0.6.8")

    // VaultUnlocked
    compileOnly("net.cfh.vault:VaultUnlocked:2.18.3")

    // PlaceholderAPI
    compileOnly("at.helpch:placeholderapi-hytale:1.0.8")

    // MysticIdentity: the player-portal provider (integration/mysticidentity) compiles against its
    // public API. The mod is optional at runtime and never bundled; MysticIdentityPortal probes for
    // it by name before any class naming the API is allowed to load. Same setup as MysticEconomy.
    compileOnly(files("../MysticIdentity/mysticidentity-api/build/libs/mysticidentity-api-0.1.0.jar"))
    testImplementation(files("../MysticIdentity/mysticidentity-api/build/libs/mysticidentity-api-0.1.0.jar"))

    implementation("com.fasterxml.jackson.core:jackson-databind:2.20.1")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.20.1")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.20.1")
    implementation("org.xerial:sqlite-jdbc:3.51.1.0")

    testImplementation(platform("org.junit:junit-bom:6.1.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // compileOnly deps are not inherited by the test source set, so tests covering anything whose
    // signature mentions a Hytale type (loggers, refs, packets) cannot compile without this.
    testImplementation("com.hypixel.hytale:Server:0.6.8")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

tasks.shadowJar {
    archiveClassifier.set("")
    mergeServiceFiles()
}

// Probe builds for the "Could not find document … for Custom UI Append command" disconnect. That
// failure takes down every mod's HUD on the server, not just ours: with this pack shipping no UI at
// all, players join fine and other mods' HUDs work, so something in Common/UI here stops the client
// registering custom UI documents. These flags narrow down what:
//
//   ./gradlew deployMod -PskipUiPack        // no UI at all — the known-good baseline
//   ./gradlew deployMod -PskipUiMarkup      // textures only, no .ui documents
//   ./gradlew deployMod -PskipUiAssets      // .ui documents only, no textures
//   ./gradlew deployMod -PskipUiDocuments=QuestAdminPage.ui,JournalPage.ui
//
// The HUD disables itself when its document is not in the JAR, so no probe can disconnect anyone.
val skippedUiDocuments = providers.gradleProperty("skipUiDocuments")
    .map { it.split(",").map(String::trim).filter(String::isNotEmpty) }
    .getOrElse(emptyList())
val uiProbe: Pair<List<String>, String>? = when {
    providers.gradleProperty("skipUiPack").isPresent ->
        listOf("Common/UI/**") to "no Common/UI content at all"
    providers.gradleProperty("skipUiMarkup").isPresent ->
        listOf("Common/UI/**/*.ui") to "textures only, no .ui documents"
    providers.gradleProperty("skipUiAssets").isPresent ->
        listOf("Common/UI/Custom/mysticquests/Assets/**") to "documents only, no textures"
    skippedUiDocuments.isNotEmpty() ->
        skippedUiDocuments.map { "**/$it" } to "omitting $skippedUiDocuments"
    else -> null
}

tasks.processResources {
    uiProbe?.let { (patterns, description) ->
        patterns.forEach { exclude(it) }
        doFirst { logger.lifecycle("Probe build: $description.") }
    }
}

tasks.test {
    useJUnitPlatform()
}

// The plugin manifest carries its own Version, which servers and the mod list show. It must match
// the build version, or a release would announce one version and contain another.
val verifyManifestVersion by tasks.registering {
    group = "verification"
    description = "Fails when src/main/resources/manifest.json's Version differs from the project version."
    val manifest = layout.projectDirectory.file("src/main/resources/manifest.json")
    val expected = project.version.toString()
    inputs.file(manifest)
    doLast {
        val declared = Regex("\"Version\"\\s*:\\s*\"([^\"]+)\"").find(manifest.asFile.readText())?.groupValues?.get(1)
        check(declared == expected) { "manifest.json declares Version $declared but the build is $expected; update one of them." }
    }
}
tasks.processResources { dependsOn(verifyManifestVersion) }

// A release: the tested plugin jar with the guides and example packages server owners need, in
// build/release/MysticQuests-<version>.zip. The jar bundles its libraries (Jackson, SQLite) and
// never the optional Mystic mods' APIs, which are compileOnly.
tasks.register<Zip>("releaseBundle") {
    group = "hytale"
    description = "Tests, builds and packages a release zip with the jar, docs and example packages."
    dependsOn(tasks.test)
    val bundle = "MysticQuests-${project.version}"
    archiveFileName.set("$bundle.zip")
    destinationDirectory.set(layout.buildDirectory.dir("release"))
    into(bundle) {
        from(tasks.shadowJar.flatMap { it.archiveFile })
        from("README.md", "LICENSE")
        into("docs") {
            from("docs")
        }
        into("examples") {
            from("examples")
        }
    }
}

tasks.register<Copy>("deployMod") {
    group = "hytale"
    description = "Builds the mod and copies it to the project-local server mods folder."
    dependsOn(tasks.shadowJar)
    from(tasks.shadowJar.flatMap { it.archiveFile })
    into("$projectDir/.hytale-server/mods")
}

// Runs the web Studio over a copy of examples/packages (build/studio-dev) for work on its pages,
// without a game server. Prints a sign-in link.
tasks.register<JavaExec>("studioDev") {
    group = "hytale"
    description = "Runs the web Studio on http://127.0.0.1:8766/ over a copy of the example packages."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("org.hyzionstudios.mysticquests.studio.StudioDevServer")
    workingDir = projectDir
}

tasks.register("cleanDeploy") {
    group = "hytale"
    description = "Cleans, rebuilds, and deploys the mod."
    dependsOn("clean", "deployMod")
}

tasks.named("deployMod") {
    mustRunAfter("clean")
    // shadowJar publishes with an empty classifier, so it writes the same path as the plain jar
    // task. Without this ordering, `gradlew build deployMod` fails validation because deployMod
    // reads a file jar also produces.
    mustRunAfter("jar")
}
