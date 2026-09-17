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
    maven { url = uri("${rootDir}/local-repo") }
    google()
    mavenCentral()
    maven { url = uri("https://maven.mozilla.org/maven2/") }
    maven { url = uri("https://raw.githubusercontent.com/guardianproject/gpmaven/master") }
    maven { url = uri("https://jitpack.io") }
  }
}

rootProject.name = "Remmi Browser"

include(":app")
