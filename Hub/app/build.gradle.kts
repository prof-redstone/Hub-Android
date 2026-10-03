plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "tomd.ovh"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "tomd.ovh"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // Signed with the debug key on purpose, not a dedicated keystore.
            //
            // Two reasons. First, a release build type with no signingConfig produces
            // an *unsigned* APK, and the system installer refuses it outright
            // ("Impossible d'installer l'application") — which is useless for the
            // one thing a release build is for here: sending it to someone. Second,
            // reusing the debug key means anyone already running the debug APK can
            // upgrade in place, instead of hitting a signature mismatch and having to
            // uninstall first (which wipes hub_config, i.e. every customisation).
            //
            // ⚠️ The cost: the key lives in ~/.android/debug.keystore, which Android
            // Studio regenerates if it is ever lost. A new key cannot update the
            // existing install on a friend's device, so back that file up. And if
            // this ever goes to the Play Store, a real upload key becomes mandatory.
            signingConfig = signingConfigs.getByName("debug")

            // R8 on, no packageScope.
            //
            // Suspected cause of a crash-on-launch that only affected the signed
            // release build (debug was fine, so the app code itself is not at fault).
            //
            // packageScope tells R8 "only these packages may be shrunk". Setting it
            // here meant tomd.ovh was excluded from the shrink, so R8 no longer
            // treated it as part of the program under analysis. The consumer rules
            // bundled with Compose / navigation / lifecycle assume a whole-program
            // view, so a partial shrink can strip something whose only remaining
            // reference sits in the app — which surfaces as an immediate crash,
            // before a single frame is drawn.
            //
            // packageScope is designed for library modules, not application modules.
            // Removing it lets R8 optimize the whole program, the well-trodden path.
            //
            // ⚠️ Unverified: no crash log was available when this was changed. If the
            // app still dies on launch, R8 is still the prime suspect, but pinning the
            // real cause needs `adb logcat` on a device running the release build.
            optimization {
                enable = true
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
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
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.compose.material.icons.core)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}