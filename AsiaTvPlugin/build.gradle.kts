// CloudStream dependency is provided by the TestPlugins template.

// jsoup exposes JSpecify nullability annotations in its public API.
// Keep JSpecify on the plugin compile classpath so Kotlin can resolve them.
dependencies {
    implementation("org.jspecify:jspecify:1.0.0")
}
