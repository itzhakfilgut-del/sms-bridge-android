plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}
val bridgeServerUrl = providers.gradleProperty("BRIDGE_SERVER_URL").orElse("").get()
android {
    namespace = "il.smsbridge.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "il.smsbridge.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.3.0"
        buildConfigField("String", "BRIDGE_SERVER_URL", "\"${bridgeServerUrl}\"")
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
