import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// 签名信息从 local.properties 读取（该文件不进 git）
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "com.example.ipcdemo"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.ipcdemo"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++11"
            }
        }
    }

    signingConfigs {
        create("release") {
            localProperties.getProperty("keystore.path")?.let { path ->
                storeFile = rootProject.file(path)
                storePassword = localProperties.getProperty("keystore.password")
                keyAlias = localProperties.getProperty("key.alias")
                keyPassword = localProperties.getProperty("key.password")
            }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}