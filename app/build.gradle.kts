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
        versionCode = 19
        versionName = versionCode.toString()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "BUNDLER_API", "\"${localProps.getProperty("BUNDLER_API", "")}\"")
        buildConfigField("String", "ALCHEMY_API", "\"${localProps.getProperty("ALCHEMY_API", "")}\"")
        buildConfigField("String", "PREMIUM_LLM_URL", "\"${localProps.getProperty("PREMIUM_LLM_URL", "https://api.markushaas.com/api/premium-llm-andy")}\"")

    }

    if (localProps.containsKey("RELEASE_STORE_FILE")) {
        signingConfigs {
            create("release") {
                storeFile = file(localProps.getProperty("RELEASE_STORE_FILE"))
                storePassword = localProps.getProperty("RELEASE_STORE_PASSWORD")
                keyAlias = localProps.getProperty("RELEASE_KEY_ALIAS")
                keyPassword = localProps.getProperty("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (localProps.containsKey("RELEASE_STORE_FILE")) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        resources {
            pickFirsts += listOf(
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/DISCLAIMER",
            )
            excludes += listOf(
                "META-INF/INDEX.LIST",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE.md",
                "META-INF/NOTICE.md",
                "META-INF/io.netty.versions.properties",
                "META-INF/FastDoubleParser-*",
                "META-INF/BigDecimal*",
            )
        }
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
    implementation(libs.splashscreen)
    implementation(libs.beanshell)
    // Tinfoil bridge — Go wrapper around tinfoil-go SDK for TEE-attested inference
    implementation(files("../tinfoil-bridge/tinfoil-bridge.aar"))
    // Llamatik — Kotlin Multiplatform llama.cpp wrapper for local LLM inference
    implementation("com.llamatik:library:0.16.0")
    // Aurora Store gplayapi for downloading apps from Play Store
    implementation(libs.gplayapi)
    // DgenSubAccountSDK — gives the LLM its own sub-account wallet (SubWalletSDK)
    // and exposes the OS-level system wallet (WalletSDK) transitively
    implementation("com.github.EthereumPhone:DgenSubAccountSDK:0.2.0")
    // MessengerSDK for XMTP messaging
    implementation("com.github.EthereumPhone:MessengerSDK:0.5.0")
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

// When SYSTEM_APP=true in local.properties, build as a system app
// (android:sharedUserId="android.uid.system") to get privileged permissions.
val isSystemApp: Boolean = localProps.getProperty("SYSTEM_APP", "false").toBoolean()
if (isSystemApp) {
    afterEvaluate {
        tasks.configureEach {
            if (name.startsWith("process") && name.contains("Manifest")) {
                doLast {
                    val intDir = project.layout.buildDirectory.get().asFile.resolve("intermediates")
                    intDir.walkTopDown()
                        .filter { it.name == "AndroidManifest.xml" }
                        .forEach { manifestFile ->
                            val text = manifestFile.readText()
                            if (!text.contains("android:sharedUserId")) {
                                manifestFile.writeText(
                                    text.replaceFirst(
                                        "<manifest",
                                        "<manifest android:sharedUserId=\"android.uid.system\""
                                    )
                                )
                            }
                        }
                }
            }
        }
    }
}
