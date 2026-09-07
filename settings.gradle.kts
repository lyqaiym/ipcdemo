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
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // walle 仅发布在已停运的 jcenter，Maven Central 上没有；只让该 group 走阿里云镜像
        maven {
            url = uri("https://maven.aliyun.com/repository/public")
            content {
                includeGroup("com.meituan.android.walle")
            }
        }
    }
}

rootProject.name = "ipcdemo"
include(":app")
