pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google(); mavenCentral()
        maven("https://devrepo.kakao.com/nexus/content/groups/public/") {
            content { includeGroupByRegex("com\\.kakao\\..*") }
        }
        maven("https://devrepo.kakaomobility.com/repository/kakao-mobility-android-knsdk-public/") {
            content { includeGroup("com.kakaomobility.knsdk") }
        }
        maven("https://devrepo.kakaomobility.com/repository/kakao-mobility-android-locationsdk-release/") {
            content { includeGroup("com.kakaomobility.location.library") }
        }
        maven("https://devrepo.kakaomobility.com/repository/kakao-mobility-android-knsdk-release/") {
            content { includeGroupByRegex("com\\.kakaomobility\\..*") }
        }
    }
}
rootProject.name = "TeslaNavi"
include(":app", ":protocol")
