plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}
val bridgeServerUrl = providers.gradleProperty("BRIDGE_SERVER_URL").orElse("").get()
android {
    signingConfigs {
        create("release") {
            val signingPath = System.getenv("ANDROID_KEYSTORE_PATH")
            if (!signingPath.isNullOrBlank()) {
                storeFile = file(signingPath)
                storePassword = System.getenv("ANDROID_SIGNING_PASSWORD")
                keyAlias = "smsbridge"
                keyPassword = System.getenv("ANDROID_SIGNING_PASSWORD")
            }
        }
    }
    namespace = "il.smsbridge.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "il.smsbridge.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.3.1"
        buildConfigField("String", "BRIDGE_SERVER_URL", "\"${bridgeServerUrl}\"")
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
}
dependencies {
    implementation(platform("com.google.firebase:firebase-bom:35.1.0"))
    implementation("com.google.firebase:firebase-messaging")
    implementation("androidx.work:work-runtime-ktx:2.10.5")
}
