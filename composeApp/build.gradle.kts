import org.jetbrains.compose.ExperimentalComposeLibrary
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.sqldelight)
    alias(libs.plugins.googleServices)
}

/**
 * The API base URL is a build-config field, never a literal in the source (engineering rules
 * in docs/BUILD_PROMPT_M0.md). Override it in `local.properties`, which is gitignored:
 *
 *     wishkaro.apiBaseUrl=http://192.168.1.20:3000
 *
 * The default assumes `adb reverse tcp:3000 tcp:3000`, which points the phone's own
 * 127.0.0.1:3000 at the server running on this machine. See docs/M0_VERIFY.md.
 */
val apiBaseUrl: String = run {
    val local = rootProject.file("local.properties")
    val fromLocal = if (local.exists()) {
        Properties().apply { local.inputStream().use(::load) }.getProperty("wishkaro.apiBaseUrl")
    } else {
        null
    }
    fromLocal ?: "http://127.0.0.1:3000"
}

kotlin {
    // expect/actual classes are still flagged Beta; the pattern is the documented way to do
    // platform shims and the warning is noise on every build.
    compilerOptions { freeCompilerArgs.add("-Xexpect-actual-classes") }

    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions { jvmTarget.set(JvmTarget.JVM_11) }
    }

    // iOS is kept compiling but is not shipped in v1 (D15). The actuals in iosMain are
    // deliberate stubs; the value here is that the shared code cannot quietly become
    // JVM-only without CI noticing.
    // No iosX64: Compose Multiplatform 1.11 no longer publishes the Intel simulator target,
    // and every Mac that can build this is Apple Silicon.
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)

            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)

            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.client.logging)
            implementation(libs.ktor.serialization.json)

            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.compose.viewmodel)

            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            @OptIn(ExperimentalComposeLibrary::class)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)
            implementation(libs.ktor.client.mock)
        }

        androidMain.dependencies {
            implementation(compose.preview)
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core.ktx)
            implementation(libs.kotlinx.coroutines.android)

            implementation(libs.ktor.client.okhttp)
            implementation(libs.koin.android)
            implementation(libs.sqldelight.android.driver)

            implementation(libs.androidx.security.crypto)
            implementation(libs.androidx.credentials)
            implementation(libs.androidx.credentials.play.services)
            implementation(libs.googleid)

            implementation(project.dependencies.platform(libs.firebase.bom))
            implementation(libs.firebase.messaging)
        }

        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
            implementation(libs.sqldelight.native.driver)
        }
    }
}

android {
    namespace = "in.wishkaro.app"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "in.wishkaro.app"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        debug {
            buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
            isMinifyEnabled = false
        }
        release {
            // M0 has no release signing config yet; a release build needs its own OAuth
            // client registered against the release SHA-1 before Google Sign-In works.
            buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

sqldelight {
    databases {
        create("WishKaroDb") {
            // Deliberately NOT in.wishkaro.app.db: `in` is a Kotlin keyword, and generated
            // code would need backticks the generator does not always emit. The app id and
            // the Android namespace are unaffected — this names generated classes only.
            packageName.set("wishkaro.db")
        }
    }
}

dependencies {
    debugImplementation(compose.uiTooling)
}
