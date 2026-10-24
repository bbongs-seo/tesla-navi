# 주행 기록·지도와 구글 드라이브 동기화 설계 (초안)

작성: 2026-10-24 · 상태: 설계, 구현 전(0단계 진단만 구현) · 목표 버전: 0.6.0 · 기준: 포크 main 0.5.4(통계 탭)

## 목표와 범위

0.5.4부터 통계 탭의 `주행` 화면이 차량과 연결된 상태로 달린 구간의 요약(시작·종료 시각, 거리, 최고 속도, 배터리)을 기록한다(`TripTracker`, `TripStore`). 이 설계는 그 위에 세 가지를 더한다.

- 구간마다 차량 위치 점을 남겨 지도로 보여 준다.
- 구간 요약과 위치 점을 사용자 본인의 구글 드라이브 앱 전용 폴더에 구간별 파일로 동기화하고, 새 휴대폰에서 같은 계정으로 로그인해 복원한다.
- 주행 기록을 구글 로그인 사용자 전용으로 바꾼다.

주행 기록은 구글 계정으로 로그인한 사용자만 쓸 수 있다. 로그인하지 않으면 구간을 기록하지 않고, 통계 탭의 `주행` 화면에는 로그인 안내만 보인다. 로그인은 "주행 기록을 휴대폰과 내 구글 드라이브에 보관"하는 데 대한 동의를 겸한다. 그래서 로그인과 별개인 동기화 켜기·끄기 설정은 두지 않는다. 0.5.4는 통계 탭만 켜면 로그인 없이 요약을 기록하므로, 이 조건을 기존 요약 기록에도 적용할지는 아래 "정해야 할 것"에 남긴다.

MVP에 넣지 않는 것:

- 기능 도입 이전의 과거 주행 기록. BLE로는 차량의 현재 상태만 조회할 수 있고 주행 이력을 꺼낼 방법이 없다.
- 통계·차트, 사용자에게 보이는 파일로 내보내기(`drive.file`), 업로드 전 암호화.
- 휴대폰 GPS 대체 기록. 0단계에서 차량 위치를 받지 못하면 그때 범위에 넣는다.
- 기기 간 삭제 전파. 휴대폰 A에서 구간을 지우면 드라이브 파일은 지워지지만, 이미 내려받은 휴대폰 B의 사본은 남는다. 한 사람이 쓰는 앱이라 허용한다.

전제: 개인용 APK이며 외부에 배포하지 않는다(PLAN.md). 기능 개념만 참고했고 다른 앱의 구현·화면·리소스는 참조하지 않는다. 근거는 Tesla `vehicle-command` 공개 프로토콜, 카카오내비 SDK 공개 API, Google·Android 공식 문서다.

## 0단계: 실차 확인 (구현 전 필수)

`GetVehicleData`에 `GetLocationState`를 추가했을 때 현재 앱 키 역할(`ROLE_VEHICLE_MONITOR`)로 차량이 `LocationState`를 반환하는지 확인한다. 화면 변경 없이 연결 기록만 남긴다(구현됨, 실차 미확인).

- `TeslaClient.readDriveState()`가 공통 조회 함수 `vehicleData()`로 주행 상태와 위치 상태를 함께 요청한다. 차량이 위치 요청을 거부하면 같은 조회를 주행 상태만으로 다시 보내고, 그 연결이 끝날 때까지 위치를 묻지 않는다. 세션 오류와 일시 오류(코드 1, 2, 11)는 거부로 보지 않는다. 배터리 조회(`readChargeState()`)는 그대로 따로 보낸다.
- 연결마다 처음 한 번과 상태가 바뀔 때 "위치 제공 확인 / 위치 상태 없음 / 좌표 없음 / 거부(사유)" 중 하나를 남긴다. 위치를 받는 동안에는 5분마다 조회 횟수, 좌표 횟수, GPS 시각 갱신 횟수, 대략적인 이동 거리를 요약한다.
- 연결 기록은 `DriveController.note()`로 남겨 화면 상태 문구는 바꾸지 않는다. 연결 기록은 앱에서 복사해 공유할 수 있으므로 좌표는 남기지 않는다. 좌표를 저장하지 않는 진단이라 로그인 조건 없이 동작한다.

