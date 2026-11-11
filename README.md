# 이프EV (If EV)

지금 타는 차의 실제 주행 기록을 전기차에 그대로 겹쳐서 "이 차가 전기차였다면" 충전은 몇 번, 비용은 얼마, 배터리 걱정은 있었는지를 보여 주는 Pleos Connect 앱입니다. 설치 직후에는 차와 내비에 이미 있는 기록으로 **예비 진단**을 바로 보여 주고, 2~4주 동안 자동으로 쌓인 주행으로 **확정 진단**을 만든 뒤 둘을 비교합니다. 현대자동차그룹 차량용 앱 개발 공모전 출품작입니다.

## 왜 필요한가

- 전기차를 사지 않는 이유: 충전 인프라 부족 45.3%, 충전 시간 32.8%, 주행거리 불안 29.3% (2026 소비자 조사 보도).
- 그런데 실제 전기차 보유자 중 충전 불편 때문에 구매를 후회한다는 응답은 0.9%였습니다 (워터 설문 보도).
- 구매 전 불안과 실제 경험 사이의 간극을 **내 차의 실제 기록**으로 메웁니다. 휴대폰 계산기는 월 주행거리와 연비를 사람이 입력해야 하지만, 차는 언제 어디서 출발해 얼마나 달리고 어디에 몇 시간 서 있었는지, 공조를 켰는지를 압니다.

## 무엇을 보여 주나

| 단계 | 시점 | 쓰는 데이터 | 보여 주는 것 |
|---|---|---|---|
| ① 예비 진단 | 설치 직후 | 누적 주행거리, 차대번호 연식(타 온 기간 추정), 평균 연비, 내비 즐겨찾기(집·회사), 최근 목적지 | 연간 주행, 주당 집 충전 횟수(또는 월 급속 횟수), 연료비 절감 범위, 최근 목적지별 "충전 없이 / 급속 n회" (지금·1월) |
| 주행 중 | 매 주행 | 기어, 누적 주행거리, 공조 상태, 위치 | 실제 연료 게이지 옆에 "아이오닉 6였다면 배터리 n%". 조작 없음 |
| ② 실측 기록 | 1~4주 | P에서 P까지의 주행(거리·시간·공조·연비·집/회사/기타) | 실측 일수와 신뢰도, 날짜별 가상 배터리 최저치와 충전, 예비 진단과 달라진 점 |
| ③ 확정 리포트 | 4주 후 | 위 기록 전체 | 30일 기준 충전 횟수, 20% 미만인 날, 절감액, 급속 대기 시간, 예비 진단 vs 실제 표, 판정(집 충전이면 충분 / 급속 생활 필요 / 하이브리드가 더 맞음) |

## 계산 방법과 근거

- **전기차 제원**: 더 뉴 아이오닉 6 환경부 인증(2025년 6월 보도). 롱레인지 2WD 84kWh 상온 568km(도심 609·고속 517), 저온 453km(도심 429·고속 482). 스탠다드 63kWh 상온 442km(476·402), 저온 387km(375·402).
- **속도**: 주행의 평균 속도로 도심(약 31km/h)과 고속(약 78km/h) 인증 주행거리 사이를 보간합니다.
- **기온**: 20℃에서 상온, -7℃에서 저온 인증값(난방 사용)으로 보간합니다. 공조를 끈 추위는 AAA(2019) -6.7℃ 공조 미사용 -12%, 더위는 35℃ 에어컨 -17%·미사용 -4%를 씁니다. 외기온도는 SDK에 없어 기상청 서울 월평균기온 평년값(1991~2020)을 씁니다.
- **충전 습관(가정)**: 집 충전이 되면 집에 4시간 이상 세울 때 50% 아래면 90%까지. 집 충전이 없으면 30% 아래로 세울 때 공공 급속으로 80%까지. 어떤 주행도 10% 아래로 계획하지 않고, 그 전에 급속 충전(80%까지, 실효 120kW, 대기 5분 추가)을 넣습니다.
- **요금**: 휘발유 1,855원/L(오피넷 2026-11-11 전국 평균). 충전은 2026-08-01 공공 요금: 완속 295원, 급속 325.6~393.1원(100~200kW 348.4원). 아파트 민간 완속 상단 350원을 범위 끝으로 씁니다. 충전 손실은 반영하지 않습니다.
- **판정 기준**: 집 충전 없이 생활 급속이 월 4.5회(주 1회 정도) 이하면 "조건부 적합", 넘으면 "하이브리드가 더 맞음". 연구 결과가 아닌 제품 기준이며 화면에도 그대로 표시합니다.
- **목적지 거리**: 집에 세워 둔 상태에서는 내비 `requestTrip`으로 실제 도로 거리를 받고, 그 전에는 직선거리 × 1.3으로 추정한 뒤 화면에 "직선거리로 추정"이라고 표시합니다.

