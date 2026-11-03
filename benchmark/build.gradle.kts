import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.test")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.amatv.benchmark"
    compileSdk = 36
    defaultConfig {
        minSdk = 28
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["androidx.benchmark.compilation.enabled"] = "false"
        // Optional smaller tester for real TV/ARM emulator runs. Production
        // APK architecture coverage is configured independently in :app.
        providers.gradleProperty("amatv.benchmarkAbis").orNull?.let { requested ->
            val abis = requested.split(',').filter(String::isNotBlank)
            require(abis.isNotEmpty() && abis.all { it in setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64") })
            ndk.abiFilters += abis
        }
    }
    targetProjectPath = ":app"
    // Exercise the actual Android mask implementation in the self-instrumented
    // test process, including pixel coverage, without app-only test commands.
    sourceSets.getByName("main").java.srcDir("../app/src/main/java/dev/amatv/ui/rendering")
    experimentalProperties["android.experimental.self-instrumenting"] = true
    buildTypes {
        create("benchmark") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("benchmark", "standalone", "release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }
androidComponents { beforeVariants(selector().all()) { it.enable = it.buildType == "benchmark" } }

dependencies {
    implementation("androidx.test.ext:junit:1.3.0")
    implementation("androidx.test:runner:1.7.0")
    implementation("androidx.test:rules:1.7.0")
    implementation("androidx.test.uiautomator:uiautomator:2.4.0")
    implementation("androidx.benchmark:benchmark-macro-junit4:1.5.0")
}