- 위치가 오면 차량 GPS를 기록 출처로 쓴다. 휴대폰 배터리를 추가로 쓰지 않고 BLE 왕복 횟수도 늘지 않는다.
- 위치가 오지 않으면 `DriveService`(위치 포그라운드 서비스 유형 보유)에서 휴대폰 `LocationManager`로 기록하도록 수집 부분만 바꾼다. 이후 설계는 같다.

## 데이터 수집

- 0단계에서 `TeslaClient.readDriveState()`가 한 번의 `GetVehicleData`로 `GetDriveState`와 `GetLocationState`를 함께 요청하도록 바꿨고, `DriveSnapshot.location: VehicleLocation?`(좌표, 방향, `gps_as_of`)을 더했다. 응답에 위치가 없으면 위치 없이 진행한다. LocationState 위경도는 float이라 한국 위도에서 약 1 m 단위 정밀도다.
- 누적 주행거리는 이미 `DriveSnapshot.odometerKm`에 들어 있어 그대로 쓴다.
- 폴링 간격은 지금처럼 약 5초다. `DriveController.recordTrip()`이 0.5.4처럼 `TripStore.sample()`에 샘플을 넘기고, 위치도 함께 넘긴다. 로그인하지 않았거나 다시 로그인이 필요한 상태면 넘기지 않는다.

## 구간 판단: 기존 `TripTracker` 확장

구간 판단은 0.5.4의 `TripTracker`를 그대로 쓴다. P단을 벗어나 3 km/h 이상이면 시작하고, P단이면 끝낸다. 샘플 공백이 10분을 넘으면 닫고, 차량 세션이 끝나면 `DriveController.run()`의 `finishTrip()`으로 닫으며, 0.3 km 미만은 버린다. 거리는 주행거리 차이를 우선하고 없으면 속도로 적분한다. 이 설계는 경로 점만 붙인다.

- 구간 ID를 시작 시점에 정한다. 지금은 구간을 닫을 때 ID를 만들어서, 진행 중인 구간에 점을 묶을 수 없다.
- 점 저장: 열린 구간이 있고 위치가 있으며 `gps_as_of`가 직전 점과 다를 때만 저장한다. 정차 중 직전 점과 10 m 안이면 생략한다.
- 구간이 0.3 km 미만으로 버려지면 그 구간의 점도 지운다.
- 프로세스 종료 대비: 점은 받는 즉시 저장한다. 0.5.4의 열린 구간은 메모리에만 있어 프로세스가 죽으면 사라지므로, 앱 시작 때 닫히지 않은 구간의 점은 지운다. 열린 구간 자체를 저장해 이어 붙이는 것은 이 설계 범위 밖이다.

## 로컬 저장

구간 요약은 0.5.4처럼 `TripStore`(SharedPreferences `drive-trips`의 JSON)에 둔다. 위치 점과 동기화 상태는 수십만 건까지 쌓이므로 JSON 대신 `SQLiteOpenHelper`로 새 데이터베이스에 둔다. 테이블이 둘뿐이라 Room과 어노테이션 처리(KSP) 없이 지금 빌드 구성을 유지할 수 있다.

| 테이블 | 열 |
|---|---|
| `points` | `trip_id`, `t`, `lat`, `lon`, `speed_kph`, `heading` · 인덱스 (`trip_id`, `t`) |
| `trip_sync` | `trip_id` PK, `vin`, `sync_state`, `remote_id`, `deleted` |

