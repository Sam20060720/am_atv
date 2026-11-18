import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.instrumentation.InstrumentationScope
import com.android.build.api.artifact.SingleArtifact
import dev.amatv.build.HomePrefetchBudgetVisitorFactory
import dev.amatv.build.MusicKitDeveloperToken
import dev.amatv.build.MusicKitSongIdVisitorFactory
import dev.amatv.build.MusicKitStackMapFrames
import dev.amatv.build.VerifyMusicKitSdkTask
import dev.amatv.build.StripFontAssetsTask
import java.util.Properties
import java.util.Base64
import java.time.Instant

val localProperties = Properties().apply {
    val propertiesFile = rootProject.file("local.properties")
    if (propertiesFile.exists()) propertiesFile.inputStream().use(::load)
}

fun localSetting(name: String, fallback: String = ""): String =
    providers.gradleProperty(name).orNull
        ?: localProperties.getProperty(name)
        ?: System.getenv(name)
        ?: fallback

fun quotedBuildConfig(value: String): String =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

val developerTokenFile = localSetting("AM_DEVELOPER_TOKEN_FILE").takeIf(String::isNotBlank)?.let(::file)
val bundledDeveloperToken = developerTokenFile?.takeIf { it.isFile }?.readText()?.trim()
    ?: localSetting("AM_DEVELOPER_TOKEN")

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.amatv"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.amatv"
        minSdk = 23
        targetSdk = 36
        versionCode = 16
        versionName = "0.6.3"

        buildConfigField("boolean", "BUNDLED_FONTS", "true")
        val nativePlayerBackdrop = providers.gradleProperty("amatv.nativeBackdrop").getOrElse("true")
        require(nativePlayerBackdrop in setOf("true", "false")) { "amatv.nativeBackdrop must be true or false" }
        buildConfigField("boolean", "NATIVE_PLAYER_BACKDROP", nativePlayerBackdrop)

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField(
            "String",
            "MUSICKIT_DEVELOPER_TOKEN",
            quotedBuildConfig(bundledDeveloperToken),
        )
        buildConfigField(
            "String",
            "MUSICKIT_TOKEN_ENDPOINT",
            quotedBuildConfig(localSetting("AM_TOKEN_ENDPOINT")),
        )
        buildConfigField(
            "String",
            "APPLE_MUSIC_GUEST_STOREFRONT",
            quotedBuildConfig(localSetting("AM_STOREFRONT", "tw")),
        )
        buildConfigField(
            "String",
            "YTM_OAUTH_CLIENT_ID",
            quotedBuildConfig(localSetting("YTM_OAUTH_CLIENT_ID")),
        )
        buildConfigField(
            "String",
            "YTM_OAUTH_CLIENT_SECRET",
            quotedBuildConfig(localSetting("YTM_OAUTH_CLIENT_SECRET")),
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        create("standalone") {
            initWith(getByName("release"))
            // Same local signing identity lets the TV update without losing its account.
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
            buildConfigField("String", "MUSICKIT_TOKEN_ENDPOINT", "\"\"")
        }
        create("standaloneSystem") {
            initWith(getByName("standalone"))
            matchingFallbacks += listOf("standalone", "release")
            buildConfigField("boolean", "BUNDLED_FONTS", "false")
        }
        create("benchmark") {
            initWith(getByName("standalone"))
            matchingFallbacks += listOf("standalone", "release")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

// Preserve 64-bit Apple catalog song IDs in the legacy SDK's JSON metadata parser.
// The downloaded official AAR is validated and stays unmodified on disk.
val verifyMusicKitSdkCompatibility = tasks.register<VerifyMusicKitSdkTask>("verifyMusicKitSdkCompatibility") {
    sdkAars.from(fileTree("libs") { include("mediaplayback*.aar") })
}
tasks.named("preBuild").configure { dependsOn(verifyMusicKitSdkCompatibility) }

val musicKitFrameSdkAars = fileTree("libs") { include("mediaplayback*.aar", "musickitauth*.aar") }
val musicKitFrameRepairClasses = providers.provider {
    MusicKitStackMapFrames.missingFrameClasses(musicKitFrameSdkAars.files)
}

val distApks = tasks.register<Copy>("distApks") {
    group = "distribution"
    description = "Build and copy the standard and bundled-font APKs to dist/."
    into(rootProject.layout.projectDirectory.dir("dist"))
}

androidComponents {
    beforeVariants(selector().withBuildType("standaloneSystem")) { variant ->
        variant.minSdk = 24
    }
    onVariants(selector().all()) { variant ->
        if (variant.name in setOf("standalone", "standaloneSystem")) {
            val version = variant.outputs.single().versionName
            val suffix = if (variant.name == "standalone") "-font" else ""
            distApks.configure {
                inputs.property("${variant.name}Version", version)
                from(variant.artifacts.get(SingleArtifact.APK)) {
                    include("*.apk")
                    rename { "AMATV-${version.get()}$suffix.apk" }
                }
            }
        }
        if (variant.name == "standaloneSystem") {
            // The auth SDK carries an unused Roboto asset even with browser-only
            // sign-in. This variant must contain no font binaries at all.
            val stripFonts = tasks.register<StripFontAssetsTask>("stripStandaloneSystemFontAssets")
            variant.artifacts.use(stripFonts).wiredWithDirectories(
                StripFontAssetsTask::getInputDirectory,
                StripFontAssetsTask::getOutputDirectory,
            ).toTransform(SingleArtifact.ASSETS)
        }
        variant.instrumentation.transformClassesWith(
            MusicKitSongIdVisitorFactory::class.java,
            InstrumentationScope.ALL,
        ) { parameters ->
            parameters.frameRepairClasses.set(musicKitFrameRepairClasses)
        }
        // Backport a finite idle-prefetch deadline for the pinned Foundation SDK.
        variant.instrumentation.transformClassesWith(
            HomePrefetchBudgetVisitorFactory::class.java,
            InstrumentationScope.ALL,
        ) { }
        variant.instrumentation.setAsmFramesComputationMode(
            FramesComputationMode.COMPUTE_FRAMES_FOR_INSTRUMENTED_CLASSES,
        )
    }
}

tasks.register("generateMusicKitToken") {
    group = "distribution"
    description = "Sign a MusicKit developer token using the configured Apple key."
    val teamId = localSetting("APPLE_TEAM_ID")
    val keyId = localSetting("APPLE_MUSIC_KEY_ID")
    val privateKey = localSetting("APPLE_MUSIC_PRIVATE_KEY_PATH").takeIf(String::isNotBlank)?.let(::file)
    doLast {
        val key = checkNotNull(privateKey) { "Set APPLE_MUSIC_PRIVATE_KEY_PATH." }
        val output = checkNotNull(developerTokenFile) { "Set AM_DEVELOPER_TOKEN_FILE." }
        val expiry = MusicKitDeveloperToken.write(key.toPath(), output.toPath(), teamId, keyId)
        logger.lifecycle("Wrote MusicKit developer token (expires $expiry).")
    }
}

val verifyStandaloneCredential = tasks.register("verifyStandaloneCredential") {
    doLast {
        check(bundledDeveloperToken.isNotBlank()) {
            "Set AM_DEVELOPER_TOKEN_FILE to a signed developer token before building a standalone APK."
        }
        val parts = bundledDeveloperToken.split('.')
        check(parts.size == 3) { "Standalone developer token is not a JWT." }
        val header = String(Base64.getUrlDecoder().decode(parts[0]), Charsets.UTF_8)
        val payload = String(Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8)
        check(header.contains("ES256")) { "Standalone developer token must use ES256." }
        val expiry = Regex("\"exp\"\\s*:\\s*(\\d+)").find(payload)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        check(expiry > Instant.now().epochSecond + 86_400L) {
            "The standalone credential must remain valid for at least one day. Export a fresh token."
        }
        logger.lifecycle("Standalone developer credential expires ${Instant.ofEpochSecond(expiry)}")
    }
}
tasks.matching { it.name in setOf("preStandaloneBuild", "preStandaloneSystemBuild", "preBenchmarkBuild") }
    .configureEach { dependsOn(verifyStandaloneCredential) }

val legacyIcuSource = configurations.create("legacyIcuSource") {
    isCanBeConsumed = false
    isTransitive = false
}

// Android 6 uses ICU4J only for UScript.getCode(Locale) and getShortName.
// Keep the locale, alias and property data used by those lookups in ICU4J 76.1.
// JVM tests use this same JAR so missing runtime data also fails in tests.
val legacyIcuScripts = tasks.register<Jar>("packageLegacyIcuScripts") {
    archiveBaseName.set("legacy-icu-scripts")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    from(legacyIcuSource.elements.map { zipTree(it.single().asFile) }) {
        include("com/ibm/icu/**/*.class")
        val data = "com/ibm/icu/impl/data/icudata"
        include(
            "$data/keyTypeData.res",
            "$data/langInfo.res",
            "$data/metadata.res",
            "$data/pnames.icu",
            "$data/pool.res",
            "$data/root.res",
            "$data/supplementalData.res",
            "$data/timezoneTypes.res",
        )
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.webkit:webkit:1.14.0")
    // Only API 23 needs the legacy script lookup. Keep it available to the
    // compiler and JVM tests, but outside the Android 7+ runtime classpath.
    add(legacyIcuSource.name, "com.ibm.icu:icu4j:76.1")
    val legacyIcu = files(legacyIcuScripts)
    compileOnly(legacyIcu)
    testImplementation(legacyIcu)
    listOf("debug", "release", "standalone", "benchmark").forEach {
        add("${it}Implementation", legacyIcu)
    }
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("androidx.compose.foundation:foundation") {
        // The guarded idle-prefetch bytecode backport matches this exact SDK.
        // Remove the backport and this pin when adopting the upstream fix.
        version { strictly("1.11.4") }
    }
    constraints {
        implementation("androidx.compose.foundation:foundation-android") {
            version { strictly("1.11.4") }
            because("The idle-prefetch backport validates Foundation 1.11.4 bytecode")
        }
    }
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.tv:tv-material:1.1.0")
    implementation("androidx.media3:media3-exoplayer:1.11.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.0")
    implementation("io.coil-kt.coil3:coil-compose:3.3.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.3.0")
    implementation("com.google.zxing:core:3.5.4")

    // Composition names in Perfetto only for the profileable benchmark APK.
    // The native binary lets the shell TracingReceiver load tracing without
    // side-loading libraries; its version matches runtime-tracing's bridge.
    add("benchmarkImplementation", "androidx.compose.runtime:runtime-tracing")
    add("benchmarkImplementation", "androidx.tracing:tracing-perfetto-binary:1.0.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")

}
