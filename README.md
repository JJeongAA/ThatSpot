# That Spot

자주 다니는 길에서 급정거했던 곳, 길을 놓쳤던 갈림길을 기억해 뒀다가 다음에 그 근처에 가면 미리 말로 알려 주는 Pleos Connect 앱입니다.

- 팀: Spider
- APK: [`apk/CP0_ThatSpot_release.apk`](apk/Spider_ThatSpot_release.apk)

## 만든 이유

매일 다니는 출근길에도 늘 움찔하는 골목 입구가 있고, 갈 때마다 헷갈리는 갈림길이 있습니다. 그런데 그때 한 번 놀라고 나면 다음 날엔 잊어버립니다.

내비가 알려 주는 사고다발구역은 다른 사람들 통계라서 내가 어디서 놀랐는지는 모릅니다. 마이현대나 티맵 운전점수도 급감속을 세긴 하지만, 주행이 끝난 뒤 점수로 보여 줄 뿐 그 자리에 다시 갈 때 말해 주지는 않습니다.

That Spot은 반대로 갑니다. 점수를 매기지 않고, 내가 놀랐던 그 자리에 다시 가까워질 때 알려 줍니다.

참고로 급정거가 잦은 구간일수록 사고도 많다는 연구가 있습니다 (NJIT, *Vehicles*, 2026. 0.2g 이상 감속 기준, 급정거 1회 증가당 예상 사고 약 1% 증가).

## 동작

1. 주행 중 급정거(0.3g 이상, 시속 15km 이상 감속, 브레이크 밟음)나 급한 핸들 조작을 감지하면 그 위치와 진행 방향을 저장합니다. 누를 버튼은 없습니다.
2. 50m 안, 같은 방향으로 들어온 기록은 한 지점으로 묶습니다. 서로 다른 날 두 번 생기면 알릴 곳이 됩니다. 한 번은 우연일 수 있어서입니다.
3. 그 지점에 같은 방향으로 다가가면 10초쯤 앞(150~400m)에서 한 번 말합니다.
   "150미터 앞, 어제도 급정거하신 곳이에요. 모두 두 번. 골목에서 차가 나올 수 있어요."
4. 주차하면 왜 그랬는지 묻습니다. 버튼으로 고르거나 말로 답할 수 있고, 말로 남긴 내용은 다음 알림 때 그대로 들려줍니다.
5. 알림을 듣고 세 번 연속 부드럽게 지나가면 그 자리는 더 이상 말하지 않습니다. 다시 급정거하면 다시 알립니다.

**길을 놓친 곳**도 같은 방식입니다. 내비 안내 중에 경로 재탐색이 일어나면(NaviHelper `onReRouteEnded`) 갈림길을 놓쳤다고 봅니다. 재탐색은 갈림길을 지나고 나서 오기 때문에 지나온 길을 200m 되짚은 곳을 저장합니다. 차선을 바꿀 시간이 필요해서 이쪽은 20초 앞(300~700m)에서 알립니다.

몇 가지 예외도 있습니다.
- 반대 차로로 같은 곳을 지날 때는 알리지 않습니다.
- 한 번 주행하는 동안 같은 곳에서 여러 번 급정거한 건(정체 등) 한 번으로 셉니다.
- 이유로 "옆 차가 끼어들었어요", "앞차가 갑자기 섰어요"를 고르면 그때만의 상황일 수 있어서, 다음 주차 때 계속 알릴지 한 번 물어봅니다.

## 실행

Android Studio에서 이 폴더를 열고 Pleos Connect Emulator에서 Run 하면 됩니다.

Android Studio에 들어 있는 JDK가 25면 Gradle 8.10이 실행되지 않습니다 (에러 메시지가 `25.0.4` 한 줄만 나옴). **Settings > Build Tools > Gradle > Gradle JDK > Download JDK**에서 17을 받아 지정하세요.

### 시연 모드

홈 화면의 **시연 시작**을 누르면 지난 10일치 출근길 기록을 만든 뒤 오늘 출근길을 8배속으로 재생합니다.

- 1.2km 지점: 골목 출구 150m 앞에서 미리 알림 → 부드럽게 지나감
- 2.95km 지점: 갈림길 300m 앞에서 "어제도 길을 놓치신 곳이에요" → 이번엔 안 놓침
- 3.5km 지점: 끼어들기로 급정거. 4일 전에 이어 두 번째라 알릴 곳이 됨
- 주차: 이유를 고르면 정리 화면이 나옴
- 다음 날: 골목 출구를 세 번째로 부드럽게 지나면 "이제 여기선 조용히 할게요"

시연 기록은 저장되지 않습니다.

### 에뮬레이터에서 직접 달려 보기

1. 앱 홈 화면에서 **에뮬레이터 경로 시험**을 켭니다. 위치 변화로 속도를 계산하는 모드입니다.
2. Virtual Console(`adb forward tcp:8088 tcp:8088` 후 `http://127.0.0.1:8088/`)에서 기어를 D로 둡니다.
3. 에뮬레이터 Extended Controls > Location > Routes에서 `emulator/commute_hard_brake.gpx`를 재생합니다(약 7분). 1분 50초쯤 1.2km 지점에서 급정거합니다.
4. 끝나면 기어를 P로 바꾸고 이유를 고릅니다.
5. 3~4를 한 번 더 하면 알릴 곳이 됩니다.
6. `emulator/commute_gentle.gpx`를 재생하면 1.05km쯤에서 알림이 나옵니다.

길을 놓친 곳 시험: 에뮬레이터 내비가 재탐색 신호를 보내는지는 확인하지 못해서, 같은 신호를 adb로 보낼 수 있게 해 뒀습니다. GPX 재생 5분 8초쯤에 실행합니다.

