import com.android.build.gradle.internal.api.ApkVariantOutputImpl
import java.io.FileInputStream
import java.util.Date
import java.text.SimpleDateFormat
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.horaslite.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.horaslite.app"
        minSdk = 23
        targetSdk = 34
        versionCode = 1762553470
        versionName = "1.0.21"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val keystorePropertiesFile = rootProject.file("keystore.properties")
            if (keystorePropertiesFile.exists()) {
                val keystoreProperties = Properties().apply {
                    load(FileInputStream(keystorePropertiesFile))
                }

                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            isMinifyEnabled = false
        }


    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }



    applicationVariants.all {
        outputs
            .map { it as ApkVariantOutputImpl }
            .forEach { output ->
                val variant = buildType.name
                val versionName = defaultConfig.versionName
                val apkName = if (variant == "release") {
                    "HorasLite-v${versionName}.apk"
                } else {
                    "HorasLite-v${versionName}-${variant}.apk"
                }
                output.outputFileName = apkName
            }
    }


}

tasks.register("generateUpdateJson") {
    dependsOn("assembleRelease")

    doLast {
        val versionCode = 1762553470
        val versionName = android.defaultConfig.versionName
        val apkFileName = "HorasLite-v${versionName}.apk"

        // 👉 Usamos "latest" en lugar de tag específico
        val apkUrl = "https://github.com/CristianghCode/HorasLite/releases/latest/download/${apkFileName}"

        val jsonContent = """
            {
              "versionCode": $versionCode,
              "versionName": "$versionName",
              "apkUrl": "$apkUrl"
            }
        """.trimIndent()

        val outputDir = file("${project.buildDir}/outputs/apk/release")
        val outputFile = file("$outputDir/horaslite-update.json")

        outputDir.mkdirs()
        outputFile.writeText(jsonContent)

        println("✅ Generado horaslite-update.json en: ${outputFile.absolutePath}")
        println("📦 URL de descarga configurada: $apkUrl")
    }
}


dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("com.google.firebase:protolite-well-known-types:18.0.1")
}