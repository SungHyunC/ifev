# 이프EV (If EV)

**지금 타는 내 차로 4주 동안 '가상 전기차'를 타 보는 Pleos Connect 앱입니다.** 설치 직후에는 차와 내비에 이미 있는 기록으로 **예비 진단**을 바로 보여 줍니다. 목적지를 찍을 때와 정차할 때는 "이 차가 전기차였다면"을 한 문장으로 말해 줍니다. 2~4주 동안 자동으로 쌓인 주행으로 **확정 진단**을 만들어 충전 횟수, 비용, 배터리 걱정을 예비 진단과 비교합니다. 현대자동차그룹 차량용 앱 개발 공모전 출품작입니다.

## 왜 필요한가

- 전기차 구매를 망설이게 하는 요인(신차 구매 예정자 450명, 복수 응답): 충전 인프라 부족 45.3%, 충전 시간 32.8%, 주행거리 불안 29.3% (차봇모빌리티, 2026.2).
- 그런데 실제 전기차 보유자 431명 중 충전 불편 때문에 구매를 후회한다는 응답은 0.9%였습니다 (컨슈머인사이트·워터, 2025.7).
- 구매 전 불안과 실제 경험 사이의 간극을 **내 차의 실제 기록**으로 메웁니다. 휴대폰 계산기는 월 주행거리와 연비를 사람이 입력해야 하지만, 차는 언제 어디서 출발해 얼마나 달리고 어디에 몇 시간 서 있었는지, 공조를 켰는지를 압니다.

## 차 안의 순간

| 순간 | 이프EV | 쓰는 신호 |
|---|---|---|
| 설치 직후 | 입력 없이 예비 진단 | 누적 주행거리, 연식, 평균 연비, 내비 즐겨찾기·최근 목적지 |
| 목적지를 찍을 때 | 음성으로 "강릉 경포해변까지 236킬로미터. 아이오닉 6 롱레인지였다면 도착할 때 배터리 43퍼센트예요."를 말함. 충전이 필요하면 횟수·시간, 1월 기준도 함께 | NaviHelper 목적지·남은 거리·시간 |
| 달리는 동안 | 가상 배터리 하나만 크게. 터치 요소 없음 | 기어, 차속, 공조 |
| P에 넣을 때 | 음성으로 "방금 17킬로미터. 전기 2.7킬로와트시, 집 충전으로 약 792원이에요."를 말하고 주행을 저장함 | 기어, 거리, 공조 비율 |
| 세워 둔 다음 출발 | "세워 둔 사이 집에서 90%까지 충전하고 출발" | P단 시각, 집 위치 |

음성은 Pleos TextToSpeech SDK의 기기 내 모드(ko-KR)로 말합니다. 오른쪽 위 `음성 안내` 버튼으로 끌 수 있습니다. 주행 중에는 화면에 누를 것이 없고, `실시간/시연` 전환과 `음성 안내` 버튼도 숨깁니다.

## 같은 경로, 두 계산 (실시간 검증)

에뮬레이터 차는 전기차(77.4kWh)이고, 현대차 내비는 이 차의 도착 배터리를 직접 계산합니다. 내비에서 목적지를 찍으면 정차 화면 맨 위에 "같은 경로, 두 계산" 카드가 나옵니다. 내비의 도착 배터리와, 이프EV 에너지 모델을 이 차 배터리에 적용한 도착 배터리가 나란히 보입니다. 가상 데이터 없이 이프EV 계산을 현대차 내비와 대조하는 장면입니다.

- 확인한 예(11/12, 에뮬레이터): 강릉 194km, 출발 100%
  - 내비: 도착 64% (6.9 km/kWh)
  - 이프EV: 도착 58% (6.0 km/kWh, 아이오닉 6 롱레인지 전비, 11월 평년 7.5℃, 공조 사용)
  - 이프EV가 6%p 보수적입니다.
