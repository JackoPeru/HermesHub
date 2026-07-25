import java.util.Properties

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

val enableMetaDat = providers.gradleProperty("enableMetaDat").orNull?.toBooleanStrictOrNull() == true
val localProperties = Properties().apply {
    val source = file("local.properties")
    if (source.isFile) source.inputStream().use(::load)
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        if (enableMetaDat) {
            maven {
                name = "MetaWearablesGitHubPackages"
                url = uri("https://maven.pkg.github.com/facebook/meta-wearables-dat-android")
                credentials {
                    username = providers.environmentVariable("GITHUB_ACTOR").orNull ?: "github"
                    password = providers.environmentVariable("GITHUB_TOKEN").orNull
                        ?: localProperties.getProperty("githubPackagesToken")
                        ?: throw GradleException(
                            "Meta DAT richiede GITHUB_TOKEN o githubPackagesToken in local.properties con scope read:packages."
                        )
                }
            }
        }
    }
}

rootProject.name = "ChatClawAndroid"
include(":app")
