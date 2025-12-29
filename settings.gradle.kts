pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

rootProject.name = "LibreTube"

include(":app")
include(":baselineprofile")

includeBuild("../NewPipeExtractor") {
    dependencySubstitution {
        substitute(module("com.github.libre-tube:NewPipeExtractor"))
            .using(project(":extractor"))
    }
}