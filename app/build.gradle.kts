import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 签名信息放在仓库外：keystore.properties 已在 .gitignore 里
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        FileInputStream(keystorePropsFile).use { load(it) }
    }
}
val hasSigning = keystorePropsFile.exists()

// CI 打 tag 时用 tag 名做版本号（v1.0.1 -> 1.0.1），本地构建不设则用默认值
val envVersionName = System.getenv("VERSION_NAME")?.removePrefix("v")?.takeIf { it.isNotBlank() }
val envVersionCode = System.getenv("VERSION_CODE")?.toIntOrNull()

// tag 版本号推导 versionCode：1.0.1 -> 10001，避免每次发版都停在 1
val baseVersionName = envVersionName ?: "1.1.1"
val derivedCode = baseVersionName.let { v ->
    val nums = Regex("\\d+").findAll(v).take(3).map { it.value.toIntOrNull() ?: 0 }.toList()
    if (nums.size >= 2) nums[0] * 10000 + nums[1] * 100 + nums.getOrElse(2) { 0 } else null
}

android {
    namespace = "com.kirufetch.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.kirufetch.app"
        minSdk = 26
        targetSdk = 34
        versionCode = envVersionCode ?: derivedCode ?: 1
        versionName = baseVersionName
    }

    signingConfigs {
        if (hasSigning) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
