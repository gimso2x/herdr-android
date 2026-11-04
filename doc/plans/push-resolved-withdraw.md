# 답한 질문의 "입력이 필요합니다" 알림 거두기

상태: 스펙 확정(2026-11-02), 구현 전.

## 배경

- pane이 `blocked`가 되면 중계기가 `needs_input` 푸시를 보내고 폰에 "입력이 필요합니다" 알림이 뜬다.
- 오너가 PC(또는 웹 UI)에서 답해 pane이 `working`으로 돌아가도 폰 알림은 그대로 남는다. 작업이 60초 이상 이어진 뒤 끝나 `finished`가 같은 태그를 덮어쓸 때까지 남고, 60초 미만이면 끝까지 남는다.
- 참고: gajae-mobile(`android/.../ui/ActivityNotifier.kt`)은 답한 질문의 알림을 `withdrawAsk(requestId)`로 지운다.

## 원인 (코드 경로)

- `bridge/observer.ts` `pane-status` 처리: `blocked && !state.blocked`일 때만 `notify(pane, "needs_input")`. `blocked`가 풀리는 전환에는 아무 이벤트도 없다.
- `bridge/machines.ts` `message()`: 원격 pane도 같은 구조. `pane-exited`도 `finished`만 본다.
- 앱 `watch/WatchNotifications.kt`: 알림을 지우는 경로는 `clear()` 하나이고, `PushRegistration.clearAlerts()`(등록 해제·알림 끔)에서만 불린다.
- 원격 pane은 상세를 받은 뒤 태그가 `PaneLink.alertKey(pane, machine)`로 옮겨지므로(`showDetail`), 뒤이은 `finished`의 일반 알림(태그 = pane id)이 예전 `needs_input` 알림을 덮어쓰지도 못한다.

## 목표

pane의 `blocked`가 풀리면 그 `needs_input` 알림이 폰에서 조용히 사라진다. 로컬·원격 pane 모두.

## 설계

### 중계기 (`bridge/`)

1. `state.ts`: `Kind`에 `"resolved"` 추가. `EventDetail`에 `resolves?: string`(거둘 `needs_input`의 event_id) 추가.
2. `observer.ts`·`machines.ts`: `Tracked`에 그 pane이 `blocked`일 때 보낸 `needs_input`의 event_id(`ask`)를 기억한다. `notify`는 만든 event_id를 돌려준다.
3. `blocked`였던 pane(`state.blocked && state.ask`)이 다음 중 하나가 되면 `resolved`를 정확히 1번 emit한다: `pane-status`가 `blocked`가 아닌 상태, `pane-exited`. 같은 프레임에서 `finished`도 나가면 `resolved`가 먼저다.
4. `resolved` 이벤트 필드: `event_id`(새 UUID), `kind:"resolved"`, `pane_id`, `server_origin`, `created_at`, `resolves`, 원격이면 `machine_id`. 라벨(workspace 등)은 넣지 않는다.
5. `service.ts` `emit`: `resolved`는 `state.addEvent`로 저장하지 않는다(상세 API 대상 아님, `/api/android-push/events/<id>`는 404). FCM 전송은 같은 경로.
6. `boundaries.ts` `fcmData`: `resolved`는 `{type:"herdr_event", event_id, kind:"resolved", pane_id, server_origin, resolves}`. `needs_input`·`finished`의 키는 지금 그대로(기존 테스트 유지).
7. 재연결 seed와 `/api/session` resync는 `resolved`를 만들지 않는다(seed가 `ask`를 모르므로). 한계로 README에 적는다.

### 앱 (`app/src/main/.../watch/`)

1. `PushEvent.decode`: `kind == "resolved"`일 때만 필드 집합 `type,event_id,kind,pane_id,server_origin,resolves`를 받고 `resolves`는 UUID여야 한다. `needs_input`·`finished`는 지금 필드 집합 그대로이고 `resolves`가 붙으면 거절한다. 이전 앱 버전은 필드 집합이 달라 `resolved`를 무시하므로 호환된다.
2. 수신(`PushReceiver.receive`): `resolved`는 알림을 게시하지 않고, `PushDetailWorker`를 예약하지 않고, 소리를 내지 않는다. 활성 알림 중 id가 `NOTIFY_ALERT`이고 `EXTRA_EVENT_ID == resolves`인 것만 태그와 상관없이 취소한다(로컬 태그·옮겨진 원격 태그 모두). 다른 event id 알림(그 사이 덮어쓴 `finished` 포함)은 건드리지 않는다. 앱이 포그라운드여도 취소한다. 푸시가 꺼져 있거나 `eligible`이 아니면 무시한다.
3. 중복 전달: 같은 `resolved` event_id가 두 번 와도 결과가 같다(취소는 멱등). 원장(`PushEventLedger`)에 넣을지는 구현이 정하되, `resolved` 때문에 같은 pane의 이후 `needs_input`·`finished`가 거절되면 안 된다.
4. 취소 대상 판정(활성 알림 목록 + `resolves` → 취소할 (tag,id) 목록)은 Android API 없이 테스트할 수 있는 순수 함수로 분리한다.
5. 상세 워커 경쟁: `showDetail`은 이미 "같은 event id의 활성 알림이 있을 때만" 갱신하므로 취소된 알림을 되살리지 않는다. 이 성질을 바꾸지 않는다.

