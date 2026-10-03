import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Version can be injected by CI: ./gradlew assembleRelease -PappVersionName=1.0.0 -PappVersionCode=42
val appVersionName: String = (findProperty("appVersionName") as String?) ?: "1.0.0"
val appVersionCode: Int = (findProperty("appVersionCode") as String?)?.toIntOrNull() ?: 1

// Release signing is configured exclusively through environment variables (GitHub Secrets in CI)
// or an untracked keystore.properties file. Nothing secret is ever committed.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(env: String, prop: String): String? =
    System.getenv(env)?.takeIf { it.isNotBlank() } ?: keystoreProps.getProperty(prop)?.takeIf { it.isNotBlank() }

val releaseStoreFile = signingValue("GA_KEYSTORE_FILE", "storeFile")
val releaseStorePassword = signingValue("GA_KEYSTORE_PASSWORD", "storePassword")
val releaseKeyAlias = signingValue("GA_KEY_ALIAS", "keyAlias")
val releaseKeyPassword = signingValue("GA_KEY_PASSWORD", "keyPassword")
val hasReleaseSigning = listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword).all { it != null } &&
    file(releaseStoreFile!!).exists()

android {
    namespace = "com.greenaccesse.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.greenaccesse.app"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        setProperty("archivesBaseName", "Green-Accesse")
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                // Fallback so CI can still produce an installable APK when no secrets are configured.
                // Such builds are clearly labelled in the release workflow and must not be distributed.
                logger.warn("Green Accesse: release signing secrets not configured - signing release with the DEBUG key.")
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        checkReleaseBuilds = true
        // Dependency update checks need network/maven metadata and are not build correctness issues.
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "OldTargetApi")
        xmlReport = true
        htmlReport = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/versions/9/previous-compilation-data.bin")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
