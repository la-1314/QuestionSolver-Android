// Top-level build file: use buildscript classpath to bypass plugin-marker resolution.
buildscript {
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        google()
        mavenCentral()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.7.3")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.20")
        classpath("org.jetbrains.kotlin:kotlin-serialization:2.3.20")
        classpath("org.jetbrains.kotlin:compose-compiler-gradle-plugin:2.3.20")
    }
}
