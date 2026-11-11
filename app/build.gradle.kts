plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.ifev.app"
    compileSdk = 35
    // AAOS Car API, for vehicle speed (PERF_VEHICLE_SPEED) which the Pleos SDK does not expose.
    useLibrary("android.car")

    defaultConfig {
        applicationId = "com.ifev.app"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 공모전 시연용 release APK를 바로 설치할 수 있도록 debug 키로 서명한다. 실제 배포 전에는 전용 키로 바꾼다.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        unitTests.all {
            // ./gradlew :app:testDebugUnitTest --tests '*RealDriveReplay*' -Pdrives=/절대/경로 --rerun
            it.systemProperty("ifev.drives", (project.findProperty("drives") ?: "").toString())
        }
    }
}

dependencies {
    implementation(libs.pleos.vehicle)
    implementation(libs.pleos.navihelper)
    implementation(libs.pleos.tts)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    // org.json is only a stub in local unit tests; the real one reads recorded drives there.
    testImplementation(libs.org.json)
}
