import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy
import java.security.MessageDigest

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.secrets)
  alias(libs.plugins.google.services)
}

fun computeReproducibleSourceFingerprint(projectDir: java.io.File): String {
  val digest = MessageDigest.getInstance("SHA-256")
  val sourceFiles = buildList {
    val mainJava = projectDir.resolve("src/main/java")
    if (mainJava.exists()) {
      addAll(
        mainJava.walkTopDown()
          .filter { it.isFile && it.extension == "kt" }
          .sortedBy { it.relativeTo(projectDir).invariantSeparatorsPath }
          .toList()
      )
    }
    val manifestFile = projectDir.resolve("src/main/AndroidManifest.xml")
    if (manifestFile.isFile) {
      add(manifestFile)
    }
  }
  for (file in sourceFiles) {
    val relPath = file.relativeTo(projectDir).invariantSeparatorsPath
    digest.update(relPath.toByteArray(Charsets.UTF_8))
    digest.update(0)
    val normalizedText = file.readText(Charsets.UTF_8).replace("\r\n", "\n")
    digest.update(normalizedText.toByteArray(Charsets.UTF_8))
    digest.update(0)
  }
  return digest.digest().take(6).joinToString("") { "%02x".format(it) }
}

fun resolveGitRevisionOrNull(rootDir: java.io.File): String? {
  val envRev = System.getenv("SOURCE_REVISION")?.trim()?.takeIf {
    it.isNotEmpty() && it != "SOURCE_REVISION_UNAVAILABLE"
  }
  if (envRev != null) return envRev
  val headFile = rootDir.resolve(".git/HEAD")
  if (!headFile.isFile) return null
  val headContent = headFile.readText(Charsets.UTF_8).trim()
  if (headContent.isEmpty()) return null
  return if (headContent.startsWith("ref:")) {
    val refPath = headContent.removePrefix("ref:").trim()
    val refFile = rootDir.resolve(".git/$refPath")
    if (refFile.isFile) refFile.readText(Charsets.UTF_8).trim().take(12).ifEmpty { null } else null
  } else {
    headContent.take(12)
  }
}

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.kazim.omnivoice.rebuild"
    minSdk = 24
    targetSdk = 36
    versionCode = 7
    versionName = "0.7.0-phase07"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    val sourceFingerprintHex = computeReproducibleSourceFingerprint(projectDir)
    val sourceFingerprint = "sha256-$sourceFingerprintHex"
    val gitRev = resolveGitRevisionOrNull(rootDir)
    val sourceRevision = gitRev ?: "SOURCE_REVISION_UNAVAILABLE"
    val derivedBuildId = if (gitRev != null) {
      "ov-rebuild-p07-$versionName-git-$gitRev-$sourceFingerprint"
    } else {
      "ov-rebuild-p07-$versionName-$sourceFingerprint"
    }

    buildConfigField("String", "SOURCE_BUILD_ID", "\"$derivedBuildId\"")
    buildConfigField("String", "SOURCE_REVISION", "\"$sourceRevision\"")
    buildConfigField("String", "SOURCE_FINGERPRINT", "\"$sourceFingerprint\"")
    buildConfigField("String", "PHASE_NAME", "\"Phase 07 - Stabilization, Timing & Bounded Recovery\"")
    buildConfigField("String", "MODEL_CONFIG_STATUS", "\"LIVE_ADAPTER_CONFIGURED (gemini-3.1-flash-live-preview via Firebase AI Logic v1beta)\"")
    buildConfigField("String", "LIVE_MODEL_ID", "\"gemini-3.1-flash-live-preview\"")
    buildConfigField("String", "LIVE_API_VERSION", "\"v1beta (Firebase AI Logic Managed WebSocket)\"")
  }

  // Do not remove or modify these signingConfigs. They are necessary for building, installing,
  // and updating Android apps in AI Studio.
  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      // Do not remove or modify this signingConfig assignment. It is necessary for Android apps in
      // AI Studio.
      signingConfig = signingConfigs.getByName("release")
    }
    debug {
      // Do not remove or modify this signingConfig assignment. It is necessary for Android apps in
      // AI Studio.
      signingConfig = signingConfigs.getByName("debugConfig")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions {
    unitTests {
      isIncludeAndroidResources = true
      all { testTask ->
        val preCachedRobolectricDir =
          file(
            "/home/appuser/.m2/repository/org/robolectric/android-all-instrumented/16-robolectric-13921718-i7"
          )
        if (preCachedRobolectricDir.exists()) {
          testTask.systemProperty(
            "robolectric.dependency.dir",
            preCachedRobolectricDir.absolutePath,
          )
          testTask.systemProperty("robolectric.offline", "true")
        }
      }
    }
  }
  androidResources {
    noCompress += "onnx"
  }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
}

// Configure the Secrets Gradle Plugin to use .env and .env.example files
// to match the convention used in Web projects.
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
  ignoreList.add("FIREBASE_APPCHECK_DEBUG_TOKEN")
}

googleServices {
    missingGoogleServicesStrategy = MissingGoogleServicesStrategy.ERROR
}

// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(platform(libs.firebase.bom))
  // implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  // implementation(libs.androidx.camera.camera2)
  // implementation(libs.androidx.camera.core)
  // implementation(libs.androidx.camera.lifecycle)
  // implementation(libs.androidx.camera.view)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  // implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  // implementation(libs.androidx.navigation.compose)
  // implementation(libs.androidx.room.ktx)
  // implementation(libs.androidx.room.runtime)
  // implementation(libs.coil.compose)
  // implementation(libs.converter.moshi)
  implementation(libs.firebase.ai)
  // Uncomment to use Firestore:
  // implementation(libs.firebase.firestore)

  // Uncomment ALL FOUR of the following dependencies together to use Firebase Auth and Google
  // Sign-In via Credential Manager:
  // implementation(libs.firebase.auth)
  // implementation(libs.androidx.credentials)
  // implementation(libs.androidx.credentials.play.services)
  // implementation(libs.googleid)
  implementation(libs.firebase.appcheck.playintegrity)
  implementation(libs.firebase.appcheck.recaptcha)
  implementation(libs.firebase.appcheck.debug)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.onnxruntime.android)
  // implementation(libs.logging.interceptor)
  // implementation(libs.moshi.kotlin)
  // implementation(libs.okhttp)
  // implementation(libs.play.services.location)
  // implementation(libs.retrofit)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.onnxruntime.jvm)
  testImplementation(libs.robolectric)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  // "ksp"(libs.androidx.room.compiler)
  // "ksp"(libs.moshi.kotlin.codegen)
}

configurations.matching { it.name.contains("UnitTest") }.configureEach {
  exclude(group = "com.microsoft.onnxruntime", module = "onnxruntime-android")
}
