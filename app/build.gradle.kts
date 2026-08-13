plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
}

apply(from = file("gradle/map-assets.gradle.kts"))
apply(from = rootProject.file("gradle/android-app-testing.gradle.kts"))

val glanceMapVersionName = providers.gradleProperty("glanceMapVersionName").get()
val glanceMapWearVersionCode = providers.gradleProperty("glanceMapWearVersionCode").get().toInt()
val gitSha =
    providers
        .exec {
            commandLine("git", "rev-parse", "--short=12", "HEAD")
        }.standardOutput
        .asText
        .map { it.trim().ifBlank { "unknown" } }
        .orElse("unknown")
val gitBranch =
    providers
        .exec {
            commandLine("git", "rev-parse", "--abbrev-ref", "HEAD")
        }.standardOutput
        .asText
        .map { it.trim().ifBlank { "unknown" } }
        .orElse("unknown")
val releaseStoreFile =
    providers
        .gradleProperty("android.injected.signing.store.file")
        .orElse(providers.gradleProperty("RELEASE_STORE_FILE"))
        .orElse(providers.environmentVariable("RELEASE_STORE_FILE"))
val releaseStorePassword =
    providers
        .gradleProperty("android.injected.signing.store.password")
        .orElse(providers.gradleProperty("RELEASE_STORE_PASSWORD"))
        .orElse(providers.environmentVariable("RELEASE_STORE_PASSWORD"))
val releaseKeyAlias =
    providers
        .gradleProperty("android.injected.signing.key.alias")
        .orElse(providers.gradleProperty("RELEASE_KEY_ALIAS"))
        .orElse(providers.environmentVariable("RELEASE_KEY_ALIAS"))
val releaseKeyPassword =
    providers
        .gradleProperty("android.injected.signing.key.password")
        .orElse(providers.gradleProperty("RELEASE_KEY_PASSWORD"))
        .orElse(providers.environmentVariable("RELEASE_KEY_PASSWORD"))
val hasReleaseSigning =
    releaseStoreFile.isPresent &&
        releaseStorePassword.isPresent &&
        releaseKeyAlias.isPresent &&
        releaseKeyPassword.isPresent
val projectTaskPrefix = "${project.path.lowercase()}:"
val releaseArtifactTaskRequested =
    gradle.startParameter.taskNames.any { taskName ->
        val normalized = taskName.lowercase()
        val targetsThisProject =
            normalized.startsWith(projectTaskPrefix) || !normalized.contains(":")
        targetsThisProject &&
            normalized.contains("release") &&
            listOf("bundle", "assemble", "publish").any(normalized::contains)
    }

if (releaseArtifactTaskRequested && !hasReleaseSigning) {
    throw GradleException(
        "Missing release signing properties. Release artifacts must not be debug-signed. " +
            "Use Android Studio's Generate Signed Bundle/APK flow, or define RELEASE_STORE_FILE, " +
            "RELEASE_STORE_PASSWORD, RELEASE_KEY_ALIAS, and RELEASE_KEY_PASSWORD in local/private " +
            "Gradle properties or environment variables.",
    )
}

android {
    namespace = "com.glancemap.glancemapwearos"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.glancemap.glancemapwearos"
        minSdk = 30
        targetSdk = 36
        versionCode = glanceMapWearVersionCode
        versionName = glanceMapVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
        buildConfigField("String", "GIT_SHA", "\"${gitSha.get()}\"")
        buildConfigField("String", "GIT_BRANCH", "\"${gitBranch.get()}\"")

        // 2MB channel buffer (tune if you measured better throughput)
        manifestPlaceholders["channelBufferSize"] = "2097152"
    }

    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
                storeFile = file(releaseStoreFile.get())
                storePassword = releaseStorePassword.get()
                keyAlias = releaseKeyAlias.get()
                keyPassword = releaseKeyPassword.get()
            }
        }
    }

    buildTypes {
        release {
            ndk {
                debugSymbolLevel = "SYMBOL_TABLE"
            }
            signingConfig =
                if (hasReleaseSigning) {
                    signingConfigs.getByName("release")
                } else {
                    signingConfigs.getByName("debug")
                }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }

        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            isDebuggable = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    bundle {
        language {
            enableSplit = true
        }
        density {
            enableSplit = true
        }
        abi {
            enableSplit = true
        }
    }

    lint {
        // Work around a lint internal crash in ActivityIconColorDetector on this project.
        disable += "ActivityIconColor"
        // Current transfer pipeline still relies on BIND_LISTENER-based listener service.
        disable += "WearableBindListener"
        // Temporarily skip release-gating lint because lintAnalyzeRelease stalls/crashes on this project.
        checkReleaseBuilds = false
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(platform(libs.compose.bom))

    // Compose / Wear
    implementation(libs.ui)
    implementation(libs.ui.graphics)
    implementation(libs.activity.compose)
    implementation(libs.wear.compose.foundation)
    implementation(libs.wear.compose.navigation)
    implementation(libs.wear.compose.material3)
    implementation(libs.ui.tooling.preview)
    debugImplementation(libs.ui.tooling)

    implementation(libs.core.splashscreen)
    implementation(libs.material.icons.extended)

    // ✅ Navigation Compose (REQUIRED for NavHost / composable / rememberNavController)
    implementation(libs.navigation.compose)
    implementation(libs.navigation.runtime.ktx)

    // AndroidX
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.wear.ongoing)
    implementation(libs.androidx.wear.ambient)

    // Play Services / Coroutines
    implementation(libs.play.services.wearable)
    implementation(libs.play.services.location)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)

    // Mapsforge / SVG
    implementation(libs.mapsforge.map)
    implementation(libs.mapsforge.map.android)
    implementation(libs.mapsforge.themes)
    implementation(libs.androidsvg)
    implementation(project(":brouter-core"))

    // Previews
    implementation(libs.androidx.wear.tooling.preview)

    // OkHttp (for HTTP transfers)
    implementation(libs.okhttp)

    // Horologist
    implementation(libs.horologist.compose.material)
    implementation(libs.horologist.compose.layout)

    implementation(project(":transfercontract"))
}
