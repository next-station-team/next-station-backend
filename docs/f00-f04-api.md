# 다음역 프론트 연동 API 명세서

**F-00 푸시 구독 / F-04 하차 알림 · v1.0.0 · 2026-11-12**

프론트는 기기별 푸시 구독을 등록하고 예상 도착 시각을 서버에 전달합니다. 서버가 마무리 알림과 하차 준비 알림을 계산해 예약하고 Web Push로 발송합니다. 페이지의 콘텐츠 중단·저장·화면 전환과 서비스 워커의 알림 표시는 프론트에서 구현합니다.

기준: main의 [fa7bde0](https://github.com/next-station-team/next-station-backend/commit/fa7bde0b3d99724c0ba999b67e404406c71cd237), [구현 PR #5](https://github.com/next-station-team/next-station-backend/pull/5).

함께 전달하는 파일: [OpenAPI 3.0.3 명세](openapi.yaml), [Postman 컬렉션](postman_collection.json).

서버 설정·상시 실행·업데이트 방법은 [운영 인수인계](operations-handoff.md)를 참고하세요.

## 1. 연동 전에 확인할 사항

| 항목 | 계약 |
| --- | --- |
| 로컬 API 주소 | `http://localhost:8080` |
| 배포 API 주소 | 서버 담당자가 확인한 HTTPS 주소를 사용. main 병합과 서버 배포는 별개 |
| 요청 형식 | 본문이 있는 POST/PUT은 `Content-Type: application/json` |
| 응답 형식 | JSON. **취소 성공 204에는 본문 없음** |
| 공통 응답 래퍼 | 없음. `data`, `success` 등으로 감싸지 않음 |
| 사용자 로그인 | 없음. JWT/세션 쿠키 사용 안 함 |
| 기기 식별 | `deviceId`: 최초 한 번 생성해 유지하는 UUID |
| 통학 식별 | `sessionId`: 통학 시작마다 생성하는 UUID. 보정 때 유지 |
| 기기 헤더 | 테스트 발송·예약·조회·취소는 `X-Device-Id: <deviceId>` 필수 |
| 시각 | ISO 8601 문자열. 요청에 `Z` 또는 `+09:00` 등 오프셋 필수, 응답 UTC |
| 도착 시각 범위 | 서버 현재 시각보다 미래이며, 현재부터 24시간 이내 |
| CORS | 기본 허용 origin은 `http://localhost:3000`. 실제 프론트 origin을 서버에 등록 |

`deviceId`는 `crypto.randomUUID()`로 생성해 IndexedDB/localStorage 등에 저장합니다. 값을 아는 주체가 해당 기기의 예약에 접근할 수 있으므로 URL, 분석 이벤트, 공유 로그에 넣지 마세요. 같은 브라우저 구독을 등록할 때 다른 deviceId를 새로 만들지 않습니다.

**푸시 기본 설정은 비활성화입니다.** `/api/health`가 UP이어도 실제 푸시 설정은 완료되지 않았을 수 있습니다. 서버 담당자가 VAPID 키·연락처를 설정하고 `PUSH_ENABLED=true`로 활성화해야 합니다. 구독 등록과 예약 PUT의 200도 단말 수신이나 푸시 활성화를 보증하지 않습니다.

이 문서의 고정 시각은 형식 예시입니다. 실행 시에는 `new Date(Date.now() + 10 * 60_000).toISOString()`처럼 미래 시각을 생성하세요. Postman 예약 요청은 미래 시각을 자동 생성합니다.

## 2. API 목록

| 기능 | Method | Path | 기기 ID 위치 | 성공 |
| --- | --- | --- | --- | --- |
| 서버 확인 | GET | `/api/health` | 없음 | 200 |
| VAPID 공개키 | GET | `/api/push/vapid-public-key` | 없음 | 200 |
| 구독 등록·갱신 | POST | `/api/push/subscriptions` | **JSON 본문** | 200 |
| 테스트 푸시 | POST | `/api/push/test` | 헤더 | 202 |
| 예약 생성·보정 | PUT | `/api/sessions/{sessionId}/alarms` | 헤더 | 200 |
| 예약 조회 | GET | `/api/sessions/{sessionId}/alarms` | 헤더 | 200 |
| 예약 취소 | DELETE | `/api/sessions/{sessionId}/alarms` | 헤더 | 204 |

세션 생성 API는 없습니다. 프론트에서 생성한 sessionId로 바로 예약합니다. 현재 별도 구독 삭제 API와 수신 확인(ACK) API도 없습니다.

## 3. F-00 푸시 구독

### 3.1 서버 상태

`GET /api/health` — 헤더/본문 불필요.

```json
{"status":"UP"}
```

### 3.2 VAPID 공개키 조회

`GET /api/push/vapid-public-key` — 헤더/본문 불필요.

200 응답:

```json
{"publicKey":"<서버 VAPID 공개키 Base64URL>"}
```

`publicKey`는 브라우저 `pushManager.subscribe()`의 `applicationServerKey`로 사용합니다. 구독 응답의 `keys.p256dh`와는 다른 값입니다. 개인키는 프론트에서 사용하지 않습니다.

실패: **503 `PUSH_DISABLED`** → 서버 푸시 설정 확인. 프론트에서 권한 요청을 반복해 해결할 오류가 아닙니다.

### 3.3 구독 등록 또는 갱신

`POST /api/push/subscriptions`

```json
{
  "deviceId": "ad216e40-432b-4e3e-bb9a-1d9f17291720",
  "endpoint": "https://fcm.googleapis.com/wp/<브라우저의 실제 endpoint>",
  "keys": {
    "p256dh": "<브라우저 구독의 p256dh>",
    "auth": "<브라우저 구독의 auth>"
  }
}
```

| 필드 | 타입 | 필수 | 제약 및 출처 |
| --- | --- | --- | --- |
| deviceId | string(UUID) | O | 프론트에서 생성·유지 |
| endpoint | string | O | `PushSubscription.toJSON().endpoint`, 최대 2,048자 |
| keys | object | O | `PushSubscription.toJSON().keys` |
| keys.p256dh | string | O | 최대 128자, Base64URL 디코딩 시 65바이트 비압축 P-256 공개키 |
| keys.auth | string | O | 최대 64자, Base64URL 디코딩 시 16바이트 |

`subscription: {...}`로 한 번 더 감싸지 않습니다. 꺾쇠가 들어간 예시 값은 실제 브라우저 구독값으로 바꿔야 합니다.

기본 지원 endpoint 호스트는 `fcm.googleapis.com`, `web.push.apple.com`, `updates.push.services.mozilla.com` 및 해당 Mozilla 하위 도메인입니다. HTTPS와 기본 443 포트를 사용해야 하며 사용자 정보·fragment를 포함할 수 없습니다. 다른 제공자는 백엔드 허용 목록 확정이 필요합니다.

200 응답(신규/갱신 동일):

```json
{
  "deviceId": "ad216e40-432b-4e3e-bb9a-1d9f17291720",
  "active": true,
  "createdAt": "2026-11-12T00:00:00Z",
  "updatedAt": "2026-11-12T00:00:00Z"
}
```

같은 deviceId는 기존 구독을 갱신하고 createdAt을 유지합니다. 다른 deviceId에 동일 endpoint가 등록되어 있으면 409입니다. 구독 만료 후 재등록해도 FAILED 예약은 자동 재생성되지 않으므로 현재 세션의 예약 PUT도 다시 호출합니다.

실패: 400 입력/구독키/endpoint 오류, 409 중복 구독 또는 동시 처리 충돌.

### 3.4 테스트 푸시

```http
POST /api/push/test
X-Device-Id: ad216e40-432b-4e3e-bb9a-1d9f17291720
```

본문 없음. 202 응답:

```json
{"status":"ACCEPTED"}
```

**기기별 발송 시도 간 30초 제한**이며 실패한 발송 시도도 포함됩니다. ACCEPTED는 제공자 접수 성공입니다. 화면이 꺼진 실기기에서 표시 여부를 직접 확인하세요.

실패: 400 헤더/UUID 오류, 404 미등록 기기, 409 동시 처리 충돌, 410 구독 만료, 429 빈도 제한, 502 제공자/네트워크 오류, 503 푸시 비활성화.

## 4. F-04 예약 생성과 위치 보정

### 4.1 요청

```http
PUT /api/sessions/b7458e9d-5dc7-4caa-9379-45e6e6ccf5b6/alarms
Content-Type: application/json
X-Device-Id: ad216e40-432b-4e3e-bb9a-1d9f17291720
```

```json
{
  "arrivalAt": "2026-11-12T09:30:00+09:00",
  "reminderMinutes": 5
}
```

| 필드 | 타입 | 필수 | 제약 |
| --- | --- | --- | --- |
| sessionId | path string(UUID) | O | 새 통학은 새 값, 위치 보정은 같은 값 |
| arrivalAt | string(date-time) | O | 실제 예상 도착 시각. 서버 현재 이후 24시간 이내 |
| reminderMinutes | integer 또는 null | X | 3 / 5 / 7. 생략 또는 null이면 5 |

**프론트는 여유분을 빼지 않은 도착 시각만 보냅니다.** 알림 발송 시각은 서버가 계산합니다.

### 4.2 발송 시각과 만료

서버 기본 여유분은 1분입니다.

| type | 의미 | 발송 예정 시각 | 만료 시각 |
| --- | --- | --- | --- |
| FINISH | 콘텐츠 마무리·저장 안내 | 도착 − reminderMinutes − 1분 | PREPARE 발송 예정 시각 |
| PREPARE | 하차 준비 안내 | 도착 − 1분 − 1분 | 예상 도착 시각 |

도착 09:30 기준:

| 선택 | FINISH | PREPARE |
| --- | --- | --- |
| 3분 | 09:26 | 09:28 |
| 5분(기본) | 09:24 | 09:28 |
| 7분 | 09:22 | 09:28 |

페이지 타이머도 **응답의 scheduledAt**을 기준으로 맞춥니다. 프론트에서 다시 시간을 계산하면 서버 설정 변경 시 어긋날 수 있습니다.

### 4.3 200 응답

응답은 다음 객체의 배열이며 `data` 래퍼가 없습니다.

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

| 응답 필드 | 타입 | 의미 |
| --- | --- | --- |
| alarmId | string(UUID) | 서버 예약 ID. 미발송 예약 보정 시 교체됨 |
| sessionId | string(UUID) | 통학 세션 ID |
| type | FINISH / PREPARE | 알림 단계 |
| scheduledAt | string(date-time) | 여유분 적용 후 발송 예정 시각 |
| expiresAt | string(date-time) | 유효기간 끝. 이 시각 이상이면 만료 |
| arrivalAt | string(date-time) | 해당 예약 생성 시의 예상 도착 시각 |
| minutesBeforeArrival | number | FINISH는 3/5/7, PREPARE는 1. 여유분과 별개 |
| status | string enum | PENDING / SENT / FAILED / EXPIRED |
| attemptCount | number | 실제 서버 발송 시도 횟수 |
| sentAt | string 또는 null | 제공자 접수 성공 시각. 단말 수신 시각 아님 |
| lastErrorCode | string 또는 null | 최근 발송 오류 코드. PENDING 재시도 중에도 값이 있을 수 있음 |

배열은 scheduledAt 오름차순입니다. 동일 시각의 순서는 보장하지 않으므로 `alarms[0]`에 의존하지 말고 `find(a => a.type === 'FINISH')`처럼 type으로 찾습니다.

### 4.4 보정과 임박한 도착의 처리

- 보정 요청도 **같은 sessionId로 PUT**합니다. 서버는 미발송 예약을 한 트랜잭션에서 삭제하고 새 ID로 생성합니다.
- 이미 SENT인 단계는 다시 발송하지 않습니다. 그 기록의 alarmId/arrivalAt/scheduledAt도 이전 값이 유지됩니다. 최신 도착 시각을 SENT 기록에서 역으로 읽지 마세요.
- 같은 PUT을 반복해도 예약 개수가 계속 늘지는 않지만 미발송 alarmId는 다시 바뀝니다. 매번 마지막 성공 응답으로 로컬 예약을 갱신하세요.
- FINISH 시각만 지났고 PREPARE 시각 전이면 FINISH가 즉시 발송 대상이 됩니다.
- PREPARE 시각까지 지났다면 FINISH는 EXPIRED, PREPARE만 즉시 발송 대상입니다. 즉시 대상의 scheduledAt은 요청 처리 시점으로 보정됩니다.
- 이미 도착했거나 현재와 같은 arrivalAt은 400입니다. 잘못된 요청은 기존 예약을 삭제하지 않습니다.
- **SENT는 제공자 접수 완료**입니다. PENDING은 대기/재시도 상태이고, 스케줄러 비활성화 시 예정 시각이 지나도 PENDING으로 남을 수 있습니다. 화면에서는 expiresAt도 확인합니다.

실패: 400 입력/도착 시각/선택값 오류, 404 구독 미등록, 409 동시 처리 충돌, 410 구독 만료.

## 5. 예약 조회와 취소

### 조회

`GET /api/sessions/{sessionId}/alarms` + `X-Device-Id`

200: 예약 응답 배열. 해당 기기에 세션 예약이 없으면 `[]`입니다. 미등록 기기는 404이며, 구독이 비활성화된 등록 기기는 조회할 수 있습니다.

| status | 프론트 해석 |
| --- | --- |
| PENDING | 예약 또는 재시도 대기. 수신 성공 표시를 하지 않음 |
| SENT | 제공자 접수 완료. 단말 표시를 확인한 상태는 아님 |
| FAILED | 구독 만료, 영구 오류 또는 재시도 소진. lastErrorCode 확인 |
| EXPIRED | 유효기간 경과. 새 안내를 표시하지 않음 |

### 취소

`DELETE /api/sessions/{sessionId}/alarms` + `X-Device-Id`

**204 No Content**. `response.json()`을 호출하지 않습니다. 미등록 기기, 없는 세션, 반복 취소도 204입니다. 구독이 만료된 기기도 취소할 수 있습니다. 다른 기기의 예약에는 영향을 주지 않습니다.

취소는 해당 세션의 예약과 발송 기록을 삭제합니다. 제공자가 이미 접수한 푸시는 회수할 수 없습니다. 프론트도 세션을 종료 표시하고 해당 세션의 예약 타이머·대기 중 보정 요청을 폐기해야 합니다.

취소 후 같은 sessionId로 PUT하면 새 예약을 만들 수 있습니다. 취소한 세션의 오래된 오프라인 요청을 다시 보내지 마세요.

## 6. 서비스 워커가 받는 Web Push

HTTP 응답이 아니라 `push` 이벤트에서 읽는 JSON입니다.

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

| 필드 | 규칙 |
| --- | --- |
| type | FINISH / PREPARE / TEST |
| alarmId, sessionId | 실제 알림은 UUID, TEST는 null |
| title, body | 사용자 표시 문구 |
| tag | 실제 알림은 `nextstation-{sessionId}-{type}`. 재시도/보정해도 동일 단계의 tag 유지 |
| scheduledAt, expiresAt | UTC 시각. TEST 유효기간은 생성부터 60초 |
| arrivalAt | 예상 도착 시각. TEST는 null |
| minutesBeforeArrival | FINISH 3/5/7, PREPARE 1, TEST 0 |
| url | 현재 구현은 `/`. 프론트 앱의 루트로 해석 |

TEST의 title은 `다음역 테스트 알림`, body는 `푸시 알림이 정상적으로 연결되었습니다.`, tag는 `nextstation-test`입니다. TEST를 실제 세션 알림처럼 처리하지 않습니다.

프론트와 서비스 워커는 다음 데이터를 공유할 수 있도록 IndexedDB 등에 저장합니다. 서비스 워커는 localStorage를 사용할 수 없습니다.

| 저장 데이터 | 용도 |
| --- | --- |
| 현재 sessionId 및 종료 여부 | 이전/종료 세션 알림 구분 |
| 최신 alarmId, type, scheduledAt, expiresAt | 보정 전 예약 및 만료 알림 구분 |
| 해당 세션에서 이미 처리한 type/tag | 재전송에 따른 화면 전환·토스트 중복 방지 |
| 마지막 서버 반영 성공 상태 | 오프라인 보정 대기 표시 |

`tag`만으로는 보정 전 메시지를 구별할 수 없으므로 alarmId도 함께 봅니다. TEST의 tag는 항상 같으므로 영구 중복 차단 대상으로 사용하지 마세요. `showNotification`의 tag는 표시된 알림의 대체에 쓰이며, 콘텐츠 저장/화면 전환의 중복 실행 방지는 별도 상태가 필요합니다.

FINISH에서는 콘텐츠 마무리·저장을, PREPARE에서는 하차 준비 화면 전환을 처리합니다. 백그라운드 수신 시 알림 클릭을 통해 앱을 열고 저장한 세션 상태를 반영합니다. 포그라운드에서는 서비스 워커와 `postMessage`로 같은 알림의 인앱 안내 중복을 조율합니다. `userVisibleOnly` 구독의 알림 표시 정책을 고려해 푸시를 무조건 조용히 소비하는 방식은 피하고 실제 대상 기기로 확인합니다.

기본 서버는 제공자 일시 오류에 최대 총 3회(실패 후 10초, 다시 실패 후 20초) 시도합니다. 남은 유효기간이 부족하면 재시도하지 않습니다. 404/410 제공자 응답은 구독을 비활성화합니다. 실제 알림 TTL은 최대 60초 또는 남은 유효시간 중 작은 값이고, 네트워크 상황에 따른 지연은 발생할 수 있습니다.

## 7. 오류 응답과 프론트 대응

애플리케이션이 처리하는 오류의 공통 형식:

```json
{
  "code": "INVALID_REMINDER_MINUTES",
  "message": "알림 시점은 3, 5, 7분 중 하나입니다.",
  "fields": {},
  "timestamp": "2026-11-12T00:00:00Z"
}
```

분기는 `code`와 HTTP 상태를 사용하고 message 문자열에는 의존하지 않습니다. `fields`는 필드 경로 → 메시지 객체이며 검증 오류가 없으면 `{}`입니다. CORS 차단, 잘못된 HTTP 메서드/Content-Type, 프록시/서버의 미처리 오류는 위 형식이나 JSON을 보장하지 않으므로 일반 네트워크 오류 처리도 필요합니다.

| HTTP | code | 적용 API | 프론트 대응 |
| --- | --- | --- | --- |
| 400 | INVALID_REQUEST | 구독/테스트/예약/조회/취소 | 필수 헤더, UUID, JSON, 필드 오류 확인 |
| 400 | INVALID_SUBSCRIPTION_KEYS | 구독 | 브라우저의 실제 keys로 다시 구성 |
| 400 | INVALID_PUSH_ENDPOINT | 구독 | 실제 endpoint와 지원 제공자 확인 |
| 400 | INVALID_REMINDER_MINUTES | 예약 PUT | 3/5/7 또는 생략/null로 수정 |
| 400 | INVALID_ARRIVAL_TIME | 예약 PUT | 최신 위치 기준 미래 도착 시각 재계산 |
| 404 | DEVICE_NOT_FOUND | 테스트/예약/조회 | 기기 구독 등록 후 재시도 |
| 409 | SUBSCRIPTION_ALREADY_REGISTERED | 구독 | 기존 deviceId 복원. 무조건 새 ID를 만들어 반복하지 않음 |
| 409 | RESOURCE_CONFLICT | 쓰기 API | 동일 기기의 동시 요청을 정리하고 최신 요청만 재시도 |
| 410 | SUBSCRIPTION_EXPIRED | 테스트/예약 PUT | 구독 재생성 → 등록 → 유효한 현재 세션 예약 PUT |
| 429 | TEST_PUSH_RATE_LIMITED | 테스트 | 최소 30초 뒤 재시도 |
| 502 | PUSH_HTTP_* | 테스트 | 푸시 제공자 오류. 운영자에게 코드 전달 |
| 502 | PUSH_NETWORK_ERROR / PUSH_INTERRUPTED / PUSH_ENCODING_ERROR | 테스트 | 네트워크/설정 확인 후 제한 간격을 지켜 재시도 |
| 503 | PUSH_DISABLED | 공개키/테스트 | 서버 VAPID/활성화 설정 확인 |

예약 자동 발송의 오류는 이미 완료된 PUT의 HTTP 상태를 바꾸지 않습니다. 이후 GET의 status/lastErrorCode로 확인합니다. 실패한 예약을 다시 설정하려면 유효한 구독과 현재 예상 도착 시각으로 PUT을 호출합니다.

## 8. 프론트 호출 예시

브라우저 측 코드입니다. SSR에서 localStorage에 접근하지 않도록 클라이언트 흐름에서만 초기화합니다. API_BASE는 프로젝트의 환경변수 값으로 대체하세요.

```javascript
const API_BASE = 'http://localhost:8080';
const deviceId = localStorage.getItem('nextstation.deviceId') ?? crypto.randomUUID();
localStorage.setItem('nextstation.deviceId', deviceId);

async function api(path, { method = 'GET', body, withDevice = false } = {}) {
  const headers = {};
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (withDevice) headers['X-Device-Id'] = deviceId;
  const response = await fetch(`${API_BASE}${path}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
    credentials: 'omit',
  });
  const raw = await response.text(); // 204는 빈 문자열
  let result;
  try { result = raw ? JSON.parse(raw) : undefined; }
  catch { result = undefined; }
  if (!response.ok) {
    throw Object.assign(new Error(result?.message ?? `HTTP ${response.status}`), {
      httpStatus: response.status,
      code: result?.code ?? 'HTTP_ERROR',
      fields: result?.fields ?? {},
    });
  }
  return result;
}

