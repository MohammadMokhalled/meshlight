plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.screenshot)
    jacoco
}

android {
    namespace = "com.example.meshlightcontroller"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.meshlightcontroller"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "com.example.meshlightcontroller.MeshTestRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    experimentalProperties["android.experimental.enableScreenshotTest"] = true
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                // Robolectric Android 16 uses FileDescriptor accessors on the JDK 25 IDE runtime.
                it.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
                    "--add-opens=java.base/java.io=ALL-UNNAMED", "--enable-native-access=ALL-UNNAMED")
            }
        }
    }
    packaging { resources.excludes += setOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/LICENSE*", "META-INF/NOTICE*") }
    buildFeatures {
        compose = true
    }
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

jacoco { toolVersion = "0.8.15" }
tasks.withType<Test>().configureEach {
    extensions.configure<JacocoTaskExtension> {
        isIncludeNoLocationClasses = true
        includes = listOf("com.example.meshlightcontroller.*")
        excludes = listOf("jdk.internal.*")
    }
}
tasks.register<JacocoReport>("jacocoDebugReport") {
    dependsOn("testDebugUnitTest")
    executionData.setFrom(layout.buildDirectory.file("jacoco/testDebugUnitTest.exec"))
    sourceDirectories.setFrom(files("src/main/java"))
    classDirectories.setFrom(
        fileTree(layout.buildDirectory.dir("intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes")) {
            exclude("**/ui/**", "**/*_Factory*", "**/Hilt_*", "**/*_Impl*", "**/*_MembersInjector*")
        }
    )
    reports { xml.required = true; html.required = true }
}

dependencies {
    implementation(libs.nordic.mesh)
    implementation(libs.nordic.ble)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.adaptive.navigation3)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.bouncycastle)
    implementation(libs.gson)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.hilt.testing)
    kspAndroidTest(libs.hilt.compiler)
    screenshotTestImplementation(libs.screenshot.validation)
    screenshotTestImplementation(libs.androidx.compose.ui.tooling)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    // Keep the debug APK's JUnit aligned with instrumentation under AGP consistent resolution.
    debugImplementation(libs.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}