- `sync_state`: `PENDING`(업로드 대기), `UPLOADED`. 로그인한 상태에서만 기록하므로 동기화 대상이 아닌 구간은 없다. 오프라인으로 달린 구간은 `PENDING`으로 남았다가 네트워크가 연결되면 올라간다. 구간 삭제(0.5.4의 길게 눌러 삭제)는 `TripStore`와 점을 바로 지우고 `deleted=1`만 남겼다가, 원격 파일 삭제가 끝나면 행을 지운다.
- 원격에서 내려받은 구간은 요약을 `TripStore`에, 점을 `points`에 넣는다.
- 로그인 상태(계정 이메일, 마지막 동기화 시각, 다시 로그인 필요 여부)는 SharedPreferences `trip-sync`에 둔다.
- `data_extraction_rules.xml`의 클라우드 백업·기기 이전 양쪽에 `database` 영역 제외를 추가한다. 지금은 `root`와 `sharedpref`만 제외돼 있다.
- 용량 추정: 하루 1시간 주행이면 1년에 약 26만 점, 수십 MB 이하.

## 드라이브 파일 형식

구간 하나가 파일 하나이고, 구간이 닫힌 뒤에는 바뀌지 않는다. 그래서 여러 기기 사이에 수정 충돌이 생기지 않는다.

- 이름 `trip-<id>.json.gz`, `parents: ["appDataFolder"]`.
- 내용(gzip JSON, 형식 버전 `v`):

```json
{"v":1,"id":"…","vin":"…","startMs":0,"endMs":0,"startKm":0.0,"endKm":0.0,"distanceKm":0.0,
 "maxSpeedKph":0.0,"startBattery":0,"endBattery":0,"points":[[t,lat,lon,speedKph,heading]]}
```

- `appProperties`: `tripId`, `v`, `startedAt`. 앱 전용 속성이라 다른 앱에 보이지 않는다. 파일당 30개, 항목당 124바이트 제한 안에 든다.
- VIN은 파일 본문에만 둔다. 앱 전용 폴더는 이 앱만 읽을 수 있다.

## 동기화

로그인과 인증

- 로그인은 `play-services-auth`(22.0.0, compileSdk 34에서 사용 가능)의 `AuthorizationClient` 요청 한 번으로 처리한다. 범위는 `drive.appdata`, `openid`, `email`이며 모두 비민감이라 기본 OAuth 검증만 필요하다. 동의가 끝나면 userinfo로 계정 이메일을 확인해 저장한다. 서버가 없어 ID 토큰을 검증할 곳이 없으므로 Credential Manager의 Google 로그인은 쓰지 않는다.
- 로그인 후에는 같은 `authorize()` 호출로 화면 없이 1시간짜리 접근 토큰을 다시 받는다. 리프레시 토큰을 기기에 저장하지 않는다.
- 다시 로그인 필요: 백그라운드 작업에서 `hasResolution()`이 참이면(권한 회수·만료) 동기화를 멈추고 "다시 로그인 필요"로 표시하며, 다시 로그인할 때까지 새 구간을 기록하지 않는다. 단순한 네트워크 실패는 로그인 문제로 보지 않으므로 오프라인 주행은 계속 기록한다.
- 계정 전환: 저장한 이메일과 다른 계정으로 다시 로그인하면 그대로 진행하지 않는다. 휴대폰 기록과 기존 계정의 드라이브 기록을 어떻게 할지(기존 계정 기록 삭제 후 전환, 또는 취소) 묻는다. 묻지 않고 넘어가면 원격 기록이 두 계정으로 갈라진다.
- 앱 전용 폴더의 파일은 사용자 본인 드라이브 용량을 쓰는 것으로 알려져 있다. 공식 문서 페이지에서는 확인하지 못해 구현 때 확인한다. 1년치도 수십 MB 수준이라 기본 15 GB에 비해 작다.

전송

- 새 HTTP 라이브러리 없이 `HttpURLConnection`으로 Drive REST v3를 호출한다.
  - 업로드: `POST https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart`
  - 목록: `GET https://www.googleapis.com/drive/v3/files?spaces=appDataFolder&fields=nextPageToken,files(id,appProperties)&pageToken=…`
  - 다운로드: `GET …/files/{id}?alt=media` · 삭제: `DELETE …/files/{id}`
