pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // SumUp Reader SDK (Kartenterminal Solo/Solo Lite); nur die Pakete, die es dort gibt
        maven("https://maven.sumup.com/releases") {
            content {
                includeGroupByRegex("com\\.sumup.*")
                includeGroup("net.sf.smc") // Zustandsmaschine, die das SumUp-SDK von dort bezieht
            }
        }
    }
}
rootProject.name = "AnyPOS"
include(":app")
include(":tse-mock")
