import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val localProps = Properties()
val localPropsFile = rootProject.file("local.properties")
if (localPropsFile.exists()) {
    localProps.load(localPropsFile.inputStream())
}

android {
    namespace = "org.ethereumphone.andyclaw"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "org.ethereumphone.andyclaw"
        minSdk = 35
        targetSdk = 36
        versionCode = 2
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "BUNDLER_API", "\"${localProps.getProperty("BUNDLER_API", "")}\"")
        buildConfigField("String", "ALCHEMY_API", "\"${localProps.getProperty("ALCHEMY_API", "")}\"")
        buildConfigField("String", "OPENROUTER_API_KEY", "\"${localProps.getProperty("OPENROUTER_API_KEY", "")}\"")
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
        compose = true
        aidl = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":AndyClaw"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.kotlinx.serialization.json)
    // Room is provided transitively via the :AndyClaw library module
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.navigation.compose)
    implementation(libs.appcompat)
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.video)
    implementation(libs.exifinterface)
    implementation(libs.security.crypto)
    implementation(libs.beanshell)
    // WalletSDK for ethOS system wallet (ERC-4337)
    implementation("org.web3j:core:4.9.4")
    implementation("com.github.EthereumPhone:WalletSDK:0.3.0")
    // MessengerSDK for XMTP messaging
    implementation("com.github.EthereumPhone:MessengerSDK:0.2.0")
    // ContactsSDK for ethOS contacts with ETH address support
    implementation("com.github.EthereumPhone:ContactsSDK:0.1.0")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
