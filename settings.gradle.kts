pluginManagement {
    // 本地走阿里云镜像（国内快），CI 直接用官方源：
    // 镜像偶尔 502，Gradle 遇到 5xx 会直接失败，不会跳到下一个仓库。
    val useMirror = System.getenv("CI").isNullOrBlank()
    repositories {
        if (useMirror) {
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/public") }
            maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    val useMirror = System.getenv("CI").isNullOrBlank()
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        if (useMirror) {
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/public") }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "KiruFetch"
include(":app")
