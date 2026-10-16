plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

val fremVersion = providers.gradleProperty("fremVersion").orElse("0.1").get()

require(Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)").matches(fremVersion)) {
    "Frem versions use <feature>.<patch>, for example 0.1"
}

val (featureVersion, patchVersion) = fremVersion.split(".").map { it.toLong() }

require(featureVersion <= 210_000 && patchVersion < 10_000) { "Version component out of range" }

val fremVersionCode = featureVersion * 10_000 + patchVersion

require(fremVersionCode in 1..2_100_000_000L) { "Android versionCode is out of range" }

val releaseKeystorePath = providers.environmentVariable("FREM_KEYSTORE_PATH").orNull

android {
    namespace = "dk.frem.app"
    compileSdk = 37
    defaultConfig {
        applicationId = "dk.frem.app"
        minSdk = 29
        targetSdk = 37
        versionCode = fremVersionCode.toInt()
        versionName = fremVersion
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    if (releaseKeystorePath != null) {
        signingConfigs.create("release") {
            storeFile = file(releaseKeystorePath)
            storePassword = providers.environmentVariable("FREM_KEYSTORE_PASSWORD").get()
            keyAlias = providers.environmentVariable("FREM_KEY_ALIAS").get()
            keyPassword = providers.environmentVariable("FREM_KEY_PASSWORD").get()
        }
    }
    buildTypes {
        getByName("release") {
            if (releaseKeystorePath != null) signingConfig = signingConfigs.getByName("release")
            buildConfigField("boolean", "FRAME_DIAGNOSTICS", "false")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        create("benchmark") {
            initWith(getByName("release"))
            isDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
            buildConfigField("boolean", "FRAME_DIAGNOSTICS", "true")
        }
        create("profile") {
            initWith(getByName("benchmark"))
            isMinifyEnabled = false
            isShrinkResources = false
        }
        create("optimized") {
            initWith(getByName("release"))
            isDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
        getByName("debug") { buildConfigField("boolean", "FRAME_DIAGNOSTICS", "false") }
    }
    sourceSets["androidTest"].assets.srcDir("schemas")
    sourceSets["profile"].manifest.srcFile("src/benchmark/AndroidManifest.xml")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.navigation3:navigation3-ui:1.2.0")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation("androidx.compose.material3:material3:1.5.0-beta01")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("androidx.work:work-runtime-ktx:2.11.1")
    implementation("androidx.health.connect:connect-client:1.2.0-alpha06")
    implementation("com.google.android.gms:play-services-fitness:21.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.09.00"))
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

tasks.register("importArtBaselineProfile") {
    description = "Import an unobfuscated on-device ART profile captured from the profile variant"
    doLast {
        val input = file(providers.gradleProperty("artProfile").get())
        val rules =
            input.readLines().filter { it.isNotBlank() && !it.startsWith("#") }.distinct().sorted()
        require(rules.any { it.contains("Ldk/frem/app/") }) {
            "Profile must come from an unobfuscated Frem build"
        }
        val output = file("src/main/baselineProfiles/baseline-prof.txt")
        output.parentFile.mkdirs()
        output.writeText(rules.joinToString("\n", postfix = "\n"))
        println("Imported ${rules.size} ART profile rules")
    }
}
