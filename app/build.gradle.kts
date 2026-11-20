import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// ---------------------------------------------------------------------------------------------
// Native engine: cross-compiles third_party/kinetic (crates/kinetic-android) for arm64-v8a and
// packages libkinetic_android.so. Needs rustup + the aarch64-linux-android target + the NDK.
// ---------------------------------------------------------------------------------------------
val kineticDir = rootProject.file("third_party/kinetic")
val kineticJni = layout.buildDirectory.dir("generated/kinetic-jni")
val ndkRoot: String? =
    System.getenv("ANDROID_NDK_HOME")
        ?: System.getenv("ANDROID_NDK_ROOT")
        ?: System.getenv("ANDROID_HOME")?.let { sdk ->
            File(sdk, "ndk").listFiles()?.filter { it.isDirectory }?.maxByOrNull { it.name }?.absolutePath
        }

val buildKinetic =
    tasks.register<Exec>("buildKinetic") {
        group = "kinetic"
        description = "Cross-compiles the Kinetic engine (kinetic-android) for arm64-v8a."
        inputs.dir(kineticDir.resolve("crates"))
        inputs.file(kineticDir.resolve("Cargo.lock"))
        outputs.dir(kineticJni)
        workingDir = kineticDir
        doFirst {
            val ndk = ndkRoot ?: error("Set ANDROID_NDK_HOME (NDK r27 or newer) to build the engine")
            val bin = "$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin"
            environment("CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER", "$bin/aarch64-linux-android29-clang")
            environment("CC_aarch64_linux_android", "$bin/aarch64-linux-android29-clang")
            environment("AR_aarch64_linux_android", "$bin/llvm-ar")
            environment("CARGO_PROFILE_RELEASE_STRIP", "symbols")
            // 16 KB ELF segment alignment: required for Android 15+ devices running 16 KB pages
            // (the Pixel 9 offers this mode) and harmless on 4 KB devices.
            environment("CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS", "-C link-arg=-Wl,-z,max-page-size=16384")
        }
        commandLine(
            "cargo", "build", "--release", "--locked",
            "--target", "aarch64-linux-android", "-p", "kinetic-android",
        )
        doLast {
            val out = kineticJni.get().asFile.resolve("arm64-v8a").apply { mkdirs() }
            kineticDir
                .resolve("target/aarch64-linux-android/release/libkinetic_android.so")
                .copyTo(out.resolve("libkinetic_android.so"), overwrite = true)
        }
    }

android {
    namespace = "com.kineai.local"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kineai.local"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        // The engine exists for arm64-v8a only (Pixel 9). No other ABI is built or advertised.
        ndk { abiFilters += listOf("arm64-v8a") }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val store = System.getenv("KINEAI_STORE_FILE")
            if (store != null && File(store).isFile) {
                storeFile = File(store)
                storePassword = System.getenv("KINEAI_STORE_PASSWORD")
                keyAlias = System.getenv("KINEAI_KEY_ALIAS")
                keyPassword = System.getenv("KINEAI_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig =
                signingConfigs.getByName("release").takeIf { it.storeFile != null }
                    ?: signingConfigs.getByName("debug")
        }
    }

    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        jniLibs { useLegacyPackaging = true }
        resources.excludes +=
            setOf(
                "META-INF/{AL2.0,LGPL2.1}", "META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA",
                "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/INDEX.LIST",
                "META-INF/versions/**", "META-INF/*.kotlin_module", "about.html", "plugin.properties",
            )
    }

    sourceSets.getByName("main").jniLibs.srcDir(kineticJni)
    testOptions { unitTests.isReturnDefaultValues = true }
}

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }.configureEach {
    dependsOn(buildKinetic)
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    // Hugging Face search and downloads only; never used for inference.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Pure-Java Git, runs on the phone with no network unless the user clones or fetches.
    implementation("org.eclipse.jgit:org.eclipse.jgit:5.13.3.202401111512-r")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.json:json:20240303")
}
