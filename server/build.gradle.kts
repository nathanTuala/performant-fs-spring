import java.security.MessageDigest

plugins {
    java
    id("org.springframework.boot") version "4.2.0"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.performantfs"
version = "0.0.1-SNAPSHOT"
description = "Performant file system API"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.flywaydb:flyway-database-postgresql")
    // Reads the text out of PDFs for content search. Text and code files are read directly.
    implementation("org.apache.pdfbox:pdfbox:3.0.8")
    runtimeOnly("org.postgresql:postgresql")
    testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
    testImplementation("org.springframework.boot:spring-boot-starter-jdbc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// The embedding model for semantic search (all-MiniLM-L6-v2, Apache 2.0). Downloaded once and
// checked against a pinned SHA-256, not committed: it is 90 MB.
val modelDir = layout.projectDirectory.dir("models/all-MiniLM-L6-v2")
val modelFiles = mapOf(
    "model.onnx" to ("https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/main/onnx/model.onnx"
        to "6fd5d72fe4589f189f8ebc006442dbb529bb7ce38f8082112682524616046452"),
    "vocab.txt" to ("https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/main/vocab.txt"
        to "07eced375cec144d27c900241f3e339478dec958f92fddbc551f295c992038a3"),
)

val downloadModel = tasks.register("downloadModel") {
    description = "Downloads the semantic search embedding model into models/ (once)."
    group = "build setup"
    outputs.dir(modelDir)
    outputs.upToDateWhen { modelFiles.all { (name, src) -> sha256(modelDir.file(name).asFile) == src.second } }
    doLast {
        modelDir.asFile.mkdirs()
        for ((name, src) in modelFiles) {
            val (url, expected) = src
            val target = modelDir.file(name).asFile
            if (sha256(target) == expected) continue
            logger.lifecycle("Downloading $name")
            val partial = File(target.path + ".part")
            uri(url).toURL().openStream().use { input -> partial.outputStream().use { input.copyTo(it) } }
            val actual = sha256(partial)
            if (actual != expected) {
                partial.delete()
                throw GradleException("$name: expected sha256 $expected, got $actual")
            }
            partial.renameTo(target)
        }
    }
}

fun sha256(file: File): String? {
    if (!file.isFile) return null
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

// Tests and the dev server load the model.
tasks.withType<Test> { dependsOn(downloadModel) }
tasks.named("bootRun") { dependsOn(downloadModel) }

// One runnable jar with a fixed name, for the Docker image.
tasks.bootJar {
    archiveFileName = "app.jar"
}

tasks.jar {
    enabled = false
}
