import java.util.Properties
import org.gradle.api.GradleException

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use(::load)
    }
}

fun localOrEnv(localKey: String, envKey: String): String? {
    return localProperties.getProperty(localKey)?.takeIf { it.isNotBlank() }
        ?: providers.environmentVariable(envKey).orNull?.takeIf { it.isNotBlank() }
}

val releaseStoreFile = localOrEnv("habit.release.storeFile", "HABIT_RELEASE_STORE_FILE")
val releaseStorePassword = localOrEnv("habit.release.storePassword", "HABIT_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = localOrEnv("habit.release.keyAlias", "HABIT_RELEASE_KEY_ALIAS")
val releaseKeyPassword = localOrEnv("habit.release.keyPassword", "HABIT_RELEASE_KEY_PASSWORD")
val hasPrivateReleaseSigning = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { it != null }

android {
    namespace = "com.example.habittracker"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.habittracker"
        minSdk = 26
        targetSdk = 35
        versionCode = 53
        versionName = "1.0.52"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasPrivateReleaseSigning) {
            create("privateRelease") {
                storeFile = file(requireNotNull(releaseStoreFile))
                storePassword = requireNotNull(releaseStorePassword)
                keyAlias = requireNotNull(releaseKeyAlias)
                keyPassword = requireNotNull(releaseKeyPassword)
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("privateRelease") ?: signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    sourceSets {
        getByName("androidTest").assets.srcDir("src/test/resources/phase-import")
        getByName("test").resources.srcDir("../docs/redesign/demo")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

tasks.matching { it.name == "packageRelease" || it.name == "bundleRelease" }.configureEach {
    doFirst {
        if (!hasPrivateReleaseSigning) {
            throw GradleException(
                "Private release signing is required for release packaging. " +
                    "Configure habit.release.* in local.properties or HABIT_RELEASE_* environment variables.",
            )
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    ksp(libs.androidx.room.compiler)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
