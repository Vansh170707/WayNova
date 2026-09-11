plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.android.libraries.mapsplatform.secrets-gradle-plugin")
}

android {
    namespace = "org.neuronavx.logger"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.neuronavx.logger"
        minSdk = 26          // elapsedRealtimeNanos on Location, and modern sensor batching
        targetSdk = 36
        versionCode = 17
        versionName = "0.2-round2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // ONNX Runtime ships native libraries for four ABIs, which alone made the debug
        // APK 76 MB. Real phones are arm64; x86_64 is kept only so the app can be smoke
        // tested on an emulator.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }

    // the exported model ships as an asset; keep it uncompressed so ORT can map it
    androidResources { noCompress += listOf("onnx", "pte", "pmtiles", "pbf") }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity:1.9.3")
    implementation("com.google.android.gms:play-services-maps:20.0.0")
    // Stable OpenGL backend: broad phone/emulator support and native local-PMTiles reading.
    implementation("org.maplibre.gl:android-sdk-opengl:13.6.0")
    // ONNX Runtime was both the fastest desktop path and the least troublesome Android
    // dependency. The ExecuTorch .pte is exported alongside for the blueprint's preferred
    // path once its AAR is added.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.19.2")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
}

// MAPS_API_KEY belongs in the ignored mobile/local.properties file. The checked-in
// default keeps builds and the local fallback working before a key is provisioned.
secrets {
    propertiesFileName = "local.properties"
    defaultPropertiesFileName = "local.defaults.properties"
}
