plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

import java.util.Properties

val enableMetaDat = providers.gradleProperty("enableMetaDat").orNull?.toBooleanStrictOrNull() == true
val allowStandardReleaseForDevelopment = providers.gradleProperty("allowStandardReleaseForDevelopment")
    .orNull?.toBooleanStrictOrNull() == true
val hermesAllowDebugSigning = providers.gradleProperty("hermesAllowDebugSigning")
    .orNull?.toBooleanStrictOrNull() == true
val mwdatApplicationId = providers.gradleProperty("mwdatApplicationId").orNull ?: "0"
val mwdatClientToken = providers.gradleProperty("mwdatClientToken").orNull ?: "0"

// Keystore release dedicato (mai il debug keystore condiviso): materiale in
// local.properties (gitignored). Risoluzione pigra: niente throw in
// configurazione per task debug/test.
val hermesKeystoreProps = Properties().apply {
    val localFile = rootProject.file("local.properties")
    if (localFile.exists()) localFile.inputStream().use { load(it) }
}
fun hermesSigningAvailable(): Boolean {
    val storeName = hermesKeystoreProps.getProperty("hermes.storeFile", "hermes-release.keystore")
    val store = rootProject.file(storeName)
    return store.exists() &&
        !hermesKeystoreProps.getProperty("hermes.storePassword").isNullOrBlank() &&
        !hermesKeystoreProps.getProperty("hermes.keyAlias").isNullOrBlank() &&
        !hermesKeystoreProps.getProperty("hermes.keyPassword").isNullOrBlank()
}

gradle.taskGraph.whenReady {
    val containsReleaseOutput = allTasks.any { task ->
        task.project == project && task.name.endsWith("Release", ignoreCase = true)
    }
    if (containsReleaseOutput && !enableMetaDat && !allowStandardReleaseForDevelopment) {
        throw GradleException(
            "La release ufficiale Hermes Hub richiede Meta DAT. " +
                "Usa scripts/package-android-release.ps1; per una build standard solo sviluppo passa " +
                "-PallowStandardReleaseForDevelopment=true. L'APK standard non deve essere pubblicato."
        )
    }
    // Fail fast: release firmata senza keystore dedicato, mai fallback
    // silenzioso al debug (solo CI validation-only con flag esplicito).
    if (containsReleaseOutput && !allowStandardReleaseForDevelopment && !hermesAllowDebugSigning && !hermesSigningAvailable()) {
        throw GradleException(
            "Keystore release Hermes Hub assente: configura hermes.storeFile/storePassword/keyAlias/keyPassword " +
                "in src/NemoclawChat.Android/local.properties (gitignored). Mai firmare release col debug keystore."
        )
    }
}

android {
    namespace = "com.nemoclaw.chat"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.nemoclaw.chat"
        minSdk = if (enableMetaDat) 29 else 26
        targetSdk = 36
        versionCode = 288
        versionName = "0.6.284"
        resConfigs("en", "it")
        buildConfigField("boolean", "META_DAT_ENABLED", enableMetaDat.toString())
        // Nessuna chiave API nel BuildConfig: la release APK non contiene
        // segreti (audit 0.6.276). Il pairing avviene dall'app (KeyStore).
        manifestPlaceholders["mwdat_application_id"] = mwdatApplicationId
        manifestPlaceholders["mwdat_client_token"] = mwdatClientToken
    }

    signingConfigs {
        // Configurazione pigra: il materiale manca in CI/debug, ma allora la
        // config non viene mai usata (whenReady blocca prima le release).
        create("hermes") {
            val storeName = hermesKeystoreProps.getProperty("hermes.storeFile", "hermes-release.keystore")
            val store = rootProject.file(storeName)
            if (store.exists()) {
                storeFile = store
                storePassword = hermesKeystoreProps.getProperty("hermes.storePassword")
                keyAlias = hermesKeystoreProps.getProperty("hermes.keyAlias")
                keyPassword = hermesKeystoreProps.getProperty("hermes.keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // Mai debug di default: solo sviluppo/CI espliciti usano debug.
            signingConfig = if (allowStandardReleaseForDevelopment || hermesAllowDebugSigning) {
                signingConfigs.getByName("debug")
            } else {
                signingConfigs.getByName("hermes")
            }
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

    if (enableMetaDat) {
        val metaDatSources = layout.projectDirectory.dir("src/metaDat/java").asFile.absolutePath
        sourceSets.getByName("main").java.directories.add(metaDatSources)
        sourceSets.getByName("main").kotlin.directories.add(metaDatSources)
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        warningsAsErrors = true
        abortOnError = true
        disable += listOf("GradleDependency", "MissingTranslation", "NewerVersionAvailable", "OldTargetApi", "AndroidGradlePluginVersion", "ObsoleteSdkInt")
        if (enableMetaDat) {
            // DAT requires minSdk 29. Shared sources intentionally retain API 26-28
            // branches because the standard Hermes Hub artifact still supports minSdk 26.
            // ObsoleteSdkInt resta disabilitato anche nello standard: mipmap-anydpi-v26
            // (adaptive icons) e i rami 26-28 sono intenzionali, non obsoleti.
            disable += "ObsoleteSdkInt"
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.04.01"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.navigation:navigation-compose:2.9.8")
    implementation("androidx.work:work-runtime-ktx:2.11.0")
    implementation("androidx.health.connect:connect-client:1.1.0")
    implementation("androidx.media3:media3-exoplayer:1.10.1")
    implementation("androidx.media3:media3-ui:1.10.1")
    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("com.squareup.okhttp3:logging-interceptor:5.3.2")
    if (enableMetaDat) {
        implementation("com.meta.wearable:mwdat-core:0.8.0")
        implementation("com.meta.wearable:mwdat-camera:0.8.0")
        implementation("com.meta.wearable:mwdat-mockdevice:0.8.0")
    }
}
