pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

// karoo-ext jest publikowane w GitHub Packages, które zawsze wymaga uwierzytelnienia
// (wystarczy dowolny token GitHub z uprawnieniem read:packages).
// Kolejność: zmienne środowiskowe (CI) -> gradle.properties / ~/.gradle/gradle.properties
val gprUser: String? = System.getenv("GPR_USER") ?: providers.gradleProperty("gpr.user").orNull
val gprKey: String? = System.getenv("GPR_KEY") ?: providers.gradleProperty("gpr.key").orNull

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://maven.pkg.github.com/hammerheadnav/karoo-ext")
            credentials {
                username = gprUser
                password = gprKey
            }
        }
    }
}

rootProject.name = "KarooNotify"
include(":phone")
include(":karoo")
