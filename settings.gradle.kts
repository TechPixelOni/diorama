pluginManagement {
	repositories {
		maven("https://maven.fabricmc.net/") { name = "Fabric" }
		maven("https://maven.neoforged.net/releases/") { name = "NeoForged" }
		mavenCentral()
		gradlePluginPortal()
	}
}

plugins {
	id("dev.kikugie.stonecutter") version "0.9.8"
	// NeoForge's build tools run on the Java its Minecraft version needs (21 for 1.21.1), so Gradle
	// downloads that JDK if it can't find one.
	id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// One source tree, one jar per Minecraft version or range of versions the same jar runs on (see
// minecraft_dep in versions/<version>/gradle.properties). Up to 1.21.11 the game ships obfuscated
// and needs remapping to Mojang's names; from 26.1 it ships with those names already. Versions
// named <minecraft>-neoforge build for NeoForge instead of Fabric; NeoForge runs on Mojang's names.
// Versions named <minecraft>-forge build for Forge, which before 1.20.6 runs on its own (SRG) names.
stonecutter {
	create(rootProject) {
		versions("1.20.1").buildscript("build.obfuscated.gradle.kts")
		vcsVersion = "1.20.1"
	}
}

rootProject.name = "diorama"
