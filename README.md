# herdr (Android)

https://herdr.example.com (herdr-web-ui)를 전체 화면으로 보여 주는 개인용 Android WebView 앱입니다.
Cloudflare Access 서비스 토큰으로 한 번 로그인하면 쿠키를 앱이 알아서 갱신하고, 백그라운드에서 에이전트 상태를 지켜보다가 알림을 보냅니다.
이 저장소는 private이며 빌드·서명·릴리스는 모두 GitHub Actions에서 이루어집니다. 패키지 이름은 `com.gimso2x.herdr`입니다.

## 최초 1회: 소유자 설정 (Cloudflare Zero Trust, 팀 `your-team`)

이미 API로 적용되어 있습니다. 상태만 알아 두세요.

- 서비스 토큰 2개(기간 "Non-expiring"): `herdr-android-phone`(폰용), `herdr-android-ci`(CI 라이브 QA용). 폐기는 대시보드에서 합니다.
- Access 앱 "herdr web ui"(herdr.example.com, omp.example.com)에 정책 `herdr-android service tokens`가 있습니다. Action은 Service Auth이고 Include는 위 두 토큰입니다. 기존 정책 `allow-gimso2x`는 그대로 입니다.
- **"Strict service token authentication"은 반드시 꺼 두세요.** 켜면 Cloudflare가 `CF_Authorization` 쿠키를 주지 않아 앱이 로그인할 수 없습니다. (앱은 이 경우 연결 화면에 안내 문구를 띄웁니다.)
- CI 토큰은 서버의 `~/.config/herdr-android/ci-token.env`(권한 600)와 저장소 시크릿 `CF_ACCESS_CLIENT_ID_CI`, `CF_ACCESS_CLIENT_SECRET_CI`에 있습니다.

## 설치와 자동 업데이트 (Obtainium)

1. 폰에 [Obtainium](https://github.com/ImranR98/Obtainium)을 설치하고 앱 추가에서 `https://github.com/gimso2x/herdr-android`를 입력합니다. 저장소가 공개라서 토큰은 필요 없습니다.
2. 릴리스의 `app-release.apk`를 설치합니다. 이후 새 릴리스가 나오면 Obtainium이 같은 서명으로 제자리 업데이트하므로 앱 데이터(토큰)는 유지됩니다. 프리릴리스(`-rc`)는 무시하세요.

## 폰 토큰 입력

폰용 토큰은 서버의 `~/.config/herdr-android/phone-token.env`에 있습니다(`CF_ACCESS_CLIENT_ID`, `CF_ACCESS_CLIENT_SECRET`). 소유자가 서버에서 직접 열어 앱 첫 화면 "herdr 연결"의 Client ID / Client Secret 칸에 입력하고 "연결"을 누릅니다. 토큰은 앱 안에서 암호화되어 저장되며 백업되지 않습니다.

## 알림 설정

토큰을 처음 입력하면(또는 업데이트 후 처음 열면) "처음 설정 안내" 화면이 나옵니다. 항목마다 상태(완료/필요)가 보이고, 시스템 설정을 다녀오면 다시 확인합니다. 다 끝내지 않아도 "시작하기"로 들어갈 수 있습니다. 나중에 설정 화면의 "처음 설정 안내 다시 보기"로 다시 열 수 있습니다.

- **알림 허용**: 알림 권한을 허용합니다(Android 13+). 이미 거부해서 대화상자가 더 뜨지 않으면 앱 알림 설정이 열립니다.
- **배터리 제한 해제 (알림이 제때 오려면 필수)**: "제한 해제하기"를 눌러 배터리 최적화 제외를 허용합니다. 허용하지 않으면 화면이 꺼진 동안 알림이 늦거나 오지 않을 수 있습니다.
- **사용하지 않는 앱 일시중지 끄기**: Android가 오래 열지 않은 앱의 권한을 회수하고 동작을 멈추지 않도록 앱 설정에서 끕니다.
- **삼성 기기만**: 설정 > 배터리 > 백그라운드 사용 제한 > 절전 예외 앱에 herdr를 추가하세요.
- **설정 여는 법**: 홈 화면에서 herdr 아이콘을 길게 눌러 "설정"을 고르거나, 상단 "에이전트 알림 대기 중" 알림을 누릅니다.
- 상단 "에이전트 알림 대기 중" 알림은 무음으로 바꿔도 되지만 끄지는 마세요. 끄면 백그라운드 감시가 끊길 수 있습니다.

## 알림의 한계

- 15초마다 `/api/session`을 확인합니다. 그 사이에 끝난 짧은 상태 변화는 놓칠 수 있습니다.
- 앱이 열려 있는 동안에는 알림이 울리지 않습니다(조용히 처리).
- "입력이 필요합니다"는 `blocked` 상태를 보았을 때, "작업을 마쳤습니다"는 60초 이상 지속된 작업이 끝났을 때만 울립니다. 알림을 누르면 해당 pane의 채팅이 열립니다.

## 음성 입력

마이크는 herdr.example.com 에서만 허용되며 앱이 `RECORD_AUDIO` 권한을 먼저 묻습니다. 실제 음성 인식은 서버에서 일어나므로 herdr-web-ui 쪽에 OpenAI 음성 인식 경로(route)가 설정되어 있어야 동작합니다. 그렇지 않으면 마이크 버튼이 있어도 변환되지 않습니다.

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

## 서명 키 백업

릴리스 서명 키는 서버의 `~/.config/herdr-android/signing/`(`herdr-release.jks`, `signing.properties`, 권한 700/600)에 있습니다. **이 폴더를 별도로 백업하세요.** 잃어버리면 같은 서명으로 업데이트할 수 없어 앱을 지우고 다시 설치해야 하고, 그때 저장된 토큰도 사라집니다.
