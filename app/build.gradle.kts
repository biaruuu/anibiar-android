plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Build-time settings come from environment variables (the GitHub repository secrets), all optional.
fun env(name: String, fallback: String = ""): String = (System.getenv(name) ?: "").trim().ifEmpty { fallback }
fun str(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "io.anibiar.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.anibiar.app"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toIntOrNull() ?: 1
        versionName = "2.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")

        buildConfigField("String", "SITE_URL", str(env("SITE_URL", "https://anibiartv.vercel.app")))
        buildConfigField("String", "SUPABASE_URL", str(env("SUPABASE_URL", "https://zrlzsrakfzfayegvvisg.supabase.co")))
        buildConfigField("String", "SUPABASE_KEY", str(env("SUPABASE_KEY", "sb_publishable_PIaz0dn6UgqXTLwm80fBfw_hImeudBy")))
        buildConfigField("String", "UPSTASH_URL", str(env("UPSTASH_REDIS_REST_URL")))
        buildConfigField("String", "UPSTASH_TOKEN", str(env("UPSTASH_REDIS_REST_TOKEN")))
        buildConfigField("String", "TMDB_KEY", str(env("TMDB_KEY")))
    }

    // One fixed key, so every new build installs over the previous one without uninstalling.
    signingConfigs {
        create("anibiar") {
            storeFile = rootProject.file("keystore/anibiar.jks")
            storePassword = "anibiar123"
            keyAlias = "anibiar"
            keyPassword = "anibiar123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("anibiar")
        }
        debug {
            signingConfig = signingConfigs.getByName("anibiar")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.browser:browser:1.8.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.7.0")

    val media3 = "1.5.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3")
    implementation("androidx.media3:media3-datasource-cronet:$media3")
    implementation("androidx.media3:media3-transformer:$media3")
    implementation("androidx.media3:media3-effect:$media3")
    implementation("androidx.media3:media3-common:$media3")
    implementation("com.google.android.gms:play-services-cronet:18.1.0")
    implementation("com.google.net.cronet:cronet-okhttp:0.1.0")
    implementation("org.mozilla:rhino:1.7.15")
    implementation("org.jsoup:jsoup:1.17.2")
    implementation("androidx.media3:media3-ui:$media3")
}
