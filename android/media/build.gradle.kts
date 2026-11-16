plugins { id("com.android.library"); kotlin("android") }
val media3Version = "1.11.1"
val cameraXVersion = providers.gradleProperty("cameraXVersion").getOrElse("1.6.2")
android {
    namespace = "it.panebarco.creator.media"
    compileSdk = 36
    buildFeatures { buildConfig = true }
    defaultConfig {
        minSdk = 31
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "CAMERA_X_VERSION", "\"$cameraXVersion\"")
        buildConfigField("String", "MEDIA3_VERSION", "\"$media3Version\"")
    }
    testOptions { targetSdk = 36 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    api(project(":domain"))
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    listOf("camera-core", "camera-camera2", "camera-lifecycle", "camera-view", "camera-video").forEach { api("androidx.camera:$it:$cameraXVersion") }
    listOf("media3-common", "media3-exoplayer", "media3-transformer", "media3-effect").forEach { api("androidx.media3:$it:$media3Version") }
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}

android.sourceSets.getByName("androidTest").assets.srcDir(rootProject.layout.buildDirectory.dir("generated/androidTestAssets"))
tasks.matching { it.name == "mergeDebugAndroidTestAssets" || it.name.endsWith("AndroidTestLintModel") || (it.name.startsWith("lintAnalyze") && it.name.endsWith("AndroidTest")) }.configureEach { dependsOn(rootProject.tasks.named("generateMediaFixtures")) }