### 문서

README "알림의 한계" 절: PC에서 답하면(`blocked`가 풀리면) 알림이 사라진다는 문장과, 중계기가 재연결하는 사이에 풀린 질문은 남을 수 있다는 한계를 적는다.

## 범위와 제외

- 범위: `bridge/state.ts`, `bridge/observer.ts`, `bridge/machines.ts`, `bridge/service.ts`, `bridge/boundaries.ts`, `bridge/bridge.test.ts`, `app/src/main/java/com/gimso2x/herdr/watch/*`, `app/src/test/java/com/gimso2x/herdr/watch/*`, `README.md`(알림의 한계 절), 이 스펙과 `docs/tasks/<ID>/`.
- 제외: 앱을 열어 pane을 볼 때 알림 지우기(WebView라 어떤 pane을 보는지 앱이 모른다), 홈 화면 위젯, `androidTest`·`ci/`·`.github/`·Gradle 설정, 버전 올리기·릴리스, push·merge·배포·브리지 재시작.

## 수락기준

- AC-1: `cd bridge && bun test` 실패 0이고, 로컬(observer)과 원격(machines) 각각 blocked→working 전환에서 kind=resolved이고 resolves가 앞선 needs_input의 event_id인 이벤트가 정확히 1번 emit됨을 확인하는 테스트가 있다.
- AC-2: blocked 상태에서 60초 이상 지난 idle/done 전환은 resolved가 finished보다 먼저 emit되고, blocked 중 pane-exited는 resolved를 1번 emit하며, blocked를 거치지 않은 전환·첫 seed·재연결 seed·같은 상태 재전송은 resolved를 emit하지 않음을 확인하는 테스트가 있다(로컬·원격).
- AC-3: fcmData가 resolved에 대해 정확히 type,event_id,kind,pane_id,server_origin,resolves 키를 내고 needs_input·finished 키는 바뀌지 않으며, resolved 이벤트는 state에 저장되지 않아 /api/android-push/events/<id>가 404임을 확인하는 테스트가 있다.
- AC-4: AC-1 회귀 테스트가 수정 전 코드에서 실패한 결과 줄이 PROGRESS.md 증거에 적혀 있다.
- AC-5: PushEventTest에 resolved 수신(필드 6개, resolves UUID)은 받고, resolves가 없거나 UUID가 아닌 resolved와 resolves가 붙은 needs_input·finished는 거절하는 JVM 단위 테스트가 있다.
- AC-6: 취소 대상 판정이 순수 함수로 분리돼 있고, EXTRA_EVENT_ID가 resolves와 같은 NOTIFY_ALERT 알림만(로컬 태그·원격 태그 모두) 고르며 다른 event id·다른 id의 알림은 고르지 않음을 확인하는 JVM 단위 테스트가 있다. resolved 수신 경로는 알림을 게시하지 않고 PushDetailWorker를 예약하지 않는다.
- AC-7: 이 호스트에는 Android SDK가 없어 `./gradlew :app:testDebugUnitTest`를 돌릴 수 없다는 사실과, 앱 테스트는 task 브랜치 push 후 CI fast 단계에서 확인한다는 사실이 PROGRESS.md에 적혀 있다.
- AC-8: README "알림의 한계" 절에 blocked가 풀리면 알림이 사라진다는 문장과 재연결 사이 한계가 있다.
- AC-9: 변경이 task 브랜치에 커밋돼 있고, 제외 경로(androidTest, ci/, .github/, *.gradle, variables.gradle)가 `git diff --stat <base>..HEAD`에 나오지 않는다.

## 반영

1. 오너 승인 뒤 task 브랜치를 push해 CI fast(빌드·단위 테스트·lint·R8)를 통과시킨다.
2. main 머지 → `bash scripts/release.sh <다음 버전>`으로 릴리스(Obtainium 업데이트).
3. 서버의 push 중계기를 새 코드로 재시작한다. 중계기를 먼저 올려도 이전 앱은 `resolved`를 무시하므로 순서는 상관없다.
