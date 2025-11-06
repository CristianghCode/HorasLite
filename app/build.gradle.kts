import com.android.build.gradle.internal.api.ApkVariantOutputImpl
import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Source - https://stackoverflow.com/questions/18701932/how-can-i-retrieve-a-saved-keystore-password-from-android-studio
// Posted by kunlinwang
// Retrieved 2025-11-06, License - CC BY-SA 4.0

afterEvaluate {
    if (project.hasProperty("android.injected.signing.store.file")) {
        // Correct: Use parentheses for the function call
        println("key store path: ${project.property("android.injected.signing.store.file")}")
    }
    if (project.hasProperty("android.injected.signing.store.password")) {
        // Correct: Use parentheses for the function call
        println("key store password: ${project.property("android.injected.signing.store.password")}")
    }
    if (project.hasProperty("android.injected.signing.key.alias")) {
        // Correct: Use parentheses for the function call
        println("key alias: ${project.property("android.injected.signing.key.alias")}")
    }
    if (project.hasProperty("android.injected.signing.key.password")) {
        // Correct: Use parentheses for the function call
        println("key password: ${project.property("android.injected.signing.key.password")}")
    }
}


android {
    namespace = "com.horaslite.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.horaslite.app"
        minSdk = 23
        targetSdk = 34
        versionCode = 1762439858
        versionName = "1.0.17"
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
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
        // Add this line to enable the feature
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "1.8" }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        resources.excludes.add("org/threeten/bp/format/ChronologyText.properties")
    }



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
        val versionCode = 1762439858
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
    implementation("com.github.prolificinteractive:material-calendarview:2.0.1")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
    implementation("com.jakewharton.threetenabp:threetenabp:1.4.6")
// Or the latest version

}