- 이프EV는 이 차의 인증 주행거리를 SDK로 읽을 수 없어 고른 비교 차종의 전비를 씁니다. 화면에도 그렇게 표시합니다.

## 실제 주행 기록으로 검증하기

시연의 4주 기록은 가상 데이터입니다. 이를 보완하려고 실제 사람의 2~4주 주행을 같은 엔진에 넣는 도구를 함께 둡니다. 절차는 [`docs/실제기록_수집_가이드.md`](docs/실제기록_수집_가이드.md)에 있습니다.

1. 폰 GPS 기록 앱(GPSLogger, Open GPX Tracker)으로 운전할 때만 기록합니다.
2. `tools/gpx_to_trips.py`가 기록을 주행으로 나눕니다. 좌표는 지우고 거리·시간·집/회사/기타만 남깁니다(표준 라이브러리만 사용).
3. `./gradlew :app:testDebugUnitTest --tests '*RealDriveReplay*' -Pdrives=<폴더> --rerun`을 실행하면 `report.md`가 생깁니다.
   - 사람마다 현대 전기차 6종의 판정이 나옵니다.
   - 전기차 차주는 실제 충전량 대비 예측 오차가 함께 나옵니다.
4. `app/src/main/assets/real/<id>/`에 넣고 빌드하면 시연 모드에 **④ 실제 기록** 버튼이 생깁니다. 참가자 기록은 `.gitignore`로 저장소에서 뺍니다.

## 무엇을 보여 주나

| 단계 | 시점 | 쓰는 데이터 | 보여 주는 것 |
|---|---|---|---|
| ① 예비 진단 | 설치 직후 | 누적 주행거리, 차대번호 연식(타 온 기간 추정, 고칠 수 있음), 평균 연비, 내비 즐겨찾기(집·회사), 최근 목적지 | 연간 주행, 주당 집 충전 횟수(또는 월 급속 횟수), 연료비 절감 범위, 최근 목적지별 "충전 없이 / 급속 n회" (지금·1월) |
| 주행 중 | 매 주행 | 기어, 누적 주행거리, 공조 상태, 위치 | 실제 연료 게이지 옆에 "아이오닉 6였다면 배터리 n%". 조작 없음 |
| ② 실측 기록 | 1~4주 | P에서 P까지의 주행(거리·시간·공조·연비·집/회사/기타) | 실측 일수와 신뢰도, 날짜별 가상 배터리 최저치와 충전, 예비 진단과 달라진 점 |
| ③ 확정 리포트 | 4주 후 | 위 기록 전체 | 30일 기준 충전 횟수, 20% 미만인 날, 절감액, 급속 대기 시간, 예비 진단 vs 실제 표, 판정(집 충전이면 충분 / 급속 생활 필요 / 하이브리드가 더 맞음) |

## 계산 방법과 근거

- **비교 차종 (현대 전기차 6종, 인증 1회 충전 주행거리)**

  | 차종 | 배터리 | 상온 (도심·고속) | 저온 | 추정한 값 |
  |---|---|---|---|---|
  | 캐스퍼 일렉트릭 인스퍼레이션 17인치 | 49kWh | 295km (323·262) | 245km | 저온 도심·고속 |
  | 코나 일렉트릭 롱레인지 17인치 | 64.8kWh | 417km (454·371) | 307km | 저온 도심·고속 |
  | 아이오닉 5 롱레인지 2WD 19인치 | 84kWh | 485km (533·426) | 미확보 | 저온 전체 (아이오닉 6 롱레인지 저온/상온 비율 80%) |
  | 아이오닉 6 스탠다드 2WD | 63kWh | 442km (476·402) | 387km (375·402) | 없음 |
  | 아이오닉 6 롱레인지 2WD | 84kWh | 568km (609·517) | 453km (429·482) | 없음 |
  | 아이오닉 9 항속형 2WD 19인치 | 110.3kWh | 532km (복합만) | 약 400km (보도 근사치) | 상온 도심·고속 (공개 전비 4.7·3.9·4.3km/kWh 비율), 저온 도심·고속 |

  추정값이 있는 차종은 화면 비교표에 * 표시하고, 계산 근거에 어떤 값을 추정했는지 보여 줍니다.
