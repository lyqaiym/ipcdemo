// Top-level build file where you can add configuration options common to all sub-projects/modules.
buildscript {
    repositories {
        google()
        mavenCentral()
        // walle 只发布在已停运的 jcenter，限定 group 走阿里云镜像
        maven("https://maven.aliyun.com/repository/public") {
            content {
                includeGroup("com.meituan.android.walle")
            }
        }
    }
    dependencies {
        // walle 官方 Gradle 插件依赖 AGP 2.x~4.x 的 applicationVariants API（AGP 9 已移除），
        // 因此只引入底层的 payload_writer，由 app 模块的自定义任务写渠道
        classpath(libs.walle.payload.writer)
        // payload_writer 用 org.json 拼 payload，但该依赖在它的 POM 里是 compileOnly
        classpath(libs.org.json)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
}
