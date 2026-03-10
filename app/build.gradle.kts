import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use(::load)
    }
}

fun String.escapeForBuildConfig(): String = replace("\\", "\\\\").replace("\"", "\\\"")

fun buildConfigString(value: String): String = "\"${value.escapeForBuildConfig()}\""

val approovConfig = providers.gradleProperty("approovConfig")
    .orElse(localProperties.getProperty("approov.config") ?: "")
    .get()

val approovDevKey = providers.gradleProperty("approovDevKey")
    .orElse(localProperties.getProperty("approov.devKey") ?: "")
    .get()

val shapesApiKey = providers.gradleProperty("shapesApiKey")
    .orElse(localProperties.getProperty("shapes.apiKey") ?: "yXClypapWNHIifHUWmBIyPFAm")
    .get()

android {
    namespace = "approov.io.webviewjava"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "approov.io.webviewjava"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "APPROOV_CONFIG", buildConfigString(approovConfig))
        buildConfigField("String", "APPROOV_DEV_KEY", buildConfigString(approovDevKey))
        buildConfigField("String", "SHAPES_API_KEY", buildConfigString(shapesApiKey))
        buildConfigField("String", "SHAPES_API_URL", "\"https://shapes.approov.io/v2/shapes\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.okhttp)
    implementation(libs.approov.service.okhttp)
    implementation(libs.webkit)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
