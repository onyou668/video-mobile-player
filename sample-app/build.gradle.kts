plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.mobileplayer.sample"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.mobileplayer.sample"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures { compose = true }
}

dependencies {
    implementation(project(":player-compose"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
}
