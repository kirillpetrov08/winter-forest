import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    base
}

base {
    archivesName.set("app")
}

val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

val keystoreProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

fun resolveConfigProperty(propKey: String, envKey: String): String {
    val fromLocal = localProps.getProperty(propKey)?.trim().orEmpty()
    if (fromLocal.isNotEmpty()) return fromLocal
    return System.getenv(envKey)?.trim().orEmpty()
}

fun escapeForBuildConfig(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

// Рекламные ID РСЯ приложения «winter forest» (ID 20264124): боевые блоки из кабинета РСЯ
// зашиты как значения по умолчанию (local.properties / env могут переопределить).
// Баннер: R-M-20264124-1 • Реклама с вознаграждением: R-M-20264124-2 • демо-блоки НЕ используются.
val yandexBannerId = resolveConfigProperty("yandex.banner.id", "YANDEX_BANNER_AD_UNIT_ID")
    .ifEmpty { "R-M-20264124-1" }
val yandexRewardedId = resolveConfigProperty("yandex.rewarded.id", "YANDEX_REWARDED_AD_UNIT_ID")
    .ifEmpty { "R-M-20264124-2" }
val yandexDebugDemo = resolveConfigProperty("yandex.debug.demo", "YANDEX_DEBUG_DEMO")
    .equals("true", ignoreCase = true)

android {
    namespace = "com.ayaks.winterforest"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ayaks.winterforest"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Рекламные ID РСЯ читаются из local.properties или переменных окружения CI.
        // Пустая строка = реклама отключена, игра полностью работает.
        buildConfigField("String", "YANDEX_BANNER_AD_UNIT_ID", escapeForBuildConfig(yandexBannerId))
        buildConfigField("String", "YANDEX_REWARDED_AD_UNIT_ID", escapeForBuildConfig(yandexRewardedId))
    }

    signingConfigs {
        val storeFilePath = keystoreProps.getProperty("storeFile")?.trim()
            ?: System.getenv("KEYSTORE_PATH")?.trim()
        val storePasswordVal = keystoreProps.getProperty("storePassword")?.trim()
            ?: System.getenv("KEYSTORE_PASSWORD")?.trim()
        val keyAliasVal = keystoreProps.getProperty("keyAlias")?.trim()
            ?: System.getenv("KEY_ALIAS")?.trim()
        val keyPasswordVal = keystoreProps.getProperty("keyPassword")?.trim()
            ?: System.getenv("KEY_PASSWORD")?.trim()

        if (
            !storeFilePath.isNullOrEmpty() &&
            !storePasswordVal.isNullOrEmpty() &&
            !keyAliasVal.isNullOrEmpty() &&
            !keyPasswordVal.isNullOrEmpty() &&
            rootProject.file(storeFilePath).exists()
        ) {
            create("release") {
                storeFile = rootProject.file(storeFilePath)
                storePassword = storePasswordVal
                keyAlias = keyAliasVal
                keyPassword = keyPasswordVal
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            // debug-only: официальный демо-режим РСЯ включается только при yandex.debug.demo=true
            buildConfigField("Boolean", "YANDEX_DEBUG_DEMO_ADS", yandexDebugDemo.toString())
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // В release демо-блоки строго запрещены на уровне сборки и рантайма
            buildConfigField("Boolean", "YANDEX_DEBUG_DEMO_ADS", "false")
            signingConfigs.findByName("release")?.let { signingConfig = it }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // Рекламная сеть Яндекса (РСЯ) — единственная рекламная сеть проекта
    implementation(libs.yandex.mobileads)
    implementation(libs.yandex.mobileads.compose)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
}
