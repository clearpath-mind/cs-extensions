@file:Suppress("UnstableApiUsage")

import org.jetbrains.kotlin.konan.properties.Properties

version = 26

android {
    namespace = "com.streamly"
    buildFeatures {
        buildConfig = true
    }
    defaultConfig {
        val properties = Properties()
        val localProps = project.rootProject.file("local.properties")
        if (localProps.exists()) {
            properties.load(localProps.inputStream())
        }
        buildConfigField("String", "TMDB_API", "\"${properties.getProperty("TMDB_API") ?: ""}\"")
    }
}

cloudstream {
    language = "ar"
    description = "Streamly - Watch movies and series with Arabic subtitles"
    authors = listOf("clearpath-mind")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1

    tvTypes = listOf(
        "Movie",
        "TvSeries"
    )

    isCrossPlatform = false

    // Bundles the settings layout/drawables so plugin.resources is non-null.
    requiresResources = true
}

dependencies {
    val cloudstream by configurations
    cloudstream("com.lagradost:cloudstream3:pre-release")

    // AppCompatActivity (provider settings host) + Fragment (DialogFragment),
    // pulled in transitively the same way StreamPlay gets them.
    implementation("androidx.appcompat:appcompat:1.7.1")
}
