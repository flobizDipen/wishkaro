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
 * in docs/BUILD_PROMPT_M0.md), and it differs per build type (docs/M1_PLAN.md §2.4):
 *
 *   debug   -> a server on this machine, reached through `adb reverse tcp:3000 tcp:3000`
 *   release -> the deployed server, https, no cleartext exemption
 *
 * Both are overridable from `local.properties`, which is gitignored:
 *
 *     wishkaro.apiBaseUrl=https://wishkaro-production-0312ab77.up.railway.app
 *     wishkaro.releaseApiBaseUrl=https://api.example.com
 *
 * Pointing the DEBUG build at the deployed https URL is how M1's task zero is proved: the
 * phone talks to Railway over mobile data with no `adb reverse` and no port forward, while
 * still being signed with the debug keystore whose SHA-1 Google Sign-In is registered
 * against. See docs/M1_VERIFY.md.
 */
private val localProperties: Properties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use(::load)
}

val debugApiBaseUrl: String =
    localProperties.getProperty("wishkaro.apiBaseUrl") ?: "http://127.0.0.1:3000"

val releaseApiBaseUrl: String =
    localProperties.getProperty("wishkaro.releaseApiBaseUrl")
        ?: "https://wishkaro-production-0312ab77.up.railway.app"

/**
 * REQUIREMENTS.md §11 requires TLS everywhere, and the cleartext exemption in
 * `src/debug/res/xml/network_security_config.xml` is scoped to the debug manifest so it
 * cannot reach a release build. A plaintext release URL would therefore produce an app that
 * compiles, installs, and fails every request at runtime — caught here instead.
 */
require(releaseApiBaseUrl.startsWith("https://")) {
    "wishkaro.releaseApiBaseUrl must be https (got: $releaseApiBaseUrl). Release builds have " +
        "no cleartext exemption."
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
            implementation(libs.androidx.work.runtime)
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
            buildConfigField("String", "API_BASE_URL", "\"$debugApiBaseUrl\"")
            isMinifyEnabled = false
        }
        release {
            // Still no release signing config: a release build needs its own OAuth client
            // registered against the release keystore's SHA-1 before Google Sign-In works,
            // and that keystore is a secret that must never reach this public repo. Deferred
            // to the milestone that first puts a build in front of a tester — docs/M1_PLAN.md
            // §2.4. The base URL is wired correctly regardless, so the only thing missing is
            // the signing identity.
            buildConfigField("String", "API_BASE_URL", "\"$releaseApiBaseUrl\"")
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

            /**
             * M1 adds columns to `person` and `event` and a whole `person_trait` table, and
             * there are real installs out there — the handset M0 was proved on among them.
             *
             * `AndroidSqliteDriver` replays the `.sqm` files on upgrade. Without one, the new
             * `.sq` schema compiles and installs happily and then throws
             * "no such column" on the first query against an existing database. That is a
             * crash on exactly the device this milestone is meant to be verified on.
             *
             * `schemaOutputDirectory` holds the snapshot of each released schema;
             * `verifyMigrations` makes the BUILD fail if applying the migrations to that
             * snapshot does not reproduce the current `.sq` files. So the next person to add
             * a column gets a build error rather than a field report.
             */
            schemaOutputDirectory.set(file("src/commonMain/sqldelight/databases"))
            verifyMigrations.set(true)
        }
    }
}

dependencies {
    debugImplementation(compose.uiTooling)
}