- **속도**: 주행의 평균 속도로 도심(약 31km/h)과 고속(약 78km/h) 인증 주행거리 사이를 보간합니다.
- **기온**: 20℃에서 상온, -7℃에서 저온 인증값(난방 사용)으로 보간합니다. 공조를 끈 추위는 AAA(2019) -6.7℃ 공조 미사용 -12%를 쓰되, 공조를 끈 쪽이 켠 쪽(차종 저온 인증값)보다 더 짧게 가는 일은 없게 합니다. 더위는 35℃ 에어컨 -17%·미사용 -4%를 씁니다. 외기온도는 SDK에 없어 기상청 서울 월평균기온 평년값(1991~2020)을 씁니다.
- **충전 습관(가정)**: 집 충전이 되면 집에 4시간 이상 세울 때 50% 아래면 90%까지. 집 충전이 없으면 30% 아래로 세울 때 공공 급속으로 80%까지. 어떤 주행도 10% 아래로 계획하지 않고, 그 전에 급속 충전(80%까지, 실효 120kW, 대기 5분 추가)을 넣습니다.
- **요금**: 휘발유 1,855원/L(오피넷 2026-11-11 전국 평균). 충전은 2026-08-01 공공 요금: 완속 295원, 급속 325.6~393.1원(100~200kW 348.4원). 아파트 민간 완속 상단 350원을 범위 끝으로 씁니다. 충전 손실은 반영하지 않습니다.
- **판정 기준**: 집 충전 없이 생활 급속이 월 4.5회(주 1회 정도) 이하면 "조건부 적합", 넘으면 "하이브리드가 더 맞음". **연구 근거가 없는 제품 기준**이며 화면에도 그대로 표시합니다. 충전 습관도 가정이며 화면 계산 근거에 표시합니다.
- **30일 환산**: 생활 급속 횟수는 100km 미만 주행에 쓴 전기를 30%→80% 충전량으로 나눠 셉니다. 장거리 가는 길의 급속은 따로 셉니다. 충전 시점에 따라 결과가 달라지지 않고, 기록 첫날의 "공짜" 90% 배터리에도 영향받지 않습니다. 집 충전이 있으면 기록 끝 배터리가 시작(90%)보다 낮은 만큼 충전을 더합니다.
- **신뢰도**: 기록 기간과 함께 운전한 날 수를 봅니다. 28일 이상이고 운전한 날이 12일 이상이어야 "높음"입니다. 한 번 달리고 4주를 쉰 기록은 "낮음"입니다.
- **집 충전 여부를 답하기 전**에는 실측 판정을 내리지 않습니다.
- **내 차 연비**: 차가 기록한 평균 연비(`Fuel`)를 씁니다. 차가 주지 않으면 "지금 타는 차"를 한 번 물어 공인 복합연비(그랜저 2.5 가솔린 11.7km/L, 아반떼 1.6 하이브리드 21.2km/L)로 대신 계산하고, 화면에 출처를 표시합니다. 공인 연비는 실제보다 높게 나오는 편이라 절감액은 작게 계산됩니다.
- **주행 거리 출처**: 주행마다 누적 주행거리 / 차속 적분 / 내비 위치 중 어디서 거리를 얻었는지 저장하고 실측 화면에 "거리 출처"로 보여 줍니다.
- **목적지 거리**: 집에 세워 둔 상태에서는 내비 `requestTrip`으로 실제 도로 거리를 받고, 그 전에는 직선거리 × 1.3으로 추정한 뒤 화면에 "직선거리로 추정"이라고 표시합니다.

## Pleos SDK 사용

