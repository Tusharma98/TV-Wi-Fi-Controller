import java.util.Properties

plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

// Release signing comes from a private file or environment variables, never from the repository.
//   keystore.properties (git-ignored, in receiver-app/):  storeFile, storePassword, keyAlias, keyPassword
//   or env vars (CI): RELEASE_STORE_FILE, RELEASE_STORE_PASSWORD, RELEASE_KEY_ALIAS, RELEASE_KEY_PASSWORD
// If neither is present, release falls back to the local debug key: fine for sideloading, but Android will
// not install it over an app that was signed with a different key.
val ksProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signing(prop: String, env: String): String? = ksProps.getProperty(prop) ?: System.getenv(env)
val releaseStore = signing("storeFile", "RELEASE_STORE_FILE")

android {
    namespace = "app.tvremote.ime"
    compileSdk = 34
    defaultConfig { applicationId = "app.tvremote.ime"; minSdk = 24; targetSdk = 34; versionCode = 1; versionName = "1.0" }
    signingConfigs {
        if (releaseStore != null) {
            create("release") {
                storeFile = rootProject.file(releaseStore)
                storePassword = signing("storePassword", "RELEASE_STORE_PASSWORD")
                keyAlias = signing("keyAlias", "RELEASE_KEY_ALIAS")
                keyPassword = signing("keyPassword", "RELEASE_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = if (releaseStore != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}

dependencies { testImplementation("junit:junit:4.13.2") }
