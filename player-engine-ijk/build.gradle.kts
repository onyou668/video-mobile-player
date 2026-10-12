plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.mobileplayer.ijk"
    compileSdk = 36

    defaultConfig {
        minSdk = 23
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    api(project(":player-core"))
    implementation(libs.ijkplayer.java)
    implementation(libs.ijkplayer.native)
}
