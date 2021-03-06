import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet

plugins {
    kotlin("jvm")
    id("org.jetbrains.intellij")
}

val buildMatrix = mapOf(
    "AS33" to ij.BuildConfig(
        "182.5107.16",
        "Studio3.3",
        "AS33",
        ij.VersionRange("182.5107", "182.5107.*"),
        arrayOf("org.jetbrains.kotlin:1.3.30-release-Studio3.3-1")
    ),
    "AS34" to ij.BuildConfig(
        "183.6156.11",
        "Studio3.4",
        "AS34",
        ij.VersionRange("183.6156", "183.6156.*"),
        arrayOf("org.jetbrains.kotlin:1.3.30-release-Studio3.4-1")
    ),
    "AS35" to ij.BuildConfig(
        "191.8026.42",
        "Studio3.5",
        "AS34",
        ij.VersionRange("191.8026", "191.8026.*"),
        arrayOf("org.jetbrains.kotlin:1.3.30-release-Studio3.5-1")
    ),
    "AS36" to ij.BuildConfig(
        "192.7142.36",
        "Studio3.6",
        "AS34",
        ij.VersionRange("192.7142.36", "192.7142.36.*"),
        arrayOf("android", "java", "org.jetbrains.kotlin:1.3.61-release-Studio3.6-1")
    )
)

val sdkVersion = project.properties["as.version"] ?: "AS36"
val settings = checkNotNull(buildMatrix[sdkVersion])

intellij {
    pluginName = "Spek Framework"
    val plugins = arrayOf("gradle", "android") + settings.deps
    setPlugins(*plugins)
    version = settings.sdk
}

sourceSets {
    main {
        withConvention(KotlinSourceSet::class) {
            kotlin.srcDirs("src/${settings.extraSource}/kotlin")
        }
    }
}

dependencies {
    compile(project(":spek-ide-plugin-intellij-base-jvm"))
    compileOnly(kotlin("stdlib"))
}

tasks {
    compileKotlin {
        kotlinOptions {
            jvmTarget = "1.8"
        }
    }

    buildPlugin {
        archiveBaseName.value("")
        archiveVersion.value("${project.version}-${settings.prefix}")
    }

    patchPluginXml {
        setVersion("${project.version}-${settings.prefix}")
        setSinceBuild(settings.version.since)
        setUntilBuild(settings.version.until)
    }
}

apply {
    plugin("publish-plugin")
}
