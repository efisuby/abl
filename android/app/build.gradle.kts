import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.FileInputStream
import java.util.Properties

// Public OAuth client id — embedded into the APK manifest anyway, so it is not a secret.
val yandexClientId = "4a94a7008ebc4ad2b5c0800dd28aed62"

// Signing material is kept out of version control; see keystore.properties.example.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        FileInputStream(keystorePropertiesFile).use { load(it) }
    }
}
val hasKeystore = keystoreProperties.isNotEmpty()

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.efisuby.abl"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.efisuby.abl"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "com.efisuby.abl.AblTestRunner"

        // Consumed by the Yandex ID SDK manifest.
        manifestPlaceholders["YANDEX_CLIENT_ID"] = yandexClientId
    }

    signingConfigs {
        create("release") {
            if (hasKeystore) {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            // Same key as release: keeps the OAuth fingerprint stable across build types.
            if (hasKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (hasKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    // MockContentResolver: serves a fixture documents provider to the SAF import tests.
    useLibrary("android.test.mock")

    lint {
        // Versions are pinned on purpose (Kotlin/KSP/Room/Media3 move together); upgrades are a decision, not a warning.
        disable += setOf("AndroidGradlePluginVersion", "GradleDependency", "NewerVersionAvailable")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        // JVM tests parse the same small audio fixtures the instrumented tests import.
        getByName("test").resources.srcDir("src/androidTest/assets")
        // MigrationTestHelper reads the exported Room schemas.
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }

    buildFeatures {
        viewBinding = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.google.material)

    // Audio playback (successor of the deprecated ExoPlayer 2.x line)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.session)
    // MetadataRetriever: reads tracks and embedded m4b chapters without starting playback
    implementation(libs.androidx.media3.inspector)

    // Yandex ID sign-in (returns an OAuth access token for the Disk REST API)
    implementation(libs.yandex.authsdk)

    // Yandex Disk REST client
    implementation(libs.okhttp)
    implementation(libs.androidx.work.runtime)

    // Local persistence
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)

    // book.json manifest and, later, the synchronization documents
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core.ktx)
    androidTestImplementation(libs.androidx.test.espresso.core)
}
