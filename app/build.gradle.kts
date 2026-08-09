import java.util.Date
import java.text.SimpleDateFormat

plugins {
    alias(libs.plugins.android.application)
}

val buildTimestamp = SimpleDateFormat("yyyyMMdd_HHmm").format(Date())

// Safe way to get Git hash without breaking the build
fun getSafeGitHash(): String {
    return try {
        val process = Runtime.getRuntime().exec("git rev-parse --short HEAD")
        val result = process.inputStream.bufferedReader().readText().trim()
        
        val statusProcess = Runtime.getRuntime().exec("git status --porcelain")
        val status = statusProcess.inputStream.bufferedReader().readText().trim()
        
        if (status.isNotEmpty()) "$result+" else result
    } catch (e: Exception) {
        "no-git"
    }
}

val gitHash = getSafeGitHash()

android {
    namespace = "com.example.yaesucatcontroladv"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.yaesucatcontroladv"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.1"

        buildConfigField("String", "BUILD_TIME", "\"$buildTimestamp\"")
        buildConfigField("String", "GIT_HASH", "\"$gitHash\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        buildConfig = true
    }
}

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val safeHash = gitHash.replace("+", "p")
            output.outputFileName.set("YaesuCAT_v${android.defaultConfig.versionName}_${buildTimestamp}_${safeHash}.apk")
        }
    }
}

dependencies {
    implementation(libs.activity.ktx)
    implementation(libs.appcompat)
    implementation(libs.constraintlayout)
    implementation(libs.material)
    implementation("androidx.security:security-crypto:1.1.0")
    testImplementation(libs.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
    implementation("com.github.mik3y:usb-serial-for-android:3.10.0")
}
