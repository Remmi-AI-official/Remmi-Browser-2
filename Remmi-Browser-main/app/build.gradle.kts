plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.roborazzi)
}

android {
  namespace = "com.remmi.browser"
  compileSdk = 36

  defaultConfig {
    applicationId = "com.remmi.browser"
    minSdk = 26
    targetSdk = 36
    versionCode = 3
    versionName = "1.0.2"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    ndk {
      abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a", "x86_64"))
    }
  }

  val releaseTaskRequested = gradle.startParameter.taskNames.any {
    it.contains("Release", ignoreCase = true)
  }

  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH")
      val storePassword = System.getenv("STORE_PASSWORD")
      val keyAlias = System.getenv("KEY_ALIAS")
      val keyPassword = System.getenv("KEY_PASSWORD")

      if (!keystorePath.isNullOrBlank() && !storePassword.isNullOrBlank() &&
          !keyAlias.isNullOrBlank() && !keyPassword.isNullOrBlank()) {
        val ksFile = file(keystorePath)
        require(ksFile.isFile) { "KEYSTORE_PATH does not point to an existing release keystore: $keystorePath" }
        storeFile = ksFile
        this.storePassword = storePassword
        this.keyAlias = keyAlias
        this.keyPassword = keyPassword
      } else if (releaseTaskRequested) {
        throw GradleException(
          "Release builds are intentionally fail-closed. Provide KEYSTORE_PATH, STORE_PASSWORD, KEY_ALIAS and KEY_PASSWORD."
        )
      }
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("release")
    }
  }

  val isBuildingBundle = gradle.startParameter.taskNames.any { it.contains("bundle", ignoreCase = true) }
  splits {
    abi {
      isEnable = !isBuildingBundle
      reset()
      include("arm64-v8a", "armeabi-v7a", "x86_64")
      isUniversalApk = true
    }
  }

  packaging {
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
    jniLibs {
      useLegacyPackaging = true
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  kotlin {
    compilerOptions {
      jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions {
    unitTests {
      isIncludeAndroidResources = true
      all {
        it.maxHeapSize = "2g"
        it.systemProperty("robolectric.dependency.repo.url", "https://repo1.maven.org/maven2")
        it.systemProperty("robolectric.dependency.repo.id", "Central")
        it.systemProperty("http.agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
        it.systemProperty("java.awt.headless", "true")
        it.testLogging {
          events("passed", "skipped", "failed", "standardError")
          showExceptions = true
          showCauses = true
          showStackTraces = true
        }
      }
    }
  }
  lint {
    checkReleaseBuilds = true
    abortOnError = true
    disable += listOf("InvalidFragmentVersionForActivityResult")
  }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
}

// This makes it easy to add them back in the future if needed.
dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.fragment.ktx)
  // implementation(libs.accompanist.permissions)
  // GeckoView & Tor dependencies (Pinned deterministic versions)
  implementation("org.mozilla.geckoview:geckoview:154.0.20260824154132")
  implementation("info.guardianproject:tor-android:0.4.9.11")
  implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")
  implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")

  implementation("androidx.compose.ui:ui")
  implementation("androidx.compose.ui:ui-graphics")
  implementation("androidx.compose.foundation:foundation")
  implementation("androidx.compose.animation:animation")
  implementation("androidx.compose.material3:material3")
  implementation("androidx.compose.material:material-icons-extended")
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.biometric)
  // implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  // implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.bouncycastle)
  implementation(libs.sqlcipher)
  implementation(libs.androidx.sqlite.ktx)
  implementation(libs.coil.compose)
  implementation("io.coil-kt.coil3:coil-network-okhttp:${libs.versions.coilCompose.get()}")
  implementation(libs.jsoup)
  implementation(libs.converter.moshi)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.logging.interceptor)
  implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  implementation("info.guardianproject:jtorctl:0.4.5.7")
  // implementation(libs.play.services.location)
  implementation(libs.retrofit)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.roborazzi.junit.rule)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
  "ksp"(libs.moshi.kotlin.codegen)
}

tasks.matching { it.name.contains("AarMetadata") }.configureEach {
  enabled = false
}
