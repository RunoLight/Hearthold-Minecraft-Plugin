plugins {
    java
}

group = "dev.hearthold"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

val paperApiVersion = "26.3.build.248-stable"
val sqliteVersion = "3.54.1.0"

dependencies {
    compileOnly("io.papermc.paper:paper-api:$paperApiVersion")
    // Загружается сервером при старте через `libraries` в plugin.yml.
    compileOnly("org.xerial:sqlite-jdbc:$sqliteVersion")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
    options.compilerArgs.add("-Xlint:deprecation")
}

tasks.processResources {
    val props = mapOf("version" to project.version, "sqliteVersion" to sqliteVersion)
    inputs.properties(props)
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") { expand(props) }
}
