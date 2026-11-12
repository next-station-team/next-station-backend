# next-station-backend

다음역 F-00 푸시 구독 등록 / F-04 하차 알림 예약·수정·취소·발송 백엔드입니다.

기준: `main`의 `ba19a03`, [F-00 #1](https://github.com/next-station-team/next-station-backend/issues/1),
[F-04 #2](https://github.com/next-station-team/next-station-backend/issues/2), 기능명세서 F-00/F-04.

팀원용 시작점: [프론트 API 명세](docs/f00-f04-api.md) · [서버 설치 및 운영 인수인계](docs/operations-handoff.md) · [OpenAPI](docs/openapi.yaml) · [Postman](docs/postman_collection.json).

Linux EC2에서는 `scripts/deploy.py`가 VAPID 키 생성/가져오기, 비밀 환경설정, 영구 H2 경로, systemd 상시 실행과 상태 확인을 처리합니다. 실제 프론트 origin과 운영 연락처를 지정해야 하며, 기존 HTTPS Nginx의 연결 포트는 서버 담당자가 적용합니다. 설치 명령과 업데이트·롤백 절차는 운영 인수인계에 있습니다. 기본 서버는 127.0.0.1:8081에 바인딩하며 기존 서버를 강제 종료하지 않습니다.

GitHub Actions는 테스트와 Linux 설치 검증 후 실행 JAR을 artifact로 보관합니다. 팀 EC2에 자동 배포하지 않으며 서버 접속 권한은 필요합니다.

## 개발 환경

| 항목 | 구성 |
| --- | --- |
| Java | 17 |
| Spring Boot | 4.1.2 (원본 유지) |
| Gradle Wrapper | 9.8.2 (원본 유지, 다운로드 timeout/retry 확대) |
| 데이터 접근 | Spring Data JPA / Hibernate |
| DB | 기존 H2 의존성 유지, `./data/nextstation.mv.db` 파일 저장 |
| 푸시 | VAPID / aes128gcm Web Push, Java HttpClient |

PDF의 SQLite 제안 대신 저장소에 이미 설정된 H2를 사용합니다. 메모리 DB가 아니므로 동일 파일과 작업 디렉터리를 유지하면 재시작해도 예약이 남습니다. 배포 DB는 담당자와 최종 확정해야 합니다. `schema.sql`로 초기 테이블을 만들고 JPA `validate`로 검사합니다. 기존 테이블 변경이 필요한 배포부터는 버전별 마이그레이션을 추가하세요.

## 실행

Windows PowerShell, 프로젝트 루트에서:

```powershell
.\gradlew.bat test bootJar
.\gradlew.bat bootRun
```

macOS/Linux:

```bash
./gradlew test bootJar
./gradlew bootRun
```

`GET http://localhost:8080/api/health` → `{"status":"UP"}`.
기본값은 `PUSH_ENABLED=false`입니다. 키 없이 서버/DB/API를 실행할 수 있지만 실제 발송은 하지 않습니다. 공개키 조회·테스트 푸시는 503을 반환하고 예약은 PENDING으로 남습니다. 가짜 발송 성공을 반환하지 않습니다.

### 실제 푸시 활성화

```powershell
.\gradlew.bat generateVapidKeys
```

명령은 `.env.vapid`에 새 공개키·개인키를 생성합니다. 이미 파일이 있으면 생성 단계를 생략하세요. 개인키는 콘솔에 출력하지 않으며 기존 파일을 덮어쓰지 않습니다. 이 파일은 Git에서 제외됩니다. 운영에서는 최초 생성한 키 쌍을 안전하게 보관해 재시작 시에도 동일하게 사용하세요. 키를 교체하면 프론트 구독도 다시 만들어야 합니다.

```powershell
Get-Content .env.vapid | ForEach-Object {
    $parts = $_ -split '=', 2
    [Environment]::SetEnvironmentVariable($parts[0], $parts[1], 'Process')
}
$env:VAPID_SUBJECT = 'mailto:실제운영자이메일'
$env:PUSH_ENABLED = 'true'
$env:CORS_ALLOWED_ORIGINS = 'http://localhost:3000,https://실제프론트도메인'
.\gradlew.bat bootRun
```

`.env`/`.env.vapid`는 Spring Boot가 자동으로 읽지 않습니다. 위처럼 프로세스 환경변수로 주입하거나 서버 서비스의 EnvironmentFile을 설정하세요. 설정 예시는 [.env.example](.env.example)에 있습니다. 운영 DB URL은 작업 디렉터리 변경에 영향을 받지 않는 절대 경로를 권장합니다.

## API

| 메서드 | 경로 | 기능 |
| --- | --- | --- |
| GET | `/api/health` | 서버 확인 |
| GET | `/api/push/vapid-public-key` | 프론트 구독용 공개키 |
| POST | `/api/push/subscriptions` | 기기별 구독 등록/갱신 |
| POST | `/api/push/test` | 등록 기기 테스트 푸시 |
| PUT | `/api/sessions/{sessionId}/alarms` | 두 단계 예약 등록/보정 |
| GET | `/api/sessions/{sessionId}/alarms` | 예약·발송 상태 조회 |
| DELETE | `/api/sessions/{sessionId}/alarms` | 해당 기기·세션 예약 취소 |

요청/응답 예시, 오류 코드, 프론트 연결 절차는 [API 명세](docs/f00-f04-api.md)를 참고하세요. 이슈가 참조하는 별도 팀 API 명세는 저장소에서 확인되지 않아 위 계약을 정의했습니다. 이미 합의된 URL/JSON이 있다면 컨트롤러/DTO를 그 계약에 맞춰 조정하면 됩니다.

## 예약 처리 규칙

- FINISH: `arrivalAt - reminderMinutes(기본 5분, 3/5/7 선택) - safetyMargin(기본 1분)`.
- PREPARE: `arrivalAt - 1분 - safetyMargin(기본 1분)`.
- 예: 도착 09:30, 기본 설정 → FINISH 09:24 / PREPARE 09:28. 프론트 타이머는 응답의 `scheduledAt`을 사용합니다.
- 보정 PUT은 같은 기기·세션의 미발송 예약을 한 트랜잭션에서 삭제하고 재등록합니다. 이미 SENT인 단계는 기록을 유지해 재발송하지 않습니다. 새 통학에는 새 sessionId를 사용하세요.
- 이미 지난 FINISH 시각이라도 PREPARE 시각 전이면 즉시 발송 대상입니다. PREPARE 시각까지 지났다면 FINISH는 EXPIRED, PREPARE만 즉시 발송합니다.
- FINISH의 유효기간은 PREPARE 시각까지, PREPARE는 도착 시각까지입니다. 도착 이후에는 보내지 않습니다.
- `@Scheduled`가 DB의 PENDING 예약을 기본 1초마다 조회합니다. 재시작 후에도 DB에서 자동 복구합니다.
- 제공자 2xx 응답은 SENT, 404/410은 구독 비활성화, 408/429/5xx/네트워크 오류는 10초·20초 간격으로 최대 총 3회 시도합니다. 그 외 오류는 FAILED입니다. 남은 유효기간 안에 재시도할 수 없으면 EXPIRED입니다.
- 동일 기기에 대한 보정·취소·발송은 DB 잠금으로 순서대로 처리합니다. 이미 제공자가 접수한 푸시는 회수할 수 없습니다. 발송 중이라면 취소가 HTTP timeout(기본 5초) 또는 DB lock timeout 동안 기다리거나 409가 날 수 있습니다.

## 검증과 운영 범위

`./gradlew test`는 구독/입력 검증, 두 단계 시간 계산, 보정·취소, 기기 격리, 동시 발송, 실패 재시도, 실파일 DB 재시작 복구, Web Push 암호화·VAPID 서명 요청 생성을 검증합니다. 외부 푸시 HTTP 응답은 테스트 대역을 사용합니다.

이번 구현의 실행 결과는 [검증 기록](docs/verification.md)에 정리했습니다.

H2 파일 DB를 쓰는 단일 서버 MVP 구성입니다. 여러 서버 인스턴스로 확장하려면 공유 DB와 작업 큐/분산 처리 전략이 필요합니다. 현재는 한 예약씩 제한 시간 내 발송하므로 트래픽이 많거나 제공자가 느리면 알림 지연이 늘어날 수 있습니다.

SENT는 푸시 제공자의 접수 상태이며 단말 표시를 보증하지 않습니다. 제공자 접수 직후 서버가 비정상 종료되면 DB 반영 전이라 재전송될 수 있습니다. payload의 동일 `tag`로 프론트/서비스 워커에서 중복을 처리하세요. 이미 전달된 구버전 푸시도 서버에서 회수할 수 없으므로 현재 세션·예약 ID·만료 시각을 함께 확인해야 합니다.

로그인 없는 MVP에서는 무작위 deviceId가 기기 접근용 비밀값 역할을 합니다. `crypto.randomUUID()`로 생성하고 URL·공유 로그에 노출하지 마세요. 공개 서비스로 확장할 때는 별도 기기 인증과 IP별 요청 제한을 추가하세요. 구독 endpoint는 허용된 HTTPS 푸시 제공자만 받으며 HTTP redirect를 따라가지 않습니다.

실제 iPhone 홈 화면 PWA, Android Chrome 수신과 1호선 탑승 지연 검증은 [프론트 연동 완료 기준](docs/f00-f04-api.md#10-postman-사용과-연동-완료-기준)을 통해 별도로 수행해야 합니다. 설치 안내·서비스 워커·페이지 화면 전환은 프론트 담당 범위입니다.

참고 구현: [webpush-java](https://github.com/web-push-libs/webpush-java), [Spring Boot 공식 문서](https://docs.spring.io/spring-boot/).
