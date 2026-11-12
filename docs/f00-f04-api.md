# F-00 / F-04 API 명세

Base URL: 로컬 `http://localhost:8080`, 배포 시 해당 서버의 HTTPS URL.
Content-Type: `application/json`. 시간은 ISO 8601 offset 포함 형식(`Z` 또는 `+09:00` 등)으로 보내며 응답은 UTC입니다.
도착 시각은 서버 현재 시각 이후 24시간 이내로 받습니다.

로그인 대신 프론트에서 최초 1회 `crypto.randomUUID()`로 만든 deviceId를 저장해 계속 사용합니다.
sessionId는 통학 시작마다 새 UUID를 만듭니다. 보정할 때는 같은 sessionId를 사용합니다.
테스트 발송/예약 API에는 `X-Device-Id: <기기 UUID>` 헤더가 필요합니다.

## 1. 공개키 조회

`GET /api/push/vapid-public-key` → 200

```json
{"publicKey":"<Base64URL VAPID 공개키>"}
```

PUSH_ENABLED=false이면 503 `PUSH_DISABLED`. 개인키는 어떤 API에도 반환하지 않습니다.

## 2. 구독 등록·갱신

`POST /api/push/subscriptions` → 200

```json
{
  "deviceId": "ad216e40-432b-4e3e-bb9a-1d9f17291720",
  "endpoint": "https://fcm.googleapis.com/wp/<실제 PushSubscription endpoint>",
  "keys": {
    "p256dh": "<PushSubscription.toJSON().keys.p256dh>",
    "auth": "<PushSubscription.toJSON().keys.auth>"
  }
}
```

예시의 꺾쇠 값은 브라우저의 실제 구독 값으로 바꿔야 합니다. p256dh는 65바이트 P-256 공개키, auth는 16바이트의 Base64URL 값입니다. Android Chrome/FCM, Apple Web Push, Mozilla Web Push endpoint를 허용합니다. 추가 제공자는 `app.push.allowed-hosts`에 정확한 신뢰 호스트를 설정해야 합니다.

```json
{
  "deviceId": "ad216e40-432b-4e3e-bb9a-1d9f17291720",
  "active": true,
  "createdAt": "2026-11-12T00:00:00Z",
  "updatedAt": "2026-11-12T00:00:00Z"
}
```

같은 기기는 구독 정보를 갱신하고 등록일은 유지합니다. 다른 deviceId에 이미 등록된 endpoint는 409입니다. 만료된 구독은 브라우저에서 새 구독을 만든 후 이 API로 다시 등록하세요. FAILED 예약은 자동 복구하지 않으므로 현재 세션 예약 PUT도 다시 호출합니다.

## 3. 테스트 발송

`POST /api/push/test` + X-Device-Id → 202 `{"status":"ACCEPTED"}`

body 없음. 30초에 한 번 호출할 수 있습니다. 제공자 접수까지만 확인하므로 화면을 끈 실기기에서 알림 표시도 확인하세요. 만료된 구독은 410, 발송 실패는 502, 너무 잦은 요청은 429입니다.

## 4. 예약 등록·수정

`PUT /api/sessions/{sessionId}/alarms` + X-Device-Id → 200

```json
{
  "arrivalAt": "2026-11-12T09:30:00+09:00",
  "reminderMinutes": 5
}
```

위 시각은 형식 예시입니다. 실제 호출 시 현재 이후 도착 시각으로 바꾸세요.
reminderMinutes는 생략/null이면 5, 지정 시 3/5/7만 허용합니다. 프론트는 보정된 예상 도착 시각을 보냅니다. 서버가 두 예약과 여유 1분을 계산하므로 여유분을 프론트에서 중복 차감하지 마세요.