- 업로드 멱등성: 올리기 전에 `appProperties has { key='tripId' and value='<id>' }`로 찾아보고, 있으면 `remote_id`만 기록한다. 업로드 응답을 받지 못한 재시도에서 중복 파일을 막는다. 쿼리 문법은 구현 때 Drive 검색 문서로 다시 확인한다.
- `DriveApi` 인터페이스 뒤에 두고, 테스트는 가짜 구현으로 한다.

실행

- WorkManager(`work-runtime-ktx` 2.9.x, 2.10은 compileSdk 35 필요) 고유 작업 `trip-sync`. 네트워크 연결 제약과 지수 백오프를 쓴다.
- 구간이 닫힐 때, 로그인할 때, 앱을 열 때 실행하고, 하루 한 번 주기 작업으로 원격 목록을 확인한다. 로그인하지 않은 상태에서는 작업을 예약하지 않는다.
- 한 번의 실행 순서: 토큰 확보 → 삭제 대기 원격 삭제 → `PENDING` 업로드 → 원격 목록에서 로컬에 없는 `tripId` 다운로드(`UPLOADED`로 저장).
- 원격 목록에 파일이 없다는 이유로 로컬 기록을 지우거나 다시 올리지 않는다. 사용자가 드라이브 설정에서 앱 데이터를 지운 경우에도 휴대폰 기록은 그대로 남는다.

로그아웃과 삭제

- 로그아웃: 기록과 동기화를 멈추고 `revokeAccess()`로 권한을 회수한다. 드라이브 파일은 남긴다. 휴대폰 기록은 남기거나 지우도록 고르게 하고, 남긴 기록도 다시 로그인하기 전에는 보이지 않는다.
- 드라이브 기록 삭제: 원격 파일을 모두 지운 뒤 로그아웃한다.
- 구간 삭제: 휴대폰과 드라이브에서 함께 지운다. 전체 삭제도 같다.

## 화면과 지도

- 지도는 통계 탭(`AppTab.LOGBOOK`, 설정의 "통계 탭" 스위치) `주행` 화면의 0.5.4 월별 목록에서 연다. 구간을 누르면 안내 설정(`GuidanceSettingsPage`)과 같은 방식의 하위 페이지로 그 구간의 지도를 열고, 뒤로 가기로 목록에 돌아온다. 목록 위에는 그 달 전체 경로를 겹쳐 보는 "지도로 보기"를 둔다. 지도 뷰는 하위 페이지가 열려 있는 동안만 만든다.
- 로그인하지 않았으면 `주행` 화면은 구글 로그인 안내와 버튼만 보여 준다. 다시 로그인이 필요한 상태도 같다.
- 목록의 각 구간에는 동기화 상태와 위치 점 유무를 작게 표시한다. 위치 점이 없는 구간(0단계 전 기록, 위치를 주지 않은 차량)은 지도 대신 요약만 보여 준다.
- 지도는 카카오내비 SDK `KNMapView`를 `AndroidView`로 감싼다. 고른 구간을 `KNMapPolyline`으로, 시작·끝을 `KNMapMarker`로 그리고 `KNMapCameraUpdate.fitTo`로 영역을 맞춘다. 좌표는 기존에 쓰는 `KNSDK.convertWGS84ToKATEC`로 바꾼다. 점이 많으면 화면용으로만 단순화(Douglas–Peucker)한다.
- 지금은 `KakaoNavigationEngine`이 주행 시작 때 SDK를 초기화한다. 초기화를 공용으로 분리해 기록 화면에서도 호출한다. 인증 실패나 오프라인이면 목록만 보여 준다.
- 계정 영역: 로그인한 이메일, 마지막 동기화, 다시 로그인 필요 안내, 로그아웃, 드라이브 기록 삭제.

## Google Cloud 준비 (사용자 작업)

1. Google Cloud 프로젝트를 만들고 Google Drive API를 사용 설정한다.
2. OAuth 동의 화면: 사용자 유형 외부, 범위 `drive.appdata`, `openid`, `email`. 테스트 상태로 두면 7일마다 다시 동의해야 할 수 있어, 비민감 범위만 쓰는 상태로 프로덕션 게시를 권한다.
3. Android OAuth 클라이언트: 패키지 `dev.teslanavi`, debug 서명 SHA-1(`keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android`). 서명 키를 바꾸면 다시 등록한다.
4. 오프라인 접근(서버 인증 코드)을 쓰지 않으므로 앱에 클라이언트 ID를 넣지 않는다.

