# 다음역 백엔드 운영 인수인계

**F-00/F-04 · API 기준 main fa7bde0 · 운영 구성 v1.0 · 2026-11-12**

Java 17이 설치된 환경에서 GitHub 저장소를 내려받아 백엔드를 실행할 수 있습니다. 실제 알림을 운영하려면 서버에 VAPID 키와 환경변수를 넣고 프론트 PWA를 연결해야 합니다. 이 설정을 팀이 관리하는 상시 실행 서버에 완료하면 개발자의 개인 PC나 Codex가 켜져 있을 필요는 없습니다.

## 1. GitHub에 포함된 것과 서버에서 준비할 것

| GitHub에 포함 | 서버/팀에서 준비 |
| --- | --- |
| Java 17 / Spring Boot / Gradle Wrapper 구성 | Java 17 런타임·빌드 환경, 최초 의존성 다운로드 인터넷 연결 |
| 구독·예약 API, 발송 스케줄러, 테이블 초기화 | 백엔드 프로세스를 계속 실행할 서버와 서비스 설정 |
| VAPID 키 생성 명령, 환경변수 예시 | 실제 VAPID 키 쌍과 운영자 연락처 |
| 파일 기반 H2 설정, 미발송 예약 복구 | 영구 DB 경로, 파일 접근 권한 및 백업 |
| CORS 환경변수 설정 | 실제 프론트 origin 및 HTTPS 연결 |
| API 명세와 테스트 | 프론트 서비스 워커·권한·화면 연동, 실기기 검증 |

프론트 앱은 이 **백엔드 저장소에 포함되어 있지 않습니다**. 확인 시점의 팀 프론트 저장소에도 README만 있으므로 설치 안내·서비스 워커·알림 표시·콘텐츠 저장·페이지 화면 전환 구현이 필요합니다. 이 설치 도구는 백엔드를 운영하는 범위입니다.

## 2. 현재 확인된 배포 상태

2026-11-12 17:10 KST에 이슈에 기재된 서버 주소를 읽기 전용으로 확인했습니다.

| 요청 | 실제 응답 | 이번 코드의 기대 응답 |
| --- | --- | --- |
| `https://nextstation17.duckdns.org/api/health` | 200, 본문 `OK` | 200, `{"status":"UP"}` |
| `https://nextstation17.duckdns.org/api/push/vapid-public-key` | 404 | 활성화 시 200, 푸시 비활성화 시 503 |

서버 자체는 응답하지만 이번 푸시 API가 해당 주소에서 제공되는 상태는 아닙니다. 최신 JAR 배포 여부와 Nginx가 연결하는 백엔드 프로세스를 확인해야 합니다. 키가 없는 상태라면 이 코드에서는 503이어야 하므로 404를 단순 키 누락으로 처리하지 마세요.

GitHub main 병합이 곧 EC2 서버 배포를 뜻하지 않습니다. 추가한 GitHub Actions는 Java 테스트·운영 도구 검증·일회용 Linux runner에서 설치/재시작/롤백 확인 후 실행 JAR을 artifact로 보관합니다. 실제 팀 EC2로 SSH 자동 배포하는 워크플로는 아닙니다. 서버 접속 권한과 실제 프론트 origin은 팀 서버 담당자가 준비해야 합니다.

## 3. Linux 서버에 설치하기

EC2의 SSH 터미널에서 진행합니다. 브라우저의 `/api/health` 주소로는 설치 명령을 실행할 수 없습니다. Ubuntu 계열이라면 Java 17, Python 3, git, systemd가 필요합니다. 이미 설치되어 있으면 설치 단계를 생략합니다.

```bash
sudo apt-get update
sudo apt-get install -y openjdk-17-jdk python3 git
git clone https://github.com/next-station-team/next-station-backend.git
cd next-station-backend
chmod +x gradlew
./gradlew test bootJar --no-daemon
```

