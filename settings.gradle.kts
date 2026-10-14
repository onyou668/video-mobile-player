pluginManagement {
    repositories {
        clear()
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
        clear()
        google()
        mavenCentral()
    }
}

rootProject.name = "MobilePlayer"

include(":player-core")
include(":player-engine-media3")
include(":player-engine-ijk")
include(":player-compose")
include(":sample-app")
