import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.formulatracker"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.formulatracker"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    flavorDimensions += "device"
    productFlavors {
        create("emulator") {
            dimension = "device"
            isDefault = true
            buildConfigField("String", "BACKEND_URL", "\"http://10.0.2.2:8000\"")
        }
        create("phone") {
            dimension = "device"
            val backendUrl = localBackendUrl() ?: "http://127.0.0.1:9"
            buildConfigField("String", "BACKEND_URL", "\"$backendUrl\"")
        }
    }
    gradle.taskGraph.whenReady {
        val buildingPhone = allTasks.any { "Phone" in it.name }
        if (buildingPhone && localBackendUrl() == null) {
            throw GradleException(
                "Add backend.url=http://100.x.x.x:8000 to android/local.properties before building the phone variant.",
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

private fun localBackendUrl(): String? {
    val properties = Properties()
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { properties.load(it) }
    }
    val url = properties.getProperty("backend.url")?.trim().orEmpty()
    if (url.isEmpty()) {
        return null
    }
    if (!url.startsWith("http://") && !url.startsWith("https://")) {
        throw GradleException("backend.url must start with http:// or https://")
    }
    if (url.contains('"')) {
        throw GradleException("backend.url cannot contain a quote.")
    }
    return url
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.work.runtime)
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}