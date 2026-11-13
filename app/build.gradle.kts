plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("androidx.room")
}

android {
    namespace = "com.meowgi.entersend"
    compileSdk = 35

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.meowgi.entersend"
        minSdk = 29
        targetSdk = 35
        versionCode = 12
        versionName = "0.3.5"
    }

    buildTypes {
        debug {
            isDebuggable = true
        }
        release {
            // Unsigned: no signingConfig. Minify off so the accessibility service needs no keep rules.
            isMinifyEnabled = false
            signingConfig = null
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets {
        // MigrationTestHelper reads exported schemas as assets; Robolectric only sees the debug
        // variant's merged assets, so the schemas ship inside debug APKs (never release).
        getByName("debug").assets.srcDir("$projectDir/schemas")
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation("androidx.room:room-runtime:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.core:core-ktx:1.15.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("androidx.room:room-testing:2.6.1")
}
