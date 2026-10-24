# herdr (Android)

자신의 herdr-web-ui 서버를 전체 화면으로 보여 주는 개인용 Android WebView 앱입니다.
서버는 Cloudflare Access(서비스 토큰) 뒤에 있어야 합니다. 앱은 한 번 로그인하면 쿠키를 알아서 갱신하고, 에이전트 이벤트가 발생했을 때 FCM 푸시 알림을 받습니다.
빌드·서명·릴리스는 모두 GitHub Actions에서 이루어집니다. 패키지 이름은 `com.gimso2x.herdr`입니다.

## 서버 쪽 준비 (Cloudflare Zero Trust)

- 서비스 토큰을 만들고(폰용, 필요하면 CI용 따로), herdr-web-ui를 보호하는 Access 앱에 Action이 Service Auth인 정책으로 그 토큰을 포함시킵니다.
- **"Strict service token authentication"은 반드시 꺼 두세요.** 켜면 Cloudflare가 `CF_Authorization` 쿠키를 주지 않아 앱이 로그인할 수 없습니다. (앱은 이 경우 연결 화면에 안내 문구를 띄웁니다.)
- 토큰은 안전한 곳에 보관하고, 유출이 의심되면 대시보드에서 즉시 폐기합니다.
- CI(라이브 QA, 릴리스 스모크)를 돌리려면 저장소 시크릿 `CF_ACCESS_CLIENT_ID_CI`, `CF_ACCESS_CLIENT_SECRET_CI`, `HERDR_BASE_URL`(서버 주소)이 필요합니다.

## 설치와 자동 업데이트 (Obtainium)

