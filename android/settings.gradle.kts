pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "KoPilot"
include(":app")
include(":core:common")
include(":core:designsystem")
include(":core:database")
include(":core:network")
include(":core:security")
include(":feature:dashboard")
include(":feature:camera")
include(":feature:vision")
include(":feature:location")
include(":feature:navigation")
include(":feature:traffic")
include(":feature:weather")
include(":feature:voice")
include(":feature:alerts")
include(":feature:vehicle")
include(":feature:trips")
include(":feature:settings")
include(":feature:diagnostics")
include(":feature:sensors")
