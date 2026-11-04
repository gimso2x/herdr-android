---
task: T-20261102-04
title: herdr-android: 답한 질문 알림 거두기(resolved 푸시)
risk: L2
repo: local:/home/ubuntu/repos/herdr-android-resolved
---
# 목표
pane의 blocked가 풀리면 중계기가 resolved 푸시를 보내고 앱이 해당 needs_input 알림을 조용히 취소한다. 설계는 doc/plans/push-resolved-withdraw.md를 따른다.
# 범위
- bridge/state.ts, observer.ts, machines.ts, service.ts, boundaries.ts, bridge.test.ts
- app/src/main/java/com/gimso2x/herdr/watch/*
- app/src/test/java/com/gimso2x/herdr/watch/*
- README.md 알림의 한계 절
# 제외
- 라이브 체크아웃·plan 워크트리 편집
- androidTest, ci/, .github/, Gradle 설정, 버전·릴리스
- 앱 열 때 알림 지우기, 홈 위젯
- push·merge·배포·중계기 재시작
# 수락기준
- [x] AC-1: AC-1: `cd bridge && bun test` 실패 0이고, 로컬(observer)과 원격(machines) 각각 blocked→working 전환에서 kind=resolved이고 resolves가 앞선 needs_input의 event_id인 이벤트가 정확히 1번 emit됨을 확인하는 테스트가 있다.
- [x] AC-2: AC-2: blocked 상태에서 60초 이상 지난 idle/done 전환은 resolved가 finished보다 먼저 emit되고, blocked 중 pane-exited는 resolved를 1번 emit하며, blocked를 거치지 않은 전환·첫 seed·재연결 seed·같은 상태 재전송은 resolved를 emit하지 않음을 확인하는 테스트가 있다(로컬·원격).
- [x] AC-3: AC-3: fcmData가 resolved에 대해 정확히 type,event_id,kind,pane_id,server_origin,resolves 키를 내고 needs_input·finished 키는 바뀌지 않으며, resolved 이벤트는 state에 저장되지 않아 /api/android-push/events/<id>가 404임을 확인하는 테스트가 있다.
- [x] AC-4: AC-4: AC-1 회귀 테스트가 수정 전 코드에서 실패한 결과 줄이 PROGRESS.md 증거에 적혀 있다.
- [x] AC-5: AC-5: PushEventTest에 resolved 수신(필드 6개, resolves UUID)은 받고, resolves가 없거나 UUID가 아닌 resolved와 resolves가 붙은 needs_input·finished는 거절하는 JVM 단위 테스트가 있다.
- [x] AC-6: AC-6: 취소 대상 판정이 순수 함수로 분리돼 있고, EXTRA_EVENT_ID가 resolves와 같은 NOTIFY_ALERT 알림만(로컬 태그·원격 태그 모두) 고르며 다른 event id·다른 id의 알림은 고르지 않음을 확인하는 JVM 단위 테스트가 있다. resolved 수신 경로는 알림을 게시하지 않고 PushDetailWorker를 예약하지 않는다.
- [x] AC-7: AC-7: 이 호스트에는 Android SDK가 없어 `./gradlew :app:testDebugUnitTest`를 돌릴 수 없다는 사실과, 앱 테스트는 task 브랜치 push 후 CI fast 단계에서 확인한다는 사실이 PROGRESS.md에 적혀 있다.
- [x] AC-8: AC-8: README "알림의 한계" 절에 blocked가 풀리면 알림이 사라진다는 문장과 재연결 사이 한계가 있다.
- [x] AC-9: AC-9: 변경이 task 브랜치에 커밋돼 있고, 제외 경로(androidTest, ci/, .github/, *.gradle, variables.gradle)가 `git diff --stat <base>..HEAD`에 나오지 않는다.
