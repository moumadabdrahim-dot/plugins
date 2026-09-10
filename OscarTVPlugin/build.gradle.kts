dependencies {
    val cloudstream by configurations
    val compileOnly by configurations

    cloudstream("com.lagradost:cloudstream3:pre-release")
    compileOnly("org.jspecify:jspecify:1.0.0")
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.2")
}

version = 3

cloudstream {
    description = "أفلام ومسلسلات وأنمي OscarTV"
    authors = listOf("moumadabdrahim")
    language = "ar"
    status = 1
    tvTypes = listOf("Anime", "AnimeMovie", "Movie", "TvSeries")
}
