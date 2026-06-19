plugins {
    java
    id("com.gradleup.shadow") version "9.3.1"
}

group = "org.hyzionstudios"
version = "1.0.0"

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
    compileOnly("com.hypixel.hytale:Server:0.5.6")

    // VaultUnlocked
    compileOnly("net.cfh.vault:VaultUnlocked:2.18.3")

    // PlaceholderAPI
    compileOnly("at.helpch:placeholderapi-hytale:1.0.8")

    implementation("com.fasterxml.jackson.core:jackson-databind:2.20.1")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.20.1")
    implementation("org.xerial:sqlite-jdbc:3.51.1.0")

    testImplementation(platform("org.junit:junit-bom:6.1.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
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

tasks.test {
    useJUnitPlatform()
}

tasks.register<Copy>("deployMod") {
    group = "hytale"
    description = "Builds the mod and copies it to the project-local server mods folder."
    dependsOn(tasks.shadowJar)
    from(tasks.shadowJar.flatMap { it.archiveFile })
    into("$projectDir/.hytale-server/mods")
}

tasks.register("cleanDeploy") {
    group = "hytale"
    description = "Cleans, rebuilds, and deploys the mod."
    dependsOn("clean", "deployMod")
}

tasks.named("deployMod") {
    mustRunAfter("clean")
}