```
adb shell am broadcast -a com.thatspot.app.SIMULATE_REROUTE
```

### Pleos SDK가 붙었는지 확인

Logcat에서 아래 태그로 검색합니다. 홈 화면 오른쪽 센서 목록에서도 보입니다.

| 태그 | 정상일 때 |
| --- | --- |
| `PleosVehicleExtras` | Vehicle SDK 연결됨 |
| `PleosFusedLocation` | 위치 서비스 연결 (안 되면 안드로이드 GPS로 대체) |
| `PleosSpeech` | Pleos TTS / STT 준비됨 |
| `PleosNavi` | NaviHelper 연결됨, 재탐색 시 `경로 재탐색` |

## 테스트 환경과 필요한 설정

| 항목 | 내용 |
| --- | --- |
| 개발 도구 | Android Studio. Gradle을 실행하는 JDK는 17 |
| 빌드 도구 | Gradle 8.10.2, Android Gradle Plugin 8.8.2, Kotlin 2.0.21 (Compose) |
| Android | minSdk 29, compileSdk와 targetSdk 35, `useLibrary("android.car")` |
| Pleos SDK 저장소 | `https://nexus-playground.pleos.ai/repository/maven-releases/` (`settings.gradle.kts`에 등록되어 있고 토큰은 필요 없음) |
| 빌드 스위치 | `gradle.properties`의 `usePleos=true`(Pleos SDK 사용), `useNavi=true`(NaviHelper 사용) |

처음 실행할 때 필요한 설정은 다음과 같습니다.
- Gradle JDK를 17로 지정합니다. Java 25에서는 Gradle 8.10이 실행되지 않습니다.
- 에뮬레이터는 **Cold boot**로 켭니다. Quick boot에서는 차량 값(VHAL)을 읽을 수 없습니다.
- 앱을 처음 켜면 권한 창이 뜹니다. 모두 허용합니다.
- 노트북이 느리면 `gradle.properties`의 `org.gradle.workers.max=2` 줄 앞 `#`을 지웁니다.
- Pleos 에뮬레이터가 아닌 일반 Automotive 에뮬레이터에서는 `usePleos=false`로 두면 시연 모드를 볼 수 있습니다. 이때는 표준 AAOS 속도와 기어, 안드로이드 위치와 음성을 씁니다.

## 개발에 사용한 에뮬레이터

| 항목 | 내용 |
| --- | --- |
| 에뮬레이터 | Pleos Connect Emulator v2.1.0 (Android 14 이미지) |
| 화면 규격 | 2560 x 1440 (`adb shell wm size`로 확인) |
| 부팅 방식 | Cold boot |

## APK 위치

`apk/CP0_ThatSpot_release.apk` 입니다. 서명한 release 빌드이고, Android Studio의 **Build > Generate Signed App Bundle or APK > APK > release**로 만들었습니다.

## 사용한 Pleos SDK

| 용도 | SDK |
| --- | --- |
| 브레이크, 핸들 각도, 깜빡이 | Vehicle 2.3.2 |
| 위치, 방향, 근접 알림 | FusedLocation 1.1.3 |
| 음성 안내 | TextToSpeech 3.3.1 |
| 음성으로 이유 답하기 | SpeechToText 3.3.1 |
| 경로 재탐색 (길 놓침) | NaviHelper 3.0.5 |
| 속도, 기어 | 표준 AAOS `PERF_VEHICLE_SPEED`, `GEAR_SELECTION` |
| 주행 중 화면 제한 | `CarUxRestrictionsManager` |

Pleos SDK는 실행 중에 불러오고, 실패하면 안드로이드 기본 기능(GPS, TextToSpeech, SpeechRecognizer)으로 넘어갑니다. 그래서 SDK가 없는 환경에서도 앱은 멈추지 않습니다. 브레이크 값을 못 읽으면 속도만으로 급정거를 판단합니다.

NaviHelper 리스너는 콜백이 25개가 넘어서 `onReRouteEnded`만 받도록 `Proxy`로 만들었습니다 (`PleosNavi.kt`).

## 기준값

`core/Detector.kt`의 `Config`에 모여 있습니다. 문헌과 시연 데이터로 정한 값이라 실차로 다시 맞춰야 합니다.

| 항목 | 값 |
| --- | --- |
| 급정거 | 1초 평균 0.3g 이상, 시속 20km 이상에서 15km/h 이상 감속, 브레이크 밟음 |
| 같은 지점 | 반경 50m, 진행 방향 차이 50도 이내 (길 놓침은 100m) |
| 알릴 곳 | 서로 다른 주행에서 2번 |
| 알림 거리 | 급정거 10초 앞 150~400m, 길 놓침 20초 앞 300~700m |
| 알림 횟수 | 지점당 10분에 한 번, 주행당 최대 6번 |
| 조용해지는 조건 | 알림 뒤 연속 3번 부드럽게 통과 |

## 폴더 구조

```
app/src/main/java/com/thatspot/
  core/        판단 로직 전부. 안드로이드 코드 없이 순수 Kotlin이라 단위 테스트로 검증
  app/         화면(Compose), 입력, 음성, 저장, 포그라운드 서비스
app/src/pleos/       Pleos SDK 연동 (usePleos=true일 때만 빌드)
app/src/pleosNavi/   NaviHelper 연동 (useNavi=true일 때만 빌드)
app/src/test/        core 단위 테스트
emulator/            에뮬레이터용 GPX 경로
apk/                 제출용 APK
```

테스트는 `./gradlew test`로 돌립니다. 시연 시나리오 전체를 엔진에 넣어서 알림 시점, 방향 구분, 조용해지는 조건, 길 놓침 기록 위치 등을 확인합니다.
