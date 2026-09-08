dependencies {
    val cloudstream by configurations
    val compileOnly by configurations

    cloudstream("com.lagradost:cloudstream3:pre-release")
    compileOnly("org.jspecify:jspecify:1.0.0")
}
