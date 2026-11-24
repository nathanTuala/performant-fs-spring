plugins {
    // Downloads the Java 25 toolchain on first build when the machine doesn't have it.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "server"