// 서비스 워커 등록 및 권한 허용 후 얻은 실제 PushSubscription을 전달
async function registerSubscription(subscription) {
  const { endpoint, keys } = subscription.toJSON();
  return api('/api/push/subscriptions', {
    method: 'POST', body: { deviceId, endpoint, keys },
  });
}

// 통학 시작 시 한 번 만들고 보정/취소까지 유지
const sessionId = crypto.randomUUID();
const alarmPath = `/api/sessions/${sessionId}/alarms`;
async function reserve(arrivalAt, reminderMinutes = 5) {
  return api(alarmPath, {
    method: 'PUT', withDevice: true,
    body: { arrivalAt, reminderMinutes },
  });
}
async function loadAlarms() {
  return api(alarmPath, { withDevice: true });
}
async function cancelAlarms() {
  await api(alarmPath, { method: 'DELETE', withDevice: true });
}
```

`reserve(new Date(Date.now() + 10 * 60_000).toISOString(), 5)`로 테스트할 수 있습니다. 실제 앱에서는 sessionId를 세션 상태에 보관해 새로고침 후에도 재사용하고, PUT 성공 시 응답 배열도 저장하세요. 이 예시는 HTTP 호출 부분이며 요청 직렬화·오프라인 큐·권한 UI·서비스 워커는 별도 구현합니다.

## 9. 앱에서 연결할 순서

1. 서비스 워커를 등록하고 공개키를 준비합니다. HTTPS 환경에서 사용자가 알림 활성화 버튼을 누르는 흐름으로 권한과 구독을 요청합니다. [PushManager 공식 참고](https://developer.mozilla.org/en-US/docs/Web/API/PushManager/subscribe)
2. iPhone은 대상 홈 화면 PWA에서 설치·권한 흐름을 확인합니다. 알림 권한 요청은 사용자 동작에 연결합니다. [WebKit 안내](https://webkit.org/blog/13878/web-push-for-web-apps-on-ios-and-ipados/)
3. 기존 브라우저 구독이 있으면 같은 deviceId와 함께 등록·갱신합니다. 없다면 VAPID 공개키를 사용해 구독을 생성합니다.
4. 테스트 푸시를 한 번 보내고 화면 꺼짐/다른 앱 사용 중 수신을 확인합니다.
5. 통학 시작 시 sessionId 생성 → 도착 예상 시각 계산 → 예약 PUT → 응답 저장 → 페이지 타이머 설정 순서로 연결합니다.
6. 위치 보정 시 같은 sessionId에 새 arrivalAt으로 PUT하고, 성공 응답으로 예약을 교체합니다.
7. 세션 종료 시 로컬 종료 상태를 기록하고, 진행 중 PUT을 정리한 뒤 DELETE를 보냅니다. 성공 시 예약/보정 큐를 비웁니다.

오프라인 보정은 같은 세션의 **최신 변경만** 보관하고 '알림 시각 반영 대기 중'을 표시합니다. 서버가 받지 못한 동안 기존 예약이 유효합니다. 연결 복구 후 이미 종료/도착한 세션을 재예약하지 마세요.

동일 기기·세션의 PUT/DELETE를 동시에 보내지 않습니다. 현재 API에는 버전 조건이나 Idempotency-Key가 없어 늦게 처리된 과거 요청이 최신 값을 덮어쓸 수 있습니다. 네트워크 오류로 결과가 불명확하면 GET으로 현재 예약을 확인하고 최신 상태를 다시 반영하세요.

## 10. Postman 사용과 연동 완료 기준

`postman_collection.json`을 가져온 뒤 컬렉션 변수를 설정합니다.

| 변수 | 설정 |
| --- | --- |
| baseUrl | 로컬 기본 `http://localhost:8080`, 실제 배포 확인 후 변경 |
| deviceId | 실제 앱에서 쓰는 UUID. 비어 있으면 도구에서 테스트용 UUID 생성 |
| sessionId | 테스트 통학 UUID. 비어 있으면 생성, 새 통학마다 변경 |
| endpoint / p256dh / auth | 대상 브라우저에서 얻은 실제 구독 정보 |
| arrivalAt | 예약/보정 요청에서 자동으로 미래 시각 설정 |
| vapidPublicKey | 공개키 응답에서 자동 저장 |

