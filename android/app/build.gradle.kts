plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Version from git, so every build says which commit it is: 0.1.<commits> (<hash>), "-dirty" if built with uncommitted changes.
fun git(vararg args: String) = providers.exec { commandLine("git", *args) }.standardOutput.asText.get().trim()
val commits = git("rev-list", "--count", "HEAD").toInt()
val describe = git("describe", "--always", "--dirty")

android {
    namespace = "com.willlauer.summit"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.willlauer.summit"
        minSdk = 31
        targetSdk = 37
        versionCode = commits
        versionName = "0.1.$commits ($describe)"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")  // sideloaded onto one phone
        }
    }
    buildFeatures { compose = true; buildConfig = true }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.navigation:navigation-compose:2.10.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-session:1.11.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.11.1")
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
    implementation("io.coil-kt.coil3:coil-compose:3.6.3")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.6.3")
    implementation("sh.calvin.reorderable:reorderable:3.1.0")
}
