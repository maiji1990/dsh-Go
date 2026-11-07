pluginManagement {
    repositories {
        if (System.getenv("DSH_MAVEN_MIRRORS") != "0") maven { url = uri("https://maven.aliyun.com/repository/google") }
        google()
        mavenCentral()
    }
}
rootProject.name = "MobileWebViewTests"

dependencyResolutionManagement {
    repositories {
        if (System.getenv("DSH_MAVEN_MIRRORS") != "0") maven { url = uri("https://maven.aliyun.com/repository/google") }
        if (System.getenv("DSH_MAVEN_MIRRORS") != "0") maven { url = uri("https://maven.aliyun.com/repository/central") }
        google()
        mavenCentral()
    }
}
