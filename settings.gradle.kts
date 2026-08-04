// settings.gradle.kts

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "GlanceMap"
include(":app")
include(":macrobenchmark")
include(":companionmacrobenchmark")
include(":glancemapcompanionapp")
include(":transfercontract")
include(":trailcore")
include(":brouter-util")
project(":brouter-util").projectDir = file("third_party/brouter/brouter-util")
project(":brouter-util").buildFileName = "build.gradle.kts"
include(":brouter-codec")
project(":brouter-codec").projectDir = file("third_party/brouter/brouter-codec")
project(":brouter-codec").buildFileName = "build.gradle.kts"
include(":brouter-expressions")
project(":brouter-expressions").projectDir = file("third_party/brouter/brouter-expressions")
project(":brouter-expressions").buildFileName = "build.gradle.kts"
include(":brouter-mapaccess")
project(":brouter-mapaccess").projectDir = file("third_party/brouter/brouter-mapaccess")
project(":brouter-mapaccess").buildFileName = "build.gradle.kts"
include(":brouter-core")
project(":brouter-core").projectDir = file("third_party/brouter/brouter-core")
project(":brouter-core").buildFileName = "build.gradle.kts"
