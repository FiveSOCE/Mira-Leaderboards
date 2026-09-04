import java.net.URI
import java.security.MessageDigest

plugins { java }

group = "com.mira"
version = "0.1.1"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
}

val miraCoreVersion = "0.2.0"
val miraCoreSha256 = "66433a266a76088d2a2de90ac1beb1a5a183c26891ee8f394827b47830195b03"
val miraCoreJar = layout.projectDirectory.file("libs/MiraCore-$miraCoreVersion.jar").asFile

val miraSeasonsVersion = "0.1.1"
val miraSeasonsSha256 = "b6531bde83163bc38ac755457e3306cf9c15709ea5c9c2047fc71b7a186941ca"
val miraSeasonsJar = layout.projectDirectory.file("libs/MiraSeasons-$miraSeasonsVersion.jar").asFile

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    return digest.digest(file.readBytes()).joinToString("") { byte -> "%02x".format(byte) }
}

fun downloadVerified(url: String, target: File, expectedSha256: String) {
    if (target.exists() && sha256(target) == expectedSha256) return
    target.parentFile.mkdirs()
    URI(url).toURL().openStream().use { input -> target.outputStream().use { output -> input.copyTo(output) } }
    check(sha256(target) == expectedSha256) { "Downloaded dependency failed SHA-256 verification: ${target.name}" }
}

val downloadMiraDependencies by tasks.registering {
    doLast {
        downloadVerified(
            "https://github.com/FiveSOCE/MIra-core/releases/download/v$miraCoreVersion/MiraCore-$miraCoreVersion.jar",
            miraCoreJar,
            miraCoreSha256
        )
        downloadVerified(
            "https://github.com/FiveSOCE/Mira-Seasons/releases/download/v$miraSeasonsVersion/MiraSeasons-$miraSeasonsVersion.jar",
            miraSeasonsJar,
            miraSeasonsSha256
        )
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("me.clip:placeholderapi:2.11.6")
    compileOnly(files(miraCoreJar))
    compileOnly(files(miraSeasonsJar))
}

java { toolchain.languageVersion.set(JavaLanguageVersion.of(21)) }

tasks.withType<JavaCompile>().configureEach {
    dependsOn(downloadMiraDependencies)
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.jar { archiveFileName.set("MiraLeaderboards-${project.version}.jar") }

tasks.processResources {
    filesMatching("plugin.yml") { expand("version" to project.version) }
}