```json
[
  {
    "alarmId": "a9a7bf68-f8b8-4277-a71f-85886de4b095",
    "sessionId": "b7458e9d-5dc7-4caa-9379-45e6e6ccf5b6",
    "type": "FINISH",
    "scheduledAt": "2026-11-12T00:24:00Z",
    "expiresAt": "2026-11-12T00:28:00Z",
    "arrivalAt": "2026-11-12T00:30:00Z",
    "minutesBeforeArrival": 5,
    "status": "PENDING",
    "attemptCount": 0,
    "sentAt": null,
    "lastErrorCode": null
  },
  {
    "alarmId": "971e6967-8da5-4408-824b-d72bda075a57",
    "sessionId": "b7458e9d-5dc7-4caa-9379-45e6e6ccf5b6",
    "type": "PREPARE",
    "scheduledAt": "2026-11-12T00:28:00Z",
    "expiresAt": "2026-11-12T00:30:00Z",
    "arrivalAt": "2026-11-12T00:30:00Z",
    "minutesBeforeArrival": 1,
    "status": "PENDING",
    "attemptCount": 0,
    "sentAt": null,
    "lastErrorCode": null
  }
]
```

위치 보정 시 같은 URL로 새 arrivalAt을 보냅니다. 이전 미발송 예약이 삭제되고 새 alarmId가 반환됩니다. 이미 SENT인 단계는 이전 시각/ID의 발송 기록으로 남습니다. 잘못된 보정 요청은 400이며 기존 예약을 지우지 않습니다.

네트워크 오류 시 프론트는 '알림 시각 반영 대기 중'을 표시하고 가장 최신 보정만 연결 복구 후 다시 보냅니다. 같은 기기/세션의 요청을 직렬화하세요. 서버는 도착 순서대로 적용하므로 오래된 오프라인 요청이 최신 보정을 덮어쓰지 않게 해야 합니다. 취소 완료 후에는 해당 세션의 보정 큐도 폐기합니다.

## 5. 조회 / 취소

`GET /api/sessions/{sessionId}/alarms` + X-Device-Id → 위와 같은 배열, 예약이 없으면 `[]`.

상태:

| 값 | 뜻 |
| --- | --- |
| PENDING | 예약 대기 또는 재시도 대기 |
| SENT | 푸시 제공자 접수 성공 |
| FAILED | 영구 오류/구독 만료/재시도 횟수 초과 |
| EXPIRED | 알림 유효기간 경과 |

`DELETE /api/sessions/{sessionId}/alarms` + X-Device-Id → 204, body 없음.
기기·세션 조합의 예약과 발송 기록을 삭제하며 반복 호출도 204입니다. 취소 시 다른 기기의 같은 sessionId에는 영향이 없습니다. 구독이 만료된 기기도 취소할 수 있습니다.

## 6. Web Push payload

```json
{
  "alarmId": "a9a7bf68-f8b8-4277-a71f-85886de4b095",
  "sessionId": "b7458e9d-5dc7-4caa-9379-45e6e6ccf5b6",
  "type": "FINISH",
  "title": "이제 마무리할 시간이에요",
  "body": "진행 중인 콘텐츠를 저장하고 마무리해 주세요.",
  "tag": "nextstation-b7458e9d-5dc7-4caa-9379-45e6e6ccf5b6-FINISH",
  "scheduledAt": "2026-11-12T00:24:00Z",
  "expiresAt": "2026-11-12T00:28:00Z",
  "arrivalAt": "2026-11-12T00:30:00Z",
  "minutesBeforeArrival": 5,
  "url": "/"
}
```

type은 FINISH / PREPARE / TEST. 테스트 알림의 alarmId, sessionId, arrivalAt은 null입니다.

프론트 연동 순서:

1. 알림 권한을 사용자 동작에서 요청하고 서비스 워커를 등록합니다.
2. 공개키로 `pushManager.subscribe({userVisibleOnly: true, applicationServerKey})`를 호출합니다.
3. `subscription.toJSON()`의 endpoint/keys와 저장된 deviceId로 구독 API를 호출합니다.
4. 세션 시작/보정 때 예약 PUT 응답을 IndexedDB에 저장합니다. 페이지 안내 시각도 응답의 scheduledAt을 사용합니다.
5. 서비스 워커는 현재 세션/최신 alarmId/만료 시각을 검사하고 알림을 표시합니다. `showNotification`에 payload의 tag를 전달하고 처리한 tag를 저장해 재전송 중복을 방지합니다.
6. 포그라운드 페이지와 서비스 워커는 `postMessage` 및 공유 상태로 동일 tag를 조율해 인앱 안내와 푸시가 중복 표시되지 않도록 합니다. FINISH에서 콘텐츠 저장·중단, PREPARE에서 하차 준비 화면 전환을 프론트가 처리합니다.
7. 세션 종료 시 DELETE를 호출하고 로컬 세션/대기 보정 요청을 종료 처리합니다.

Web Push는 즉시 수신을 보장하지 않습니다. 서버는 HIGH urgency와 최대 60초 TTL(잔여 유효시간이 짧으면 그 값)을 사용합니다. 서비스 워커에서도 expiresAt을 확인해야 합니다. 현재 세션은 로컬에 있으므로 서버만으로 취소 후 이미 접수된 알림을 숨길 수 없습니다.

## 7. 오류

```json
{
  "code": "INVALID_REMINDER_MINUTES",
  "message": "알림 시점은 3, 5, 7분 중 하나입니다.",
  "fields": {},
  "timestamp": "2026-11-12T00:00:00Z"
}
```

| HTTP | 대표 code | 대응 |
| --- | --- | --- |
| 400 | INVALID_REQUEST / INVALID_SUBSCRIPTION_KEYS / INVALID_PUSH_ENDPOINT | 형식/필수값 확인 |
| 400 | INVALID_REMINDER_MINUTES / INVALID_ARRIVAL_TIME | 시간/옵션 수정 |
| 404 | DEVICE_NOT_FOUND | 구독 선등록 |
| 409 | SUBSCRIPTION_ALREADY_REGISTERED / RESOURCE_CONFLICT | 기기 ID 또는 동시 요청 상태 확인 |
| 410 | SUBSCRIPTION_EXPIRED | 구독 재생성 → 재등록 → 현재 예약 PUT |
| 429 | TEST_PUSH_RATE_LIMITED | 30초 대기 |
| 502 | PUSH_HTTP_* / PUSH_NETWORK_ERROR 등 | 테스트 푸시 제공자 오류, 설정 확인 |
| 503 | PUSH_DISABLED | 서버 VAPID 설정/활성화 |

## 실기기 확인

- HTTPS 프론트·백엔드 도메인과 CORS_ALLOWED_ORIGINS를 설정합니다.
- 운영자 연락처와 VAPID 키를 환경변수에 넣고 PUSH_ENABLED=true로 실행합니다.
- iPhone Safari에서 홈 화면에 추가한 PWA를 열고 권한 허용 후 구독 등록 → 테스트 푸시를 요청합니다.
- Android Chrome에서도 같은 흐름을 확인합니다. 화면을 끄거나 다른 앱으로 전환한 상태를 포함합니다.
- 현재 +10분을 도착 시각으로 예약하면 기본 FINISH는 +4분, PREPARE는 +8분에 발송됩니다.
- 보정으로 도착 시각을 변경한 후 GET에서 새 alarmId/시각을 확인하고 이전 시각에 알림이 오지 않는지 확인합니다.
- DELETE 후 해당 세션 알림이 오지 않는지 확인합니다.
- 예약 후 서버를 종료·재시작하고 동일 DB 파일에서 미발송 예약이 유지되는지 확인합니다.
- 비행기 모드 복구/권한 취소/구독 만료를 확인하고, 실제 1호선 탑승에서 예상 도착·발송·수신 시각을 기록합니다.

이 저장소의 자동 테스트는 물리 단말 수신 및 실제 열차 도착 시각을 검증하지 않습니다. 해당 항목을 확인한 후 GitHub 이슈의 실기기 완료 체크를 업데이트하세요.
