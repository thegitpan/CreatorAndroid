import java.util.Properties

plugins { id("com.android.application"); kotlin("android"); id("org.jetbrains.kotlin.plugin.compose") }
android {
    namespace = "it.panebarco.creator"
    compileSdk = 36
    defaultConfig {
        applicationId = "it.panebarco.creator"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        getByName("debug") { applicationIdSuffix = ".debug"; versionNameSuffix = "-debug"; resValue("string", "app_name", "Creator · test") }
        getByName("release") { isMinifyEnabled = false; resValue("string", "app_name", "Creator") }
    }
    val signingPath = providers.environmentVariable("CREATOR_SIGNING_PROPERTIES").orNull
    if (signingPath != null) {
        val propertiesFile = file(signingPath)
        require(propertiesFile.isFile) { "CREATOR_SIGNING_PROPERTIES must point to an existing external properties file" }
        val properties = Properties().apply { propertiesFile.inputStream().use(::load) }
        val required = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
        require(required.all { !properties.getProperty(it).isNullOrBlank() }) { "Signing properties are incomplete" }
        val keyStore = file(properties.getProperty("storeFile"))
        require(keyStore.isFile) { "The external signing keystore does not exist" }
        val personal = signingConfigs.create("personal") {
            storeFile = keyStore
            storePassword = properties.getProperty("storePassword")
            keyAlias = properties.getProperty("keyAlias")
            keyPassword = properties.getProperty("keyPassword")
        }
        buildTypes.getByName("release").signingConfig = personal
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
dependencies {
    implementation(project(":domain")); implementation(project(":data")); implementation(project(":media"))
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.media3:media3-ui:1.11.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.10.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-intents:3.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

android.sourceSets.getByName("androidTest").assets.srcDir(rootProject.layout.buildDirectory.dir("generated/androidTestAssets"))
tasks.matching { it.name == "mergeDebugAndroidTestAssets" || it.name.endsWith("AndroidTestLintModel") || (it.name.startsWith("lintAnalyze") && it.name.endsWith("AndroidTest")) }.configureEach { dependsOn(rootProject.tasks.named("generateMediaFixtures")) }