아래 두 값은 예시입니다. **실제 프론트 origin**(경로·끝 슬래시 제외)과 **운영 연락처**로 바꿉니다. 프론트가 아직 배포되지 않았다면 기능 확인용 origin을 쓰고, 배포 후 실제 origin으로 다시 설치 명령을 실행해 갱신합니다.

```bash
sudo python3 scripts/deploy.py install \
  --jar build/libs/backend-0.0.1-SNAPSHOT.jar \
  --frontend-origin https://YOUR-FRONTEND-DOMAIN \
  --vapid-subject mailto:YOUR-TEAM-CONTACT@example.com
```

설치 도구가 수행하는 작업:

1. 기존 키 파일이 없으면 서버에서 VAPID 키를 생성합니다. 기존 키가 있으면 유지합니다.
2. 키·CORS·DB 설정을 `/etc/nextstation-backend/backend.env`에 root 전용 권한으로 저장합니다.
3. `nextstation` 서비스 계정과 `/var/lib/nextstation-backend` 영구 DB 디렉터리를 준비합니다.
4. JAR을 `/opt/nextstation-backend/releases/`에 보관하고 `current.jar`로 연결합니다.
5. `nextstation-backend.service`를 설치해 부팅 시 실행·실패 시 재시작하도록 설정합니다.
6. 기본 `127.0.0.1:8081`에서 시작하고 health·공개키·CORS를 확인합니다. 기존 8080 서버는 자동 종료하지 않습니다.
7. 관리 중인 서비스 업데이트가 실패하면 이전 JAR/환경설정으로 복구를 시도합니다.

동일 이름의 기존 서비스가 이 도구로 만든 것이 아니면 덮어쓰지 않습니다. 이 경우 `--service-name nextstation-push`를 추가하면 관리 경로가 해당 이름으로 분리됩니다. 포트가 사용 중이면 `--port 8082`처럼 변경하고 Nginx도 같은 포트로 맞춥니다.

새 설치는 새로운 영구 DB를 사용합니다. 기존 운영 DB가 있다면 예약/구독을 잃지 않도록 기존 서비스·DB 경로·계정 정보를 먼저 확인하고 이관하세요. 운영 중인 H2 파일을 두 프로세스에서 동시에 열지 않습니다.

### 기존에 생성한 VAPID 키 가져오기

브라우저가 이미 특정 공개키로 구독했다면 같은 쌍의 개인키를 서버에 보관해야 합니다. 서버의 권한이 제한된 위치로 `.env.vapid`를 전달한 후 최초 설치에 다음 옵션을 추가합니다.

```bash
sudo python3 scripts/deploy.py install \
  --jar build/libs/backend-0.0.1-SNAPSHOT.jar \
  --frontend-origin https://YOUR-FRONTEND-DOMAIN \
  --vapid-subject mailto:YOUR-TEAM-CONTACT@example.com \
  --vapid-env /SECURE/PATH/.env.vapid
```

`.env.vapid`는 `VAPID_PUBLIC_KEY=...`와 `VAPID_PRIVATE_KEY=...` 두 줄의 원본 파일입니다. 개인키는 채팅·PR·공개 GitHub 파일로 게시하지 않습니다. 운영 키를 생성한 뒤에는 설치 도구가 다른 키로 몰래 교체하지 못하도록 거부합니다.

**사용자님의 개인 키 파일이 없어도**, 초기 운영을 시작하는 팀원은 첫 설치 명령으로 서버에서 새 키를 생성할 수 있습니다. 이 경우 프론트가 서버의 공개키 API를 조회해 새 구독을 생성하면 됩니다. 공개키를 프론트 코드에 고정해 두지 않는 방식을 권장합니다.

### HTTPS와 기존 Nginx 연결

