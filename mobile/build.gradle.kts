import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Optional local-only input. CI and ordinary source builds contain no accessory identity.
val localAuthenticationAssets = providers.environmentVariable("OSNPLAY_AUTH_ASSETS_DIR")
    .orNull?.let { file(it).canonicalFile }
val osnTestBuild = providers.gradleProperty("osnplayOsnTest").isPresent
val osnPlay = providers.gradleProperty("osnPlay").isPresent
val osnReleaseConfig = providers.fileContents(layout.projectDirectory.file("osnplay-release.properties")).asText.map { text ->
    Properties().apply { load(text.reader()) }
}
val osnVersionName = providers.gradleProperty("osnVersionName")
    .orElse(osnReleaseConfig.map { it.getProperty("versionName") }).get()
val osnVersionCode = providers.gradleProperty("osnVersionCode")
    .orElse(osnReleaseConfig.map { it.getProperty("versionCode") }).get().toInt()
require(osnVersionCode > 0) { "OsnPlay versionCode must be positive" }
require(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,70}").matches(osnVersionName)) { "Invalid OsnPlay versionName" }

android {
    namespace = "com.shilapi.xcertplay"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = if (osnPlay) "com.sinyee.babybus.story" else "com.shihab.osnplay"
        minSdk = 28
        targetSdk = if (osnPlay) 28 else 37
        versionCode = if (osnPlay) osnVersionCode else 30
        versionName = if (osnPlay) osnVersionName else "0.2.11"

    }


    localAuthenticationAssets?.let { sourceSets.getByName("main").assets.srcDir(it) }
    if (osnPlay) {
        sourceSets.getByName("release").apply {
            res.srcDir("src/osnplay/res")
            java.srcDir("src/osnplay/java")
            kotlin.srcDir("src/osnplay/java")
            manifest.srcFile("src/osnplay/AndroidManifest.xml")
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file(
                providers.environmentVariable("ANDROID_KEYSTORE_PATH")
                    .getOrElse("missing-release-keystore.jks"),
            )
            storePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").getOrElse("")
            keyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").getOrElse("")
            keyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").getOrElse("")
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = if (osnTestBuild) ".osntest" else ".hudtest"
            versionNameSuffix = if (osnTestBuild) "-osn-test1" else "-hud-test"
            manifestPlaceholders["testAppLabel"] = if (osnTestBuild) "OsnPlay OSN Test" else "OsnPlay HUD Test"
        }
        release {
            optimization {
                enable = false
            }
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(project(":common"))
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.app.projected)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

// No implicit import. Only the two explicitly selected local runtime assets are allowed.
val credentialAssets = files(android.sourceSets.flatMap { source ->
    source.assets.directories.map { directory ->
        fileTree(directory) {
            include("**/offline-mfi/**", "**/*.pk8", "**/*.p7b", "**/*.key",
                "**/*.pem", "**/*.p12", "**/*.pfx", "**/*.jks", "**/*.keystore")
        }
    }
})
val rejectBundledCredentials by tasks.registering {
    group = "verification"
    description = "Reject unexpected credential files in APK assets."
    val filesToCheck = credentialAssets
    val allowed = localAuthenticationAssets?.let { dir ->
        listOf("identity.pk8", "certificate.p7b").map { dir.resolve("offline-mfi/$it").canonicalFile }.toSet()
    } ?: emptySet()
    inputs.files(filesToCheck)
    doLast {
        check(allowed.all { it.isFile }) { "Explicit local authentication assets are incomplete" }
        val unexpected = filesToCheck.files.filter { it.canonicalFile !in allowed }
        check(unexpected.isEmpty()) { "Unexpected credential files in APK assets" }
    }
}
tasks.named("preBuild") { dependsOn(rejectBundledCredentials) }

// Car-test packages must be standalone. Keep ordinary source/CI builds identity-free.
val verifyStandaloneAuthentication by tasks.registering {
    group = "verification"
    description = "Require the explicit runtime authentication input for a standalone car-test APK."
    val directory = localAuthenticationAssets
    doLast {
        check(directory != null) {
            "Standalone car builds require OSNPLAY_AUTH_ASSETS_DIR; assembleDebug alone is source-only."
        }
        check(listOf("identity.pk8", "certificate.p7b").all {
            directory.resolve("offline-mfi/$it").let { file -> file.isFile && file.length() > 0 }
        }) { "Standalone CarPlay authentication files are missing or empty" }
    }
}
tasks.named("preBuild") { mustRunAfter(verifyStandaloneAuthentication) }
tasks.register("assembleStandaloneDebug") {
    group = "build"
    description = "Build a standalone car-test APK with explicitly provisioned authentication."
    dependsOn(verifyStandaloneAuthentication, "assembleDebug")
}
tasks.register("assembleStandaloneRelease") {
    group = "build"
    description = "Build a signed release with explicitly provisioned runtime authentication."
    dependsOn(verifyStandaloneAuthentication, "assembleRelease")
}
