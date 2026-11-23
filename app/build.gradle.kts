plugins {
  alias(libs.plugins.android.application)
}

android {
  namespace = "com.jaspermeller.autoplus"
  compileSdk = 37

  defaultConfig {
    applicationId = "com.jaspermeller.autoplus"
    minSdk = 33
    targetSdk = 36
    versionCode = 1
    versionName = "0.1"
  }

  buildTypes {
    release {
      isMinifyEnabled = false
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  buildFeatures {
    buildConfig = true
  }

  testOptions {
    unitTests {
      // Robolectric needs the merged manifest, resources and the bundled typeface.
      isIncludeAndroidResources = true
      all { test ->
        // Where the board renders its PNGs, so a person (or CI) can look at them.
        test.systemProperty("autoplus.renderDir", layout.buildDirectory.dir("renders").get().asFile.path)
      }
    }
  }
}

kotlin {
  jvmToolchain(21)
}

dependencies {
  implementation(libs.car.app)
  implementation(libs.car.app.projected)

  testImplementation(libs.junit)
  testImplementation(libs.robolectric)
  testImplementation(libs.androidx.test.core)
  testImplementation(libs.car.app.testing)
}