모두 **읽기 권한**입니다. 차량 제어(Vehicle Control) 권한은 쓰지 않습니다. 차속은 Vehicle SDK에 없어 AAOS 표준 속성 `PERF_VEHICLE_SPEED`를 씁니다. 기어는 AAOS `GEAR_SELECTION`을 먼저 쓰고, 차가 답하지 않을 때만 Pleos Gear API를 씁니다. [Pleos SDK 정책](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/)이 "동일하거나 유사한 기능의 API가 AAOS에 있으면 AAOS에 맞춘다"이고, SDK의 Gear API도 Google `GEAR_SELECTION` 속성을 쓰라고 안내하므로, 그 원칙을 따른 것입니다.

| 목적 | API | 권한 |
|---|---|---|
| 주행 시작·끝 | AAOS `GEAR_SELECTION` (Pleos SDK의 Gear API가 deprecated로 이 속성을 안내함). 차가 답하지 않으면 Pleos `Gear.registerGearSelection`으로 대신함 | android.car.permission.CAR_POWERTRAIN, (대체) CAR_DRIVE |
| 주행 거리, 예비 진단 연간 주행 | `Odometer.registerDistanceDriven`, `getCurrentDistanceDriven` | CAR_DRIVE |
| 연료 게이지, 내 차 연비 | `Fuel.registerFuelPercent`, `registerCurrentFuelEconomy`, `registerCumulativeFuelEconomy` (SDK 단위 L/100km를 km/L로 변환) | CAR_ENERGY |
| 이미 전기차인 차의 배터리 (전환 후 모드 준비) | `EvBattery.registerEvBatteryStateOfCharge`, `getCumulativeEvEfficiency` | CAR_ENERGY |
| 공조 사용 (전기차 소비 보정) | `HVAC.checkHvacPowerCapabilities`, `registerHvacPowerState` | CAR_CLIMATE |
| 주행 거리 보완 | AAOS `CarPropertyManager` `PERF_VEHICLE_SPEED` (Pleos SDK에 차속이 없어 표준 API 사용) | android.car.permission.CAR_SPEED (런타임) |
| 타 온 기간 추정 | `CarInfo.getVINInfo` (10번째 자리 연식만 계산, 저장 안 함) | CAR_INFO |
| 집·회사 | NaviHelper `getBookmarkInfo` (`BookmarkType.HOME`/`OFFICE`) | NAVI_ROUTE |
| 최근 목적지 | `getRecentDestinationInfo` | NAVI_ROUTE |
| 위치 (집/회사 판별, 거리 보조) | `getCurrentLocationInfo` | NAVI_ROUTE |
| 도로 거리 | `requestTrip` → `onTripInfo` | NAVI_ROUTE |
| 목적지를 찍는 순간 | `onDestinationChanged`·`onRoutingCompleted`·`onRouteStarted` → `getDestinationInfo`, `getRouteStateInfo`, `onDrivingInfo` | NAVI_ROUTE |
| 순간의 한 문장 | TextToSpeech SDK 3.3.1 `Mode.ON_DEVICE`, `Locale.KOREA` | TTS_SERVICE |
| 이미 전기차인 차: 내비 계산과 비교 | AAOS `INFO_EV_BATTERY_CAPACITY`, `EV_BATTERY_LEVEL` (Pleos의 용량 API가 deprecated로 이 속성을 안내함), NaviHelper `RouteStateInfo.remainDestinationChargingInt` | android.car.permission.CAR_INFO, CAR_ENERGY |

주행 기록은 포그라운드 서비스(`RecorderService`)가 맡아 내비 등 다른 앱을 보는 동안에도 이어지고, 부팅(시동)과 함께 시작합니다. 주행 중 화면은 게이지 하나뿐이고 조작이 필요 없습니다.

## 개인정보