1. 폰에 [Obtainium](https://github.com/ImranR98/Obtainium)을 설치하고 앱 추가에서 `https://github.com/gimso2x/herdr-android`를 입력합니다. 저장소가 공개라서 토큰은 필요 없습니다.
2. 릴리스의 `app-release.apk`를 설치합니다. 이후 새 릴리스가 나오면 Obtainium이 같은 서명으로 제자리 업데이트하므로 앱 데이터(토큰)는 유지됩니다. 프리릴리스(`-rc`)는 무시하세요.

## 서버 주소와 토큰 입력

앱을 처음 열면 "herdr 연결" 화면에서 **서버 주소**(예: `https://herdr.example.com`), Client ID, Client Secret을 차례로 입력하고 "연결"을 누릅니다. 서버 주소는 `https://`로 시작하고 호스트만 있어야 합니다(경로·쿼리·계정 정보 없이). 앱에는 기본 주소가 없고, 입력한 주소와 토큰은 앱 안에서 암호화되어 저장되며 백업되지 않습니다. 주소는 설정 화면의 "서버 주소"에서 바꿀 수 있고, 바꾸면 쿠키를 지우고 새 서버에 다시 연결합니다(실패하면 연결 화면으로 돌아갑니다).

이전 버전에서 업데이트한 경우 저장된 토큰은 그대로 쓰고 서버 주소만 한 번 물어봅니다.

## 알림 설정

토큰을 처음 입력하면(또는 업데이트 후 처음 열면) "처음 설정 안내" 화면이 나옵니다. 앱이 확인하는 항목은 "알림 허용" 하나이며, 상태(완료/필요)를 보여 주고 시스템 설정을 다녀오면 다시 확인합니다. 허용하지 않아도 "시작하기"로 들어갈 수 있습니다. 나중에 설정 화면의 "처음 설정 안내 다시 보기"로 다시 열 수 있습니다. 아래 항목 중 알림 허용을 뺀 나머지는 앱이 감지하거나 표시하지 않으니 직접 확인하세요.

- **알림 허용**: 알림 권한을 허용합니다(Android 13+). 이미 거부해서 대화상자가 더 뜨지 않으면 앱 알림 설정이 열립니다.
- **Google Play 서비스**: FCM 수신에 필요합니다. 앱을 항상 켜 두거나 배터리 제한을 해제할 필요는 없습니다.
- **사용하지 않는 앱 일시중지 끄기**: Android가 오래 열지 않은 앱의 권한을 회수하지 않도록 앱 정보 화면에서 직접 끕니다.
- **삼성 기기**: 알림이 늦는 경우에만 백그라운드 사용 제한에서 herdr가 깊은 절전 앱으로 지정됐는지 확인하세요.
- **설정 여는 법**: 홈 화면에서 herdr 아이콘을 길게 눌러 "설정"을 고릅니다.
- 상시 "에이전트 알림 대기 중" 알림은 더 이상 사용하지 않습니다.

## 알림의 한계

- 서버의 중계기가 읽기 전용 WebSocket 이벤트를 받고 FCM으로 전달합니다. 앱은 주기적으로 서버를 확인하지 않으며, 포그라운드 감시 서비스나 wake lock을 유지하지 않습니다.
- 앱이 열려 있는 동안에는 알림이 울리지 않습니다(조용히 처리).
- "입력이 필요합니다"는 `blocked` 상태를 보았을 때, "작업을 마쳤습니다"는 60초 이상 지속된 작업이 끝났을 때만 울립니다. 알림을 누르면 해당 pane의 채팅이 열립니다.
- FCM에는 알림 종류·이벤트 ID·패널 이동용 식별자만 전달합니다. 세션 이름과 질문·답변은 보내지 않습니다. 앱은 일반 알림을 먼저 표시하고, 일회성 백그라운드 작업으로 기존 Access 인증을 거쳐 상세 정보를 가져와 조용히 갱신합니다. 가져오지 못하면 일반 알림이 남습니다.
- 푸시는 네트워크·Android 설정·FCM 상태에 따라 지연되거나 누락될 수 있습니다. 앱을 강제 종료한 경우 다시 열어야 수신이 재개됩니다.
- 앱 설정에서 알림을 끄거나 토큰을 지우면 등록을 해제하고, 등록 해제 요청이 실패해도 앱은 해당 알림을 표시하지 않습니다.

## FCM 중계기 설정

Firebase 프로젝트에 `com.gimso2x.herdr`와 `com.gimso2x.herdr.debug`를 등록합니다. 두 앱이 포함된 `google-services.json`은 저장소에 넣지 않고 base64로 인코딩해 GitHub Secret `HERDR_GOOGLE_SERVICES_B64`에 저장합니다. CI는 빌드할 때만 파일을 생성합니다. 설정값은 APK에 포함되므로 Secret 사용이 APK 안의 Firebase API 키를 숨기는 것은 아닙니다. 서비스 계정 비공개 키는 앱과 CI에 넣지 않습니다.

`bridge/`는 서버의 Bun 프로세스입니다. 서비스 계정은 서버 전용 파일(권한 600)에 보관하고 `HERDR_PUSH_SERVICE_ACCOUNT`로 지정합니다. 중계기는 loopback에만 바인딩하며, Cloudflare Tunnel에서 `/api/android-push/`만 해당 포트로 라우팅합니다. 이 경로에도 기존 Access 앱의 JWT 검증을 적용해야 합니다. `HERDR_PUSH_ACCESS_TEAM`, `HERDR_PUSH_ACCESS_AUD`, `HERDR_PUSH_PUBLIC_ORIGIN`은 같은 Access 앱과 서버 주소로 설정합니다. herdr-web-ui 원본 코드나 Access 인증 예외는 필요하지 않습니다.

실행은 `bun install --cwd bridge --frozen-lockfile` 후 `bun run bridge/server.ts`입니다. 나머지 환경 변수와 기본값은 다음과 같습니다(`bridge/server.ts`).

- `HERDR_PUSH_PORT`: 기본 `7318`. `127.0.0.1`에만 바인딩하므로 Tunnel 대상도 `http://127.0.0.1:<포트>`로 적습니다.
- `HERDR_PUSH_UPSTREAM`: 기본 `http://127.0.0.1:7317`. loopback HTTP만 허용하며, 중계기는 이 주소의 `/ws`와 `/api/session`에 인증 정보 없이 접속하므로 herdr-web-ui가 loopback 접속을 토큰 없이 받아야 합니다.
- `HERDR_PUSH_STATE_DIR`: 기본 `~/.config/herdr-android/push-state`(권한 700). 등록된 FCM 토큰과, 알림 상세용으로 12시간 동안 workspace·tab·에이전트·pane 제목을 담은 `state.json`(권한 600)이 저장됩니다.
- `HERDR_PUSH_SERVICE_ACCOUNT`: 기본 `~/.config/herdr-android/fcm-service-account.json`. 그룹·기타 권한이 없는 64 KiB 이하 일반 파일이어야 합니다.


## 음성 입력

마이크는 설정한 서버 주소에서만 허용되며 앱이 `RECORD_AUDIO` 권한을 먼저 묻습니다. 실제 음성 인식은 서버에서 일어나므로 herdr-web-ui 쪽에 OpenAI 음성 인식 경로(route)가 설정되어 있어야 동작합니다. 그렇지 않으면 마이크 버튼이 있어도 변환되지 않습니다.

## 토큰 교체

1. Cloudflare 대시보드에서 `herdr-android-phone` 토큰을 새로 만들거나 Refresh하고, 정책 `herdr-android service tokens`가 새 토큰을 포함하는지 확인합니다.
2. 앱 설정 > 서비스 토큰 > "토큰 바꾸기"로 새 값을 입력합니다. 완전히 끊으려면 "토큰 지우기".
3. 서버의 `~/.config/herdr-android/phone-token.env`도 새 값으로 갱신합니다(권한 600 유지).
4. 유출이 의심되면 대시보드에서 해당 토큰을 즉시 폐기합니다.

## 릴리스 (개발자용)

```
bash scripts/release.sh 0.1.0
```

작업 트리가 깨끗하고 태그가 없을 때만 실행됩니다. `versionName`/`versionCode`를 올리고 `chore(release): <버전>` 커밋과 주석 태그를 만들어 푸시한 뒤, 태그 빌드를 기다려 서명된 APK를 `dist/<버전>/app-release.apk`에 받고 URL과 sha256을 출력합니다.
수동 워크플로 `upgrade.yml`(`from_tag`, `to_tag`)은 에뮬레이터(API 34)에서 이전 APK 위에 새 APK를 덮어 설치해 `firstInstallTime` 유지와 `versionCode` 증가를 확인합니다.

## CI 두 단계 (개발자용)

`android.yml`은 단계를 하나만 고릅니다. 에뮬레이터 phase 목록은 `ci/instrumented.sh --tier fast|full`이 유일한 기준입니다(CI는 full만 사용).

- **fast** (브랜치 push, pull request): 빌드 + 단위 테스트 + lint + 임시 키로 서명한 minify 릴리스 빌드(R8 확인) + CI 스크립트 자체 테스트. 에뮬레이터와 서버 인증키는 필요 없지만 Firebase 클라이언트 설정 Secret은 필요합니다. fork PR에는 Secret을 전달하지 않습니다.
- **full** (`v*` 태그, 매주 일요일 18:00 UTC 스케줄, 수동 실행): fast + 에뮬레이터 API 34 전체 phase(라이브 L1-L4 포함) + API 35 부분 집합. 모든 phase는 러너가 성공 종료하고 클래스별 예상 테스트 수만큼 실행(0개 불가)해야 통과합니다.
- **릴리스 잡**은 태그 push 이벤트에서만, 기본 매트릭스(API 34·35, 전체 phase)로 돈 full 실행 뒤에만 실행됩니다. 수동 실행(`apis`/`phases`/`gpus` 덮어쓰기 포함)은 태그 ref에서도 릴리스하지 않습니다.

수동으로 full을 돌리려면 `gh workflow run android.yml -f tier=full --ref main`을 실행합니다. 수동 입력(`apis`, `gpus`, `emu-build`, `phases`)은 plan 잡에서 검증되며 알 수 없는 phase ID는 거부됩니다.

보안 규칙(공개 저장소): 모든 `uses:`는 커밋 SHA로 고정(Dependabot이 갱신)하고, 디버그 APK는 패키지 `com.gimso2x.herdr.debug`·임시 디버그 키로만 빌드하며 full 단계에서 1일만 보관합니다. 에뮬레이터 산출물은 시크릿 스캔(`ci/secret-scan.sh`: 토큰·서버 주소·`CF_Authorization=`·JWT 형태)이 통과해야만 업로드하고, 라이브 phase(L1-L4, P1)의 스크린샷·logcat은 수집하지 않습니다. Gradle은 `gradle/verification-metadata.xml`(sha256)과 래퍼 체크섬으로 검증하며, 의존성을 바꾸면 `gradle-verification.yml`을 수동 실행해 파일을 다시 만듭니다.

휴대폰에서 FCM을 확인할 때는 수동 실행에 `-f phone-apk=true`를 추가하고 `herdr-phone-test-apk`를 받습니다. 이 APK는 에뮬레이터의 알림 게시 확인용 `NotificationListenerService`를 제외하고, 서명·패키지 이름·알림 접근 서비스 부재·시크릿 스캔을 확인한 뒤 7일간 보관합니다. `herdr-debug-apk`는 알림 접근 서비스가 들어 있는 에뮬레이터 QA 전용이므로 휴대폰 설치용으로 사용하지 않습니다. 휴대폰용도 별도 `.debug` 패키지이며, 정식 앱이나 Obtainium 업데이트를 대체하지 않습니다.

## 서명 키 백업

릴리스 서명 키는 서버의 `~/.config/herdr-android/signing/`(`herdr-release.jks`, `signing.properties`, 권한 700/600)에 있습니다. **이 폴더를 별도로 백업하세요.** 잃어버리면 같은 서명으로 업데이트할 수 없어 앱을 지우고 다시 설치해야 하고, 그때 저장된 토큰도 사라집니다.
