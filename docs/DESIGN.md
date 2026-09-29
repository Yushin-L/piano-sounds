# Piano Sounds 설계

## 목표와 사용 환경
Arturia KeyLab Essential 88 (USB-B) → 기존 USB 케이블 → USB-C OTG 어댑터 → Galaxy S25.
앱이 USB MIDI 연주 정보를 받아 내장 그랜드피아노 샘플로 소리를 만든다. 인터넷, 계정, 마이크 권한은 필요 없다.

## 첫 버전의 범위
- 연결된 USB MIDI 장치를 자동 검색. KeyLab을 우선 선택하고 다른 장치/출력 포트도 직접 선택 가능.
- 건반 Note On/Off, velocity=0 Note On, 16채널, running status, 분할 패킷, SysEx/실시간 메시지 혼합 처리.
- 88건반 전 음역, 96 동시 발음, 세기별 3개 실제 피아노 샘플 층, 페달(CC64), CC120/121/123.
- 볼륨, 전체 음 정지, 화면 건반 미리 듣기, 음원/연결/오디오 상태와 입력 표시.
- 앱이 화면에 보일 때 연주. 화면 켜짐 유지. 백그라운드·오디오 포커스 상실·USB 해제 시 정지.
- 오디오 경로 변경 시 스트림을 다시 열고 오류 시 재시도 제공. 화면 회전 시 안전하게 재연결.

## 구현 구조
Kotlin 네이티브 Activity와 Android MIDI API. UI와 MIDI 콜백의 이벤트는 C++ 고정 길이 큐에 전달한다.
생산자끼리만 mutex로 직렬화하며 오디오 콜백은 잠금, 파일 I/O, 메모리 할당 없이 동작한다.
Oboe가 장치 기본 샘플레이트의 float stereo 스트림을 열고 low latency/exclusive를 요청한다.
Exclusive 실패 시 shared로 재시도한다. 실제 지연 수치로 버퍼 길이를 오인하지 않는다.

음원은 Salamander Grand Piano v3 (Alexander Holm, CC BY 3.0)에서 30개 기준음 × 3 세기층을 추출한다.
원본 SFZ의 공격 시작점 정보를 사용하고 24 kHz stereo PCM16으로 변환하며 최대 12초, 마지막 250ms를 fade out 한다.
인접 기준음에서 최대 1반음 보간한다. 원본 레벨 차이는 유지한다. 네이티브 엔진이 릴리스·페달·음량과 리미팅을 처리한다.
악기 크기와 메모리를 줄이기 위해 전체 16층, 공명/페달 잡음은 포함하지 않는다.

## 검증
JVM에서 MIDI 파서의 분할/혼합/오류 복구를 검증한다. Android 계측 테스트에서 내장 음원 로딩,
88음 재생, 세기·페달·정지·다중 발음·클리핑·큐 과부하를 오프라인 렌더링으로 검증한다.
에뮬레이터에서 앱 실행·화면·오디오 시작·가상 MIDI 서비스 입력·생명주기를 확인한다.
실제 USB 전원 공급, Galaxy S25의 스피커 출력 및 입력부터 소리까지의 지연은 해당 하드웨어에서만 최종 확인 가능하다.

## 참고
- https://developer.android.com/reference/android/media/midi/package-summary
- https://developer.android.com/games/sdk/oboe/low-latency-audio
- https://github.com/google/oboe/blob/1.10.0/docs/GettingStarted.md
- https://github.com/sfzinstruments/SalamanderGrandPiano