컬렉션 순서대로 실행하면 테스트 푸시를 실제 발송하고, 예약 생성·조회·보정 후 취소합니다. 예약 수신 시각까지 관찰하려면 05 예약 요청 뒤에 멈추고 기다립니다. 전체 실행은 바로 보정·취소하므로 예약 푸시가 오지 않는 것이 정상입니다. 실구독값을 채운 컬렉션은 공유용으로 다시 내보내지 마세요.

- [ ] iPhone 홈 화면 PWA와 Android Chrome에서 설치·권한·구독 등록 확인
- [ ] 실제 휴대폰에서 테스트 푸시 수신 확인
- [ ] 3/5/7분 선택, 기본값 5, 1분 여유 적용 시각 확인
- [ ] 보정 응답으로 로컬 예약이 바뀌고 이전 예약 알림이 안내를 중복 실행하지 않음
- [ ] 종료 후 예약·페이지 타이머·대기 보정 요청이 남지 않음
- [ ] 404/410 구독 복구, 429 재시도 간격, 503 서버 설정 오류 처리
- [ ] 오프라인에서 반영 대기 표시 및 복구 시 최신 보정만 전송
- [ ] 앱 재실행 시 현재 sessionId와 서버 예약을 복원
- [ ] 화면 꺼짐/다른 앱 사용/실제 탑승 조건에서 알림 수신 시각 기록

백엔드 자동 검증은 38개 테스트 및 실행 JAR의 API 확인을 통과했습니다. 실제 기기 수신·탑승 검증은 위 연동 완료 기준으로 확인합니다. OpenAPI 파일은 [3.0.3 표준](https://spec.openapis.org/oas/v3.0.3.html) 형식으로 제공하며, Web Push payload는 HTTP 경로와 별도로 `components.schemas.WebPushPayload`에 정의되어 있습니다.
