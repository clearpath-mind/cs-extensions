version = 3

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
}

dependencies {
    // jsoup 1.22.2 annotates nullable returns with jspecify; it is not
    // transitively on the compile classpath, so declare it explicitly.
    implementation("org.jspecify:jspecify:1.0.0")
}
