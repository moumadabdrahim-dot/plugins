dependencies {
    val cloudstream by configurations
    val compileOnly by configurations

    cloudstream("com.lagradost:cloudstream3:pre-release")
    compileOnly("org.jspecify:jspecify:1.0.0")

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}

version = 1

cloudstream {
    description = "أفلام ومسلسلات وأنمي OscarTV"
    authors = listOf("moumadabdrahim")
    language = "ar"
    status = 1
    tvTypes = listOf("Anime", "AnimeMovie", "Movie", "TvSeries")
}