## 개인정보

- 주행 기록은 구글 로그인으로만 켜지며 기본값은 꺼짐이다. 로그인 전에 저장 위치(휴대폰, 본인 구글 드라이브 숨김 폴더)와 기록 대상(차량과 연결된 상태로 달린 구간의 위치)을 알린다.
- 기록은 휴대폰과 사용자 본인 계정에만 있고 개발자 서버를 거치지 않는다. 외부 배포로 바뀌면 위치정보법 의무를 다시 검토한다.
- 구글 계정이 유출되면 주행 기록도 노출된다. 업로드 전 암호화는 새 기기 복원용 키 관리 방법을 정한 뒤 다음 단계에서 검토한다.

## 단계와 완료 조건

각 단계는 따로 커밋한다.

1. 0단계 실차 확인: 위치 수신 여부와 갱신 간격을 연결 기록으로 확인한다.
2. 로그인·경로 점 저장: 로그인 상태 저장과 기록 차단(로그인 전, 다시 로그인 필요) 테스트, `TripTracker` 확장(시작 시 ID, 점 묶기, 버린 구간의 점 삭제) 테스트와 점 저장소 테스트를 통과한다. 0.5.4의 `TripTracker` 기존 테스트도 그대로 통과해야 한다. 로그인이 먼저 있어야 기록을 시험할 수 있으므로 인증을 이 단계로 앞당긴다.
3. 기록 화면·지도: 실기기에서 로그인 안내, 구간 목록과 선 표시, 오프라인 시 목록만 표시를 확인한다.
4. 드라이브 동기화: 가짜 `DriveApi`로 업로드 멱등성, 삭제 대기 처리, 원격 목록 병합, 다시 로그인 필요 처리, 계정 전환, 구간이 닫힐 때의 업로드 예약을 테스트한다. 작업 테스트에는 `androidx.work:work-testing`을 쓴다. 실기기에서 동의·백그라운드 업로드·새 기기 복원을 확인한다.
5. 문서·버전: README, PLAN.md 현황 갱신과 0.6.0.

## 정해야 할 것

- 0단계 결과에 따른 위치 출처(차량 GPS 또는 휴대폰 GPS).
- 로그인 조건의 범위: 0.5.4의 구간 요약 기록에도 로그인을 요구할지, 지도와 드라이브 동기화에만 요구할지. "주행 기록은 로그인 사용자만"이라는 요청을 그대로 따르면 전자이고, 이 경우 0.5.4 사용자는 업데이트 후 로그인해야 요약도 계속 기록된다.
- 기록 조건: 0.5.4는 통계 탭이 켜져 있을 때만 기록한다. 로그인 조건을 더하면 "탭 켜짐 + 로그인"이 되는데, 탭 스위치를 기록 조건에서 뺄지.
- 위치 점 저장소에 경로 단순화 결과를 따로 둘지, 화면을 그릴 때마다 계산할지.
- 카카오모빌리티 SDK 약관상 안내 외 용도의 지도 표시 허용 여부. 허용되지 않으면 지도 SDK를 따로 고른다.
- 구간 병합 5분, 폐기 기준 0.3 km·1분은 실차 기록을 보고 조정한다.

설계 참고: [Tesla vehicle.proto](https://github.com/teslamotors/vehicle-command/blob/main/pkg/protocol/protobuf/vehicle.proto), [Android 권한 요청(AuthorizationClient)](https://developer.android.com/identity/authorization), [Drive 앱 전용 폴더](https://developers.google.com/workspace/drive/api/guides/appdata), [Drive 범위](https://developers.google.com/workspace/drive/api/guides/api-specific-auth), [Drive 사용자 정의 속성](https://developers.google.com/workspace/drive/api/guides/properties).