- 기록은 차 안(앱 저장소)에만 남습니다. 주행마다 거리, 시간, 기온, 공조 비율, 연비, 출발·도착이 집/회사/기타인지만 저장하고 **좌표는 저장하지 않습니다**.
- 차대번호는 연식 계산에만 쓰고 저장하지 않습니다. 앱 코드는 네트워크를 쓰지 않습니다. 다만 TextToSpeech SDK의 매니페스트에 SDK 검증용 인터넷 권한이 들어 있어 합쳐집니다. 음성은 기기 내 모드로 만듭니다.
- 주행 중에는 진행 중인 주행을 10초마다 앱 저장소에 적어 둡니다. 앱이 주행 도중 종료돼도 다시 시작하면 그 주행을 이어서 기록합니다.

## 실행 방법

### 준비
- Android Studio, JDK 21 (`gradle/gradle-daemon-jvm.properties`가 로컬 JDK 21을 찾습니다)
- Pleos Connect Emulator v2.1 (API 34). SDK Manager > SDK Update Sites에 `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-arm64-sys-img.xml` (Windows/Linux는 `crp-sys-img.xml`), AVD RAM 4GB, Cold boot
- 음성을 들으려면 에뮬레이터를 오디오와 함께 켭니다(`-no-audio` 옵션을 쓰면 기기 내 음성 엔진이 오류를 냅니다).

### 빌드·설치
```bash
./gradlew :app:testDebugUnitTest      # 계산 엔진 단위 테스트 (56개, 실제 기록 재생은 -Pdrives가 있을 때만)
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
브라우저에서 `http://127.0.0.1:8088/` → Emulation Controls에서 Gear를 D로 바꾸면 주행 화면(가상 배터리 게이지)으로, P로 바꾸면 정차 화면으로 돌아오며 정차 문장을 말합니다.

### 목적지를 찍는 순간 시험하기
에뮬레이터 내비에서 목적지를 검색해 `안내 시작`을 누르면, 이프EV가 목적지와 남은 거리·시간을 받아 한 문장으로 말합니다. 주행 화면에는 "목적지까지 n km · 도착 배터리 n%" 카드가 나타납니다. 에뮬레이터 내비는 네트워크로 장소를 검색하고, 영문 검색어(예: `Gangneung`)를 쓸 수 있습니다.

## 테스트 환경
- Pleos Connect Emulator v2.1.0 (Android 14, 2560×1440), macOS (Apple Silicon)
- Vehicle SDK 2.3.2, NaviHelper SDK 3.0.5, TextToSpeech SDK 3.3.1 (`https://nexus-playground.pleos.ai/repository/maven-releases/`)

## 개발하며 확인한 점 (실차에서 다시 확인해야 할 것)
- **연비 경로는 실차에서 한 번도 검증되지 않았습니다.** 에뮬레이터 차량은 전기차라 `Fuel`의 연료량·연비 속성이 지원되지 않습니다(`property ID is not in carPropertyConfig list`). 그랜저·아반떼처럼 연료차인 실차에서 동작을 확인해야 합니다. 연비를 주지 않으면 "지금 타는 차"의 공인 복합연비로 대신 계산하고 출처를 표시합니다. 연비 단위는 SDK 소스 주석에 L/100km로 명시돼 있어 km/L로 바꿔 씁니다(`kmPerLiterFromLitersPer100Km`).
- 에뮬레이터 차는 전기차(77.4kWh)입니다. 에뮬레이터 내비는 이 차 기준의 도착 배터리를 직접 계산해 보여 줍니다(예: 194km 경로에 64%, 왕복 후 27%). PT까지 같은 경로에서 내비 계산과 이프EV 계산을 나란히 보여 주는 화면을 만들 계획입니다.
- 연식은 차대번호의 모델 연도만 알려 줍니다. 모델 연도는 보통 전년도 중반부터 판매되므로 그해 7월 1일부터 셉니다. 2027년형을 "1개월"로 보아 연간 주행이 12배로 커지던 문제를 고쳤습니다. 화면에서 고칠 수 있습니다.
- 에뮬레이터의 누적 주행거리(`PERF_ODOMETER`)는 주행해도 0에 머뭅니다. 그래서 주행 거리는 누적 주행거리가 움직이면 그 값을, 아니면 차속 적분과 내비 위치 궤적 중 긴 쪽을 씁니다. Virtual Console에서 D + ACCEL로 달린 6.8km가 이 방식으로 기록되는 것을 확인했습니다 (내비 화면이 앞에 있는 동안에도 기록됨).
- `requestTrip`의 `TripInfo.distance`는 SDK 주석대로 미터이고 km로 바꿉니다.
- 에뮬레이터 내비의 `remainDestinationChargingInt`는 SDK 주석에 단위가 없습니다. 실제 값은 Wh였습니다(77.4kWh 차에서 화면 63%일 때 49,216). 100 이하이면 %로, 그보다 크면 Wh로 보고 배터리 용량으로 나눕니다. 또 안내 중에도 `isRouting=false`가 오므로, 경로 종료는 `onRouteEnded`·`onRouteCancelled`·`onDestinationArrived`로만 판단합니다.
- TextToSpeech는 `Locale.KOREAN`("ko")을 주면 기기 내 엔진이 `unsupported locale: ko-`로 실패합니다. `Locale.KOREA`(ko-KR)를 씁니다. Pleos 엔진이 실패하면 Android TTS로 같은 문장을 다시 말합니다.

