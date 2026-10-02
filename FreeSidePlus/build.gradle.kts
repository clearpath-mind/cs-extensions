version = 10

android {
    namespace = "com.freesideplus"
}

cloudstream {
    authors = listOf("clearpath-mind")
    language = "en"

    status = 1

    tvTypes = listOf(
        "Movie"
    )

    iconUrl = "https://freesideplus.plus/wp-content/uploads/2026/06/FSP-LOGO.png"
}

dependencies {
    // jsoup 1.22.2 annotates nullable returns with jspecify; it is not
    // transitively on the compile classpath, so declare it explicitly.
    implementation("org.jspecify:jspecify:1.0.0")
}