## Pleos SDK 사용

모두 **읽기 권한**입니다. 차량 제어(Vehicle Control) 권한은 쓰지 않으므로 지금 공개된 Vehicle State·NaviHelper만으로 동작합니다.

| 목적 | API | 권한 |
|---|---|---|
| 주행 시작·끝 | `Gear.registerGearSelection`, `getCurrentGearSelectionState` | CAR_DRIVE |
| 주행 거리, 예비 진단 연간 주행 | `Odometer.registerDistanceDriven`, `getCurrentDistanceDriven` | CAR_DRIVE |
| 연료 게이지, 내 차 연비 | `Fuel.registerFuelPercent`, `registerCurrentFuelEconomy`, `registerCumulativeFuelEconomy` | CAR_ENERGY |
| 공조 사용 (전기차 소비 보정) | `HVAC.checkHvacPowerCapabilities`, `registerHvacPowerState` | CAR_CLIMATE |
| 주행 거리 보완 | AAOS `CarPropertyManager` `PERF_VEHICLE_SPEED` (Pleos SDK에 차속이 없어 표준 API 사용) | android.car.permission.CAR_SPEED (런타임) |
| 타 온 기간 추정 | `CarInfo.getVINInfo` (10번째 자리 연식만 계산, 저장 안 함) | CAR_INFO, PRIVATE_CAR_INFO |
| 집·회사 | NaviHelper `getBookmarkInfo` (`BookmarkType.HOME`/`OFFICE`) | NAVI_ROUTE |
| 최근 목적지 | `getRecentDestinationInfo` | NAVI_ROUTE |
| 위치 (집/회사 판별, 거리 보조) | `getCurrentLocationInfo` | NAVI_ROUTE |
| 도로 거리 | `requestTrip` → `onTripInfo` | NAVI_ROUTE |

주행 기록은 포그라운드 서비스(`RecorderService`)가 맡아 내비 등 다른 앱을 보는 동안에도 이어지고, 부팅(시동)과 함께 시작합니다. 주행 중 화면은 게이지 하나뿐이고 조작이 필요 없습니다.

## 개인정보

- 기록은 차 안(앱 저장소)에만 남습니다. 주행마다 거리, 시간, 기온, 공조 비율, 연비, 출발·도착이 집/회사/기타인지만 저장하고 **좌표는 저장하지 않습니다**.
- 차대번호는 연식 계산에만 쓰고 저장하지 않습니다. 앱에는 인터넷 권한이 없습니다.

## 실행 방법

### 준비
- Android Studio, JDK 21 (`gradle/gradle-daemon-jvm.properties`가 로컬 JDK 21을 찾습니다)
- Pleos Connect Emulator v2.1 (API 34). SDK Manager > SDK Update Sites에 `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-arm64-sys-img.xml` (Windows/Linux는 `crp-sys-img.xml`), AVD RAM 4GB, Cold boot

### 빌드·설치
```bash
./gradlew :app:testDebugUnitTest      # 계산 엔진 단위 테스트
./gradlew :app:assembleRelease
adb install -r -g app/build/outputs/apk/release/app-release.apk
```

