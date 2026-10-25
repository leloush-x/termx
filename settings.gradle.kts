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
        // Termux's terminal-emulator is published only through JitPack.
        // Limit JitPack to the one group we need (supply-chain guard).
        maven {
            url = uri("https://jitpack.io")
            content { includeGroupByRegex("com\\.github\\.termux\\..*") }
        }
    }
}

rootProject.name = "TermX"
include(":app")
