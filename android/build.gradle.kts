plugins {
    id("com.android.application") version "8.13.2" apply false
    id("com.android.library") version "8.13.2" apply false
    kotlin("android") version "2.2.21" apply false
    kotlin("jvm") version "2.2.21" apply false
    kotlin("plugin.serialization") version "2.2.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21" apply false
}

val generateMediaFixtures by tasks.registering(Exec::class) {
    val output = layout.buildDirectory.dir("generated/androidTestAssets")
    inputs.file("scripts/generate-fixtures.sh")
    outputs.files(output.map { it.file("landscape-tone.mp4") }, output.map { it.file("portrait-silent.mp4") }, output.map { it.file("rotated-tone.mp4") }, output.map { it.file("sync-markers.mp4") }, output.map { it.file("sync-markers-long.mp4") })
    commandLine("bash", "scripts/generate-fixtures.sh", output.get().asFile.absolutePath)
}
