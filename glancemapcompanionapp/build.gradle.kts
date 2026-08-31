plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
}

apply(from = rootProject.file("gradle/android-app-testing.gradle.kts"))

val glanceMapVersionName = providers.gradleProperty("glanceMapVersionName").get()
val glanceMapPhoneVersionCode = providers.gradleProperty("glanceMapPhoneVersionCode").get().toInt()
val arkluzSmsApiKeyProperty = "ARKLUZ_SMS_API_KEY"
val projectGradlePropertiesDefinesArkluzSmsApiKey =
    rootProject.file("gradle.properties").useLines { lines ->
        lines.any { line ->
            val trimmedLine = line.trimStart()
            !trimmedLine.startsWith("#") &&
                '=' in trimmedLine &&
                trimmedLine.substringBefore('=').trim() == arkluzSmsApiKeyProperty
        }
    }
if (projectGradlePropertiesDefinesArkluzSmsApiKey) {
    throw GradleException(
        "$arkluzSmsApiKeyProperty must not be defined in the repository gradle.properties. " +
            "Use ~/.gradle/gradle.properties or an environment variable.",
    )
}
val arkluzSmsApiKey =
    providers
        .gradleProperty(arkluzSmsApiKeyProperty)
        .orElse(providers.environmentVariable(arkluzSmsApiKeyProperty))
        .orNull
        .orEmpty()
val hasArkluzSmsApiKey = arkluzSmsApiKey.isNotEmpty()
val hasValidArkluzSmsApiKey =
    arkluzSmsApiKey.length in 32..128 &&
        arkluzSmsApiKey.matches(Regex("^[A-Za-z0-9+/]+={0,2}$"))
if (hasArkluzSmsApiKey && !hasValidArkluzSmsApiKey) {
    throw GradleException(
        "$arkluzSmsApiKeyProperty must be a 32-128 character Base64 value.",
    )
}
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

if (releaseArtifactTaskRequested && !hasArkluzSmsApiKey) {
    throw GradleException(
        "Missing $arkluzSmsApiKeyProperty. Release artifacts must include the Arkluz SMS API key. " +
            "Define it in ~/.gradle/gradle.properties or as an environment variable.",
    )
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
    namespace = "com.glancemap.glancemapcompanionapp"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.glancemap.glancemapwearos"
        minSdk = 26
        targetSdk = 36
        versionCode = glanceMapPhoneVersionCode
        versionName = glanceMapVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        manifestPlaceholders["channelBufferSize"] = "8388608" // 8MB buffer
        buildConfigField("String", "ARKLUZ_TRACKING_URL", "\"https://arkluz.com/trk\"")
        buildConfigField("String", "ARKLUZ_SMS_API_KEY", "\"$arkluzSmsApiKey\"")
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

    buildFeatures {
        buildConfig = true
        compose = true
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
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    sourceSets {
        getByName("main") {
            assets {
                directories.add(rootProject.file("licenses").absolutePath)
                directories.add(rootProject.file("shared-theme-assets").absolutePath)
                // Reuse the watch app's bundled Mapsforge theme families and resources.
                directories.add(rootProject.file("app/src/main/assets/theme").absolutePath)
                directories.add(rootProject.file("app/src/main/assets/poi").absolutePath)
                // Reuse the watch app's BRouter profiles without maintaining a second copy.
                directories.add(rootProject.file("app/src/main/assets/brouter").absolutePath)
            }
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.activity.compose)
    implementation(libs.ui)
    implementation(libs.ui.graphics)
    implementation(libs.ui.tooling.preview)
    debugImplementation(libs.ui.tooling)
    implementation(libs.compose.material3)
    implementation(libs.material.icons.extended)
    implementation(libs.lifecycle.viewmodel.compose)

    // AndroidX & Coroutines
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.window)
    implementation(libs.play.services.wearable)
    implementation(libs.play.services.location)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.androidx.lifecycle.service)

    // Explicit coroutine support
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)

    // Ktor Server (for HTTP transfers)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.partial.content)

    // Gson for JSON serialization
    implementation(libs.gson)

    // Native map picker
    implementation(libs.maplibre.android)
    implementation(libs.okhttp)
    implementation(libs.mapsforge.map)
    implementation(libs.mapsforge.map.android)
    implementation(libs.mapsforge.themes)
    implementation(libs.androidsvg)

    implementation(project(":transfercontract"))
    implementation(project(":trailcore"))
    implementation(project(":brouter-util"))
}
