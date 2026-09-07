import com.meituan.android.walle.ChannelWriter
import java.io.File
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

// walle 多渠道打包：官方插件不兼容 AGP 9（applicationVariants API 已移除），
// 改为直接调用 payload_writer，给已 v2 签名的 release APK 注入渠道
tasks.register("assembleReleaseChannels") {
    group = "publishing"
    description = "生成 walle 多渠道 release 包。渠道列表见 app/channel，输出到 build/outputs/channels/"
    dependsOn("assembleRelease")

    val channelsFile = layout.projectDirectory.file("channel")
    val releaseApk = layout.buildDirectory.file("outputs/apk/release/app-release.apk")
    val outDir = layout.buildDirectory.dir("outputs/channels")
    inputs.file(channelsFile)
    inputs.file(releaseApk)
    outputs.dir(outDir)

    doLast {
        val channels = channelsFile.asFile.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { it.substringBefore("#").trim() }
            .filter { it.isNotEmpty() }
        val output = outDir.get().asFile
        output.mkdirs()
        channels.forEach { channel ->
            val channelApk = File(output, "app-release-$channel.apk")
            releaseApk.get().asFile.copyTo(channelApk, overwrite = true)
            // ChannelWriter 直接改写 APK Signing Block，注入后 v2/v3 签名仍然有效
            ChannelWriter.put(channelApk, channel)
            logger.lifecycle("walle channel apk: ${channelApk.absolutePath}")
        }
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.walle)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}