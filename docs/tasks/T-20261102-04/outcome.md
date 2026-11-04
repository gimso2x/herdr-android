# T-20261102-04 Outcome 증거

## AC-1
- commit: dc0449f test(bridge): answered question must emit one resolved event (fails before fix)
- commit: 5c71886 feat(bridge): emit resolved when a blocked pane is answered or exits
- test: `cd bridge && bun test` → ` 36 pass` / ` 0 fail` / `Ran 36 tests across 1 file.`
- file: bridge/bridge.test.ts — `read-only live observer > an answered question withdraws its needs_input alert exactly once`(로컬), `remote machine panes > an answered remote question withdraws its needs_input alert exactly once`(원격). blocked→working→working에서 kinds가 `["needs_input","resolved"]`이고 `resolves`가 needs_input의 event_id임을 확인한다.

## AC-2
- commit: 5c71886 feat(bridge): emit resolved when a blocked pane is answered or exits
- test: `cd bridge && bun test` → ` 36 pass` / ` 0 fail`
- file: bridge/bridge.test.ts — 로컬: `a long blocked turn that ends sends resolved before finished, for done and idle`, `a pane that exits while blocked withdraws its question once`, `no resolved without a seen blocked spell: plain turns, first seed, reconnect seed, repeated status`, `a structure resync keeps a continuous question and never withdraws one by itself`. 원격: `a long remote blocked turn sends resolved before finished, and an exit while blocked withdraws once`, `no remote resolved without a seen blocked spell: plain turns, first seed, reconnect seed, repeated status`.

## AC-3
- commit: 5c71886 feat(bridge): emit resolved when a blocked pane is answered or exits
- test: `cd bridge && bun test` → ` 36 pass` / ` 0 fail`
- file: bridge/bridge.test.ts — `the FCM data of resolved adds only the withdrawn event id`(키 6개 정확히), `the FCM data keys are unchanged for local and remote events`(needs_input·finished 키 5개 유지), `resolved is sent but never cached, so its event detail is not found`(`/events/<resolved id>` 404, needs_input 200, cached_events 1).

## AC-4
- commit: dc0449f test(bridge): answered question must emit one resolved event (fails before fix)
- test: `cd bridge && bun test`(수정 전 코드) → `(fail) read-only live observer > an answered question withdraws its needs_input alert exactly once`, `(fail) remote machine panes > an answered remote question withdraws its needs_input alert exactly once`, ` 2 fail`
- file: docs/tasks/T-20261102-04/PROGRESS.md S2 증거 줄

## AC-5
- commit: edb8298 feat(app): withdraw the needs_input alert named by a resolved push
- test: 임시 디렉터리에서 kotlin-compiler-embeddable 2.2.20으로 순수 파일만 컴파일해 `java org.junit.runner.JUnitCore ...PushEventTest ...AlertWithdrawTest ...PaneLinkTest ...AlertTextTest` → `OK (26 tests)` (gradle 아님, AC-7 참고)
- file: app/src/test/java/com/gimso2x/herdr/watch/PushEventTest.kt — `resolvedCarriesTheWithdrawnEventIdAndAlertsNeverDo`
- file: app/src/main/java/com/gimso2x/herdr/watch/PushEvent.kt

## AC-6
- commit: edb8298 feat(app): withdraw the needs_input alert named by a resolved push
- test: 위 AC-5와 같은 JUnitCore 실행 → `OK (26 tests)`
- file: app/src/main/java/com/gimso2x/herdr/watch/AlertWithdraw.kt — 순수 함수 `AlertWithdraw.targets(active, resolves)`
- file: app/src/test/java/com/gimso2x/herdr/watch/AlertWithdrawTest.kt — 로컬 태그·옮겨진 원격 태그(`PaneLink.alertKey(pane, machine)`) 선택, 다른 event id·다른 알림 id(3001)·event id 없는 알림 제외
- file: app/src/main/java/com/gimso2x/herdr/watch/HerdrMessagingService.kt — `PushReceiver.receive`에서 `RESOLVED`는 `WatchNotifications.withdraw`만 부르고 `showPush`·`PushDetailWorker.enqueue`·원장 기록 전에 반환한다

## AC-7
- commit: edb8298 feat(app): withdraw the needs_input alert named by a resolved push
- file: docs/tasks/T-20261102-04/PROGRESS.md "앱 검증 한계 (AC-7)" 절 — Android SDK 없음으로 `./gradlew :app:testDebugUnitTest` 미실행, 앱 테스트는 task 브랜치 push 후 CI fast 단계에서 확인

## AC-8
- commit: 8ee52ad docs(readme): answered questions withdraw their alert, except across reconnects
- file: README.md "알림의 한계" 절 41행

## AC-9
- commit: 8ee52ad docs(readme): answered questions withdraw their alert, except across reconnects
- test: `git diff --name-only 130df5e..HEAD | grep -E "androidTest|^ci/|^\.github/|\.gradle|variables\.gradle"` → 출력 없음(exit 1). `git diff --stat 130df5e..HEAD` → `15 files changed, 295 insertions(+), 30 deletions(-)`(README·app watch·bridge·docs/tasks만)
