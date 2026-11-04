# T-20261102-04 컨텍스트
## 요청 원문
<https://github.com/yazzang-homelab/gajae-mobile/|github.com/yazzang-homelab/gajae-mobile> 이거로 herdr-andriod수정할거있나?
## 배경
PC에서 답해도 폰의 '입력이 필요합니다' 알림이 finished가 덮어쓸 때까지(60초 미만 작업이면 끝까지) 남는다. observer/machines는 blocked 진입만 이벤트로 보내고, 앱은 등록 해제 때만 알림을 지운다. gajae-mobile ActivityNotifier.withdrawAsk를 참고한 개선.
## 가정
'추천대로' = 1번(resolved 푸시로 답한 질문 알림 거두기)만 진행, 앱 열 때 지우기·위젯은 제외.
## 메타
- 요청 위치: https://botfactory-5956354.slack.com/archives/C0CCBLV767J/p1793630638185149?thread_ts=1793630638.185149&cid=C0CCBLV767J · 등록: 2026-11-02T23:49:23+09:00 · 위험등급: L2
- 레포: local:/home/ubuntu/repos/herdr-android-resolved · 워크트리: /home/ubuntu/repos/herdr-android-resolved-t-20261102-04 (branch task/T-20261102-04) · 에이전트: t-20261102-04