## 코드 구조
```
app/src/main/java/com/ifev/app/
├── engine/     EvModel(제원·요금·기온), EnergyModel(조건별 전비), BatterySim(주행 재생·충전),
│               Preliminary(예비 진단), Measured(실측 리포트·비교), TripRecorder(신호 → 주행),
│               RoutePreview(경로 미리 보기), Lines(순간의 한 문장), NaviCheck(내비 계산과 비교)
├── vehicle/    VehicleGateway(Vehicle SDK), NaviGateway(NaviHelper SDK), Voice(TextToSpeech SDK),
│               AaosSignals(AAOS 기어·EV 배터리), SpeedSource(AAOS 차속)
├── data/       LiveRecorder(실시간 기록·순간), Narrator(순간 → 음성), Store(설정·주행 저장)
├── scenario/   DemoScenario(시연용 가상 데이터), RealDrives(실제 기록 assets)
├── ui/         Compose 화면, Pleos 색·글자 토큰
├── RecorderService.kt   포그라운드 기록 서비스, 부팅 시작
└── MainActivity.kt, MainViewModel.kt
```

## 시연 자료
- 시연 모드의 2~4주 기록은 가상 데이터입니다. 화면 상단과 확정 리포트에 "시연용 가상 데이터"로 표시합니다. 실시간 모드의 기록만 실제 주행입니다.
- `demo/IfEV_demo.mp4` (약 2분 33초): 에뮬레이터 화면 녹화(`adb shell screenrecord`)에 한국어 해설과 자막을 넣은 영상입니다. 순서는 설치 직후 예비 진단 → 집 충전 없음 판정 → 실제 내비에서 목적지를 찍는 순간(내비가 보여 주는 전기차 도착 배터리 포함) → Virtual Console 실시간 주행과 정차 → 같은 경로 내비 계산 vs 이프EV 계산 → 4주 확정 리포트 → 집 충전 없음 확정 판정과 차종별 판정입니다. 실시간 구간의 음성 문장은 앱 로그에 남은 문장 그대로입니다. 다만 에뮬레이터 소리는 화면 녹화에 담기지 않아, 같은 문장을 영상용으로 다시 합성했습니다. 해설과 재합성에는 Supertonic 3(오프라인 TTS, OpenRAIL-M)을 썼고, 영상에도 그 사실을 표시했습니다.
- `demo/*.png`: 각 단계 화면 캡처(현재 빌드)

## APK
- `apk/IfEV_release.apk` (release 빌드, 시연용으로 debug 키 서명). 제출 시 `팀명_이프EV_release.apk`로 이름을 바꿉니다.
