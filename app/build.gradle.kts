import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val versionProperties = Properties().apply { rootProject.file("version.properties").inputStream().use(::load) }

android {
    namespace = "com.sleepysoong.bramble"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.sleepysoong.bramble"
        minSdk = 31
        targetSdk = 35
        versionCode = versionProperties.getProperty("versionCode").toInt()
        versionName = versionProperties.getProperty("versionName")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            val keystorePath = providers.environmentVariable("BRAMBLE_KEYSTORE_PATH").orNull
            if (keystorePath != null) {
                signingConfig = signingConfigs.create("release") {
                    storeFile = file(keystorePath)
                    storePassword = providers.environmentVariable("BRAMBLE_KEYSTORE_PASSWORD").get()
                    keyAlias = providers.environmentVariable("BRAMBLE_KEY_ALIAS").get()
                    keyPassword = providers.environmentVariable("BRAMBLE_KEY_PASSWORD").get()
                }
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("io.github.kyant0:backdrop:2.0.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

tasks.withType<Test>().configureEach {
    maxHeapSize = "1024m"
    maxParallelForks = 1
    jvmArgs("-XX:+UseSerialGC", "-XX:ActiveProcessorCount=2", "-XX:TieredStopAtLevel=1")
}
