plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// gradle.properties 의 usePleos. true 면 src/pleos 의 Pleos SDK 연동 코드를 함께 빌드한다.
val usePleos = (findProperty("usePleos") as String?)?.toBoolean() == true
// gradle.properties 의 useNavi. true 면 NaviHelper(길을 놓친 곳) 를 함께 빌드한다. usePleos 가 true 일 때만 의미가 있다.
val useNavi = usePleos && (findProperty("useNavi") as String?)?.toBoolean() != false

android {
    namespace = "com.thatspot.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.thatspot.app"
        minSdk = 29 // Pleos 배포 요건(28 이상) 충족
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("boolean", "USE_PLEOS", usePleos.toString())
    }

    // Android Automotive 표준 Car API (CarPropertyManager, CarUxRestrictionsManager)
    useLibrary("android.car")

    sourceSets {
        getByName("main") {
            if (usePleos) java.srcDir("src/pleos/java")
            if (useNavi) java.srcDir("src/pleosNavi/java")
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
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    if (usePleos) {
        // 버전: Pleos 공식 샘플(vehicle, fusedlocation) 과 Pleos 저장소의 최신 release 값
        implementation("ai.pleos.playground:Vehicle:2.3.2")
        implementation("ai.pleos.playground:FusedLocation:1.1.3")
        implementation("ai.pleos.playground:TextToSpeech:3.3.1")
        implementation("ai.pleos.playground:SpeechToText:3.3.1")
    }
    if (useNavi) {
        // 내비 경로 재탐색 신호 (길을 놓친 곳). 버전은 Pleos 공식 navi 샘플과 같다
        implementation("ai.pleos.playground:NaviHelper:3.0.5")
    }

    testImplementation("junit:junit:4.13.2")
}
