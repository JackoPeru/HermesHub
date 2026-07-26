plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val enableMetaDat = providers.gradleProperty("enableMetaDat").orNull?.toBooleanStrictOrNull() == true
val allowStandardReleaseForDevelopment = providers.gradleProperty("allowStandardReleaseForDevelopment")
    .orNull?.toBooleanStrictOrNull() == true
val mwdatApplicationId = providers.gradleProperty("mwdatApplicationId").orNull ?: "0"
val mwdatClientToken = providers.gradleProperty("mwdatClientToken").orNull ?: "0"

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
}

android {
    namespace = "com.nemoclaw.chat"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.nemoclaw.chat"
        minSdk = if (enableMetaDat) 29 else 26
        targetSdk = 36
        versionCode = 179
        versionName = "0.6.175"
        buildConfigField("boolean", "META_DAT_ENABLED", enableMetaDat.toString())
        manifestPlaceholders["mwdat_application_id"] = mwdatApplicationId
        manifestPlaceholders["mwdat_client_token"] = mwdatClientToken
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
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
        disable += listOf("GradleDependency", "MissingTranslation", "NewerVersionAvailable", "OldTargetApi")
        if (enableMetaDat) {
            // DAT requires minSdk 29. Shared sources intentionally retain API 26-28
            // branches because the standard Hermes Hub artifact still supports minSdk 26.
            disable += "ObsoleteSdkInt"
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.04.01"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.work:work-runtime-ktx:2.11.0")
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
