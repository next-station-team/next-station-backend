# 구현 검증 기록

검증일: 2026-11-12 (Asia/Seoul)

- 기준 저장소: `next-station-team/next-station-backend`
- 기준 커밋: `ba19a03`
- 작업 브랜치: `feat/f00-f04-push-alarms`
- 환경: Windows, Eclipse Temurin Java 17.0.19, Spring Boot 4.1.2, Gradle 9.8.2

## 자동 테스트

`gradlew.bat test --no-daemon`: 성공. **38개 테스트, 실패 0, 오류 0, 건너뜀 0**.

| 테스트 | 개수 | 확인 내용 |
| --- | ---: | --- |
| AlarmApiIntegrationTest | 33 | 구독 갱신, 키·endpoint 검증, 3/5/7분, 시간대, 보정, 취소, 기기 범위, 만료, 재시도, 동시 처리, CORS |
| AlarmRestartIntegrationTest | 1 | 실제 H2 파일로 Spring context를 세 번 기동, 미발송 복구와 발송 완료 중복 방지 |
| WebPushGatewayTest | 3 | 실제 암호화·VAPID 서명 HTTP 요청 생성, 네트워크 오류, 잘못된 VAPID 설정 거부 |
| NextStationBackendApplicationTests | 1 | 애플리케이션 context 기동 |

최종 소스에서 `gradlew.bat test`를 다시 확인했고 모든 태스크가 최신 상태(UP-TO-DATE)였습니다.

## 실행 파일과 실제 HTTP 확인

`gradlew.bat bootJar generateVapidKeys --no-daemon`: 성공.

생성한 VAPID 키로 실행용 JAR을 포트 18081에서 기동한 후 실제 HTTP로 확인했습니다.

- health 200 / UP
- 공개키 조회와 생성한 공개키 일치
- 구독 등록 성공
- 두 단계 예약 생성
- 보정 시 예약 ID 교체
- 예약 조회
- 취소 후 빈 예약 목록

이 확인에서는 자동 발송을 비활성화했고 외부 푸시 제공자에게 메시지를 보내지 않았습니다. 확인용 서버 프로세스는 종료했습니다.

## 이슈별 상태

| 이슈 | 구현·확인 완료 | 남은 현장 확인 |
| --- | --- | --- |
| F-00 #1 | VAPID 생성 도구 및 로컬 키 생성, 공개키 API, 구독 등록·갱신, device 테이블, 테스트 발송 API | 실제 운영 연락처/프론트 도메인 설정, iPhone 홈 화면 PWA·Android Chrome 수신 |
| F-04 #2 | 예약·보정·취소 API, alarm 테이블, @Scheduled Web Push, 재시작 복구, 1분 여유, 3/5/7분 선택 | 실제 1호선 탑승 중 수신 지연과 하차 전 수신 여부 |

소스 ZIP에는 `.git`, 빌드 캐시, DB, `.env.vapid` 등 개인키 파일을 포함하지 않습니다. 로컬 작업 폴더에는 Git에서 제외된 `.env.vapid`가 있으므로 해당 폴더에서는 이를 사용할 수 있습니다. ZIP을 새로 푼 환경에서는 키 생성 명령을 실행하세요.

이슈의 실기기 완료 체크는 현장 수신 검증 후 갱신해야 합니다. 코드 반영 상태는 GitHub PR과 커밋 기록을 기준으로 확인하세요.