기존 도메인/인증서 설정은 유지하고 HTTPS server 블록의 `/api/` upstream을 새 백엔드 포트로 맞춥니다. [Nginx 예시](https://github.com/next-station-team/next-station-backend/blob/main/deploy/nginx-api.conf.example)를 참고하세요. `/api/` location을 중복으로 추가하지 않습니다. 현재 upstream에서 제공하는 다른 API도 새 JAR에 포함되었는지 확인하고 전환하세요.

```nginx
location /api/ {
    proxy_pass http://127.0.0.1:8081;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
}
```

수정 후 `sudo nginx -t`가 성공한 경우에만 `sudo systemctl reload nginx`로 적용합니다. 서버의 8081 포트를 외부에 공개할 필요는 없으며 443 HTTPS를 통해 접근합니다.

```bash
python3 scripts/deploy.py status \
  --base-url https://nextstation17.duckdns.org \
  --frontend-origin https://YOUR-FRONTEND-DOMAIN
```

READY가 나오면 health·VAPID 활성화·해당 origin CORS까지 확인한 것입니다. 실제 휴대폰 수신은 프론트 구독 등록 후 테스트 푸시로 확인합니다. 상태 확인 명령은 푸시 메시지를 보내지 않습니다.

### 업데이트와 복구

```bash
git pull --ff-only origin main
./gradlew test bootJar --no-daemon
sudo python3 scripts/deploy.py install --jar build/libs/backend-0.0.1-SNAPSHOT.jar
```

처음 설정한 키·CORS·연락처·DB 경로·DB 비밀번호·포트를 유지합니다. 프론트 주소를 바꾸려면 `--frontend-origin`만 추가합니다. 다른 서비스 이름으로 설치했다면 이후 명령에도 동일 `--service-name`을 추가합니다.

```bash
sudo python3 scripts/deploy.py rollback
sudo systemctl status nextstation-backend
sudo journalctl -u nextstation-backend -n 100 --no-pager
```

롤백은 바로 이전 JAR·환경설정·서비스 설정으로 돌아갑니다. DB 파일은 되돌리지 않습니다. 향후 DB 스키마 변경 배포에는 별도 데이터 백업·마이그레이션 계획이 필요합니다. 최초 설치에는 이전 버전이 없으므로 롤백 명령을 사용할 수 없습니다.

## 4. 개발 PC에서 실행하는 순서

```bash
git clone https://github.com/next-station-team/next-station-backend.git
cd next-station-backend
java -version
./gradlew test bootJar
./gradlew bootRun
```

Windows는 `./gradlew` 대신 `.\gradlew.bat`를 사용합니다. 기본 포트는 8080입니다. 기본 설정에서는 로컬 파일 `./data/nextstation.mv.db`가 생성되며 푸시 발송은 비활성화됩니다.

실제 푸시를 켜기 전에 팀이 이미 사용하는 키 쌍이 있는지 확인합니다. 기존 키가 있다면 그것을 서버의 비밀 설정에서 가져옵니다. 처음 설정하는 환경이면 다음 명령으로 생성할 수 있습니다.

```bash
./gradlew generateVapidKeys
```

`.env.vapid`에 생성되며 기존 파일은 덮어쓰지 않습니다. **개인키가 GitHub에 없는 것은 정상**입니다. 프론트에는 공개키만 전달합니다. 운영 키는 개인 PC에만 두지 말고 팀의 비밀 저장소나 접근이 제한된 서버 설정에 보관하세요. 키를 교체하면 기존 브라우저 구독도 재생성이 필요합니다.

## 5. 필수 환경변수

| 변수 | 설정 |
| --- | --- |
| PUSH_ENABLED | 실제 발송을 하려면 `true` |
| VAPID_PUBLIC_KEY | 생성한 공개키 |
| VAPID_PRIVATE_KEY | 같은 쌍의 개인키. GitHub/프론트에 게시하지 않음 |
| VAPID_SUBJECT | `mailto:실제운영자이메일` 또는 운영 HTTPS 연락처 URL |
| CORS_ALLOWED_ORIGINS | 실제 프론트 origin. 여러 개면 쉼표 구분, 경로/끝 슬래시 제외 |
| DB_URL | 운영에서는 영구 디렉터리의 절대 경로 H2 파일 URL |
| DB_USERNAME / DB_PASSWORD | 서버 DB에 사용하는 계정 값. 재시작 때 동일하게 유지 |
| ALARM_SCHEDULER_ENABLED | 발송 스케줄러를 사용하려면 `true`(기본값) |

Linux 운영 경로 예시:

```text
DB_URL=jdbc:h2:file:/var/lib/nextstation/nextstation;DB_CLOSE_ON_EXIT=FALSE
```

해당 디렉터리가 존재하고 서비스 실행 계정이 읽고 쓸 수 있어야 합니다. `.env`/`.env.vapid` 파일은 Spring Boot가 자동으로 읽지 않습니다. 프로세스 환경변수 또는 서비스의 EnvironmentFile로 주입합니다. 단순히 GitHub에서 파일을 받아 옆에 놓는 것만으로는 적용되지 않습니다.

처음 로컬에서 활성화하는 Bash 예시:

```bash
set -a
. ./.env.vapid
set +a
export VAPID_SUBJECT='mailto:ops@your-domain.example' # 실제 운영 연락처로 변경
export PUSH_ENABLED=true
export CORS_ALLOWED_ORIGINS='http://localhost:3000' # 실제 프론트 origin으로 변경
java -jar build/libs/backend-0.0.1-SNAPSHOT.jar
```

이 명령은 터미널 실행 예시입니다. 팀 서버에서는 기존 systemd 서비스 등으로 상시 실행하고, 부팅 시 자동 시작·실패 시 재시작을 설정합니다. 기존 서비스의 WorkingDirectory, JAR 경로, EnvironmentFile과 Nginx upstream을 서버 담당자가 맞춰야 합니다.

## 6. 개발자가 없어도 운영되기 위한 인계 항목

| 인계 항목 | 완료 기준 |
| --- | --- |
| 저장소와 배포 권한 | 팀의 서버 담당자가 main 코드로 빌드·배포 가능 |
| 서버 서비스 | SSH/터미널 종료 뒤에도 프로세스 실행, 재부팅 뒤 자동 기동 |
| 비밀 설정 | 팀이 운영 VAPID 키 쌍·연락처·DB 설정의 위치와 접근 방법을 관리 |
| 영구 데이터 | 배포/재시작 때 같은 DB 파일 사용. 빈 임시 DB로 바뀌지 않음 |
| 데이터 백업 | DB 정지 후 파일 복사 또는 H2가 지원하는 백업 방식 사용. 실행 중 파일을 무작정 복사하지 않음 |
| HTTPS와 CORS | 실제 프론트에서 등록/예약 API 호출 성공 |
| 새 API 배포 | health의 JSON 응답 및 공개키 API 200 확인 |
| 실제 발송 | iPhone PWA/Android Chrome에서 화면 꺼짐 상태 수신 확인 |
| 재시작 복구 | 예약 후 서버 재시작, 같은 예약이 유지되고 적시에 발송됨 |

현재 구성은 단일 서버 MVP입니다. 서버가 꺼져 있는 동안에는 발송되지 않고, 복구 후에는 DB의 미발송 예약 중 아직 유효한 단계만 처리합니다. 이미 도착 시각이 지난 예약은 발송하지 않습니다. 서버 재시작 복구와 무중단 운영은 서로 다른 조건입니다.

실제 푸시는 인터넷 연결과 브라우저/운영체제의 수신 상태에도 영향을 받습니다. 제공자 접수 성공은 단말 표시 완료가 아니므로 운영 확인에는 실제 기기 테스트가 포함되어야 합니다.

API 필드·오류·프론트 처리 순서는 함께 전달한 F-00/F-04 API 명세와 [기존 저장소 실행 안내](https://github.com/next-station-team/next-station-backend#readme)를 참고하세요.
