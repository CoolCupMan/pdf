plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Version = <appVersionBase>.<Build-Nummer>, z. B. 1.0.7
val versionBase: String = providers.gradleProperty("appVersionBase").getOrElse("1.0")
val buildNumber: Int = (providers.gradleProperty("buildNumber").orNull
    ?: System.getenv("GITHUB_RUN_NUMBER")
    ?: "1").toInt()
val fullVersion = "$versionBase.$buildNumber"

// Eindeutige Application-ID pro Version -> mehrere Versionen parallel installierbar,
// z. B. com.coolcupman.pdfextractor.v1_0_7
val idSuffix = "v" + fullVersion.replace('.', '_')

android {
    namespace = "com.coolcupman.pdfextractor"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.coolcupman.pdfextractor.$idSuffix"
        minSdk = 24
        targetSdk = 35
        versionCode = buildNumber
        versionName = fullVersion
        resValue("string", "app_name", "PDF Extraktor $fullVersion")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Mit Debug-Key signiert, damit die APK direkt installierbar ist.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // Post-Quantum-Tabellen von BouncyCastle (~4 MB), von PDFBox nicht benötigt
            excludes += "/org/bouncycastle/pqc/**"
        }
    }
}

base {
    archivesName.set("PdfExtraktor-$fullVersion")
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    testImplementation("junit:junit:4.13.2")
}
