import java.util.Date
import java.util.Properties
import java.text.SimpleDateFormat

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
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

// Semnatura de release: keystore.properties (local, ignorat de git) indica keystore-ul si
// parolele. Fara el, build-ul de release iese nesemnat; debug-ul nu e afectat.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) keystorePropertiesFile.inputStream().use { load(it) }
}

android {
    namespace = "com.example.yaesucatcontroladv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.yaesucatcontroladv"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.2"

        buildConfigField("String", "BUILD_TIME", "\"$buildTimestamp\"")
        buildConfigField("String", "GIT_HASH", "\"$gitHash\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
        externalNativeBuild {
            cmake {
                cppFlags += ""
                arguments += listOf("-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            if (keystorePropertiesFile.exists()) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    androidResources {
        noCompress("onnx", "onnx.gz")
    }

    buildFeatures {
        buildConfig = true
    }
}

// Custom APK naming removed for stability during sync fix
/*
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val safeHash = gitHash.replace("+", "p")
            // output.outputFileName.set("YaesuCAT_v${android.defaultConfig.versionName}_${buildTimestamp}_${safeHash}.apk")
        }
    }
}
*/

dependencies {
    implementation(libs.activity.ktx)
    implementation(libs.appcompat)
    implementation(libs.constraintlayout)
    implementation(libs.material)
    implementation("androidx.security:security-crypto:1.1.0")

    // DeepCW Neural Decoder Dependencies
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.onnxruntime.android)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    testImplementation(libs.junit)
    // Testele de pe PC (ex. comparatia modelelor CW) ruleaza ONNX cu varianta JVM - cea
    // Android nu are biblioteci native pentru desktop. Acelasi API si aceeasi versiune.
    testImplementation(libs.onnxruntime.jvm)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
    implementation("com.github.mik3y:usb-serial-for-android:3.10.0")

    // Server HTTP local (acces la fișierele de backup/log dintr-un browser din rețeaua WiFi)
    implementation("org.nanohttpd:nanohttpd:2.3.1")
}

// Varianta Android a onnxruntime nu se poate incarca pe JVM-ul de pe PC si ar intra in
// conflict cu cea JVM (aceleasi clase ai.onnxruntime) - o scoatem doar din testele unitare.
configurations.matching { it.name.endsWith("UnitTestRuntimeClasspath") }.configureEach {
    exclude(group = "com.microsoft.onnxruntime", module = "onnxruntime-android")
}