### 실행
```bash
adb shell am start -n com.ifev.app/.MainActivity                    # 실시간 (차량 신호)
adb shell am start -n com.ifev.app/.MainActivity --es demo day0     # 시연 ① 설치 직후
adb shell am start -n com.ifev.app/.MainActivity --es demo drive    # 시연 ② 출근길 주행 (약 1분)
adb shell am start -n com.ifev.app/.MainActivity --es demo weeks    # 시연 ③ 4주 뒤
```
앱 오른쪽 위 `시연`을 눌러도 됩니다. 에뮬레이터는 실제로 달리지 않고 즐겨찾기도 비어 있으므로, 시연은 SDK가 주는 것과 같은 형태의 가상 데이터(집·회사, 최근 목적지, 누적 주행거리, P→P 주행 4주치)를 같은 엔진에 넣습니다. 시연 중에도 "집 충전 있어요/없어요", 기간, 비교 차종을 바꾸면 결과가 바로 바뀝니다.

### 실시간 신호로 시험하기 (Virtual Console)
```bash
adb forward tcp:8088 tcp:8088
```
브라우저에서 `http://127.0.0.1:8088/` → Emulation Controls에서 Gear를 D로 바꾸면 주행 화면(연료·가상 배터리 게이지)으로, P로 바꾸면 정차 화면으로 돌아옵니다.

## 테스트 환경
- Pleos Connect Emulator v2.1.0 (Android 14, 2560×1440), macOS (Apple Silicon)
- Vehicle SDK 2.3.2, NaviHelper SDK 3.0.5 (`https://nexus-playground.pleos.ai/repository/maven-releases/`)

## 개발하며 확인한 점
- 에뮬레이터 차량은 전기차라 `Fuel`의 연료량·연비 속성이 지원되지 않습니다(`ERROR_NOT_SUPPORTED_PROPERTY`, `property ID is not in carPropertyConfig list`). 그랜저·아반떼처럼 연료차인 실차에서 동작을 확인해야 합니다. 연비를 모를 때는 화면에 "평균 연비를 아직 몰라요"로 표시하고 절감액을 계산하지 않습니다.
- 에뮬레이터의 누적 주행거리(`PERF_ODOMETER`)는 주행해도 0에 머뭅니다. 그래서 주행 거리는 누적 주행거리가 움직이면 그 값을, 아니면 차속 적분과 내비 위치 궤적 중 긴 쪽을 씁니다. Virtual Console에서 D + ACCEL로 달린 6.8km가 이 방식으로 기록되는 것을 확인했습니다 (내비 화면이 앞에 있는 동안에도 기록됨).
- `requestTrip`의 `TripInfo.distance`는 미터로 보고 km로 바꿉니다. 실차에서 단위를 다시 확인해야 합니다.

## 코드 구조
```
app/src/main/java/com/ifev/app/
├── engine/     EvModel(제원·요금·기온), EnergyModel(조건별 전비), BatterySim(주행 재생·충전),
│               Preliminary(예비 진단), Measured(실측 리포트·비교), TripRecorder(신호 → 주행)
├── vehicle/    VehicleGateway(Vehicle SDK), NaviGateway(NaviHelper SDK)
├── data/       LiveRecorder(실시간 기록), Store(설정·주행 저장)
├── scenario/   DemoScenario(시연용 가상 데이터)
├── ui/         Compose 화면, Pleos 색·글자 토큰
├── RecorderService.kt   포그라운드 기록 서비스, 부팅 시작
└── MainActivity.kt, MainViewModel.kt
```

## 시연 자료
- `demo/IfEV_demo.mp4`: 화면 캡처 7장을 이어 붙인 약 58초 슬라이드 영상 (① 설치 직후 → 주행 중 → Virtual Console 실시간 주행·기록 → ③ 4주 뒤 리포트 → 예비 vs 실제 → 집 충전 없음 판정). 에뮬레이터 화면 녹화(`screenrecord`, `adb emu screenrecord`)가 Pleos 에뮬레이터 v2.1에서 빈 파일을 남겨 캡처로 대신했습니다.
- `demo/*.png`: 각 단계 화면 캡처

## APK
- `apk/IfEV_release.apk` (release 빌드, 시연용으로 debug 키 서명). 제출 시 `팀명_이프EV_release.apk`로 이름을 바꿉니다.
