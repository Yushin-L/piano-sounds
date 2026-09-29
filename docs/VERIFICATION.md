# 검증 기록

## 2026-09-29 화음 음질 개선 (1.1.2, #1)

- 원인 측정: 실제 `PianoSynth`로 단음, 4·6음 화음, 빠른 코드 전환, 페달 유지/교체를 렌더링했다
  ([ChordRender](../app/src/test/cpp/ChordRender.cpp), [결과](verification/chord-render.txt)).
  기본 볼륨 70%에서 6음 화음 합산 피크가 +5.9~7.2 dBFS(100%에서 +9~10.3 dBFS)로, 포화 구간(|x|>0.8)에 매 어택마다 들어갔다.
  5 ms 단위 게인을 제거한 뒤 남는 파형 왜곡은 -21.5~-24.8 dB, 볼륨 100%에서는 -16.6 dB까지 커졌다.
- 수정: 샘플 단위 포화를 스테레오 연동 룩어헤드 리미터(2 ms, 릴리스 100 ms, -1 dBFS)로 교체했다.
  같은 입력의 6음 화음·코드 전환에서 왜곡은 70%에서 -31.6~-33.3 dB(7~10 dB 개선),
  100%에서 -30.6~-31.9 dB(12~15 dB 개선)이다.
  임계값 아래(단음·약한 연주)는 2 ms 지연 외에 샘플 단위로 동일하다.
  세게 친 화음 구간의 RMS는 1.5~3.3 dB 낮아진다(포화가 만들던 음량 증가분).
- 비교 WAV: [6음 ff 포화](verification/chords/chord6_ff-saturate.wav) /
  [리미터](verification/chords/chord6_ff-limit.wav),
  [페달 코드 전환 포화](verification/chords/changes_pedal-saturate.wav) /
  [리미터](verification/chords/changes_pedal-limit.wav).
  포화 쪽은 1.1.1 합성기 출력과 오차 2e-5 이내로 일치함을 확인했다.
- 새 네이티브 검사: `LimiterTest`(3개 샘플레이트, 임계값 아래 무변형, 최대 20배 과입력에서도
  피크 한계, 버퍼 크기 독립, 복귀, 리셋 시 잔여음 없음), `ChordRender`(합성기 출력이 -1 dBFS 이하,
  화음 왜곡 -30 dB 미만). 앱 자체 검사에 리미터 한계 검사를 추가했다.
- 로컬: assembleDebug, testDebugUnitTest, lintDebug, assembleDebugAndroidTest 통과.
  Android 15 ATD 에뮬레이터에서 계측 테스트 3개 통과(`OK (3 tests)`, 앱 자체 네이티브 검사 포함).
  1.1.2 APK 서명 인증서가 1.1.1과 동일함을 확인했다.
- 남은 확인: S25 스피커와 가능하면 유선 출력으로 1.1.1과 비교 청음, 추가 2 ms 지연 체감.

## 2026-09-29 메트로놈 (1.1.0)

- 메트로놈 켜기/끄기, 40~240 BPM, 독립 클릭 음량, 설정 저장 구현.
- [GitHub 검사](https://github.com/Yushin-L/piano-sounds/actions/runs/36526212961):
  APK 빌드, JUnit 8개, Lint, Android 테스트 APK 빌드 및 네이티브 메트로놈 검사 통과.
- 네이티브 검사는 44.1/48/96 kHz × 40/137/240 BPM에서 각각 10분 분량의 오디오 프레임을
  처리한다. 클릭 시작이 이론적 박자 위치를 샘플 단위로 올림한 값과 일치하고 누적 오차가 없음을 검사했다.
  가변 버퍼 경계, 템포 변경, 정지·재시작, 클릭 음량 0, 합산 출력 범위 검사도 통과했다.
- Android 15 AOSP ATD 에뮬레이터에서 계측 테스트 3개 통과 (`OK (3 tests)`, 62.593초).
  새 검사는 UI 시작/정지, 6음 화음과 클릭 동시 재생, 메트로놈만 정지할 때 피아노 음 유지,
  전체 음 정지, 배경 전환 시 오디오 종료, 재진입·재생성 후 OFF 상태 및 BPM·음량 저장을 검증한다.
- 에뮬레이터 부하로 Gradle 장치 조회와 스트리밍 설치가 실패해, APK를 `adb install --no-streaming -r`로
  설치한 뒤 `am instrument -w`로 동일한 테스트 APK의 전체 계측 검사를 실행했다.
- [메트로놈 화면](verification/metronome.png)은 실제 Android View를 Canvas로 그린 에뮬레이터 화면이다.
  화면의 MIDI 장치는 가상 테스트 장치이며, 표시된 오디오 끊김은 소프트웨어 에뮬레이터의 관측값이다.
- 새 로컬 APK와 기존 1.0.2 APK의 서명 인증서가 동일함을 확인했다.
- 남은 확인: S25 + KeyLab에서 클릭 청음, 연주 중 체감 박자 안정성, 화면 조작성.
  기존 화음 뭉침 문제의 해결 여부는 별도 이슈 #1에서 검증한다.

## 2026-09-23 연결 안정화 (1.0.2)

실기기 피드백에서 연속 입력이 끊기고 수정 후 연결이 되지 않는 현상이 보고되었다.
MIDI `flush`는 장치 해제가 아니므로 연주 중 음을 정지하거나 파서의 running status를
지우지 않도록 했다. 오디오 장치 변경으로 스트림을 다시 열 때도 MIDI 포트를 유지하며,
오디오 준비 상태와 관계없이 건반 연결 상태를 표시한다.

`testDebugUnitTest`, `lintDebug`, `assembleDebug` 통과.
Android 15 AOSP ATD 에뮬레이터에서 `connectedDebugAndroidTest` 2개 통과.
계측 테스트에 가상 MIDI 포트 연결, `flush` 후 유지되는 음, `flush`를 가로지르는
running status 입력, 앱 재진입 후 재연결을 포함했다. 실제 Galaxy S25와 KeyLab의
USB 연결은 이 개발 환경에 장치가 없어 사용자의 재검증이 필요하다.

검증 환경: Linux x86_64, JDK 17, Android SDK 35, NDK 27.2.12479018,
Android 15/API 35 AOSP ATD 에뮬레이터(소프트웨어 CPU 에뮬레이션, 호스트 오디오 출력 비활성).

## 빌드와 정적 검사

- Gradle Wrapper로 `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest` 성공.
- MIDI 파서 JUnit 테스트 8개 통과. [원본 결과](verification/midi-parser.xml).
- Android Lint: 오류 0개. 고정한 의존성의 새 버전 안내 및 백업 설정 관련 경고 5개.
  [원본 결과](verification/lint.txt). API 오류를 숨기는 baseline은 사용하지 않았다.
- 음원 90개 헤더, 30개 기준음, 3개 세기층, stereo 길이와 전체 SHA-256 검증 통과.
- APK v2 서명 검증 통과. ARM64의 `libpiano`, `liboboe`, `libc++_shared` ELF LOAD 정렬과
  APK ZIP 정렬 모두 16 KB 호환 검사 통과.
- APK manifest와 DEX 분석으로 테스트 MIDI 서비스/테스트 클래스가 배포 APK에 없음을 확인.
- 인터넷과 마이크 권한 없음. 앱 음원은 `assets/grand.pno`에 포함되어 있다.

## Android 실행 검증

**계측 테스트 2개 모두 통과, 실패 0개.** [JUnit 결과](verification/android-integration.xml) ·
[전체 빌드 로그](verification/build.txt).

| 검증 대상 | 실행 결과 |
|---|---|
| 내장 음원 | 실제 90개 샘플 로딩, A0–C8 88개 음 모두 유효한 오디오 출력 |
| 연주 표현 | 세기에 따른 출력 차이, Note On velocity 0, 페달 유지/해제, 채널 분리 통과 |
| 음 관리 | 반복 타건, CC120/121/123, 전체 정지, 96 voices 제한 통과 |
| 오디오 | 44.1/48/96 kHz 처리, 출력 유한값/범위, 볼륨 0과 복원 통과 |
| 이벤트 전달 | 큐 최대 용량과 두 생산자의 동시 입력 순서 검증 통과 |
| Android MIDI | 별도 테스트 APK의 MIDI 서비스 → Android MIDI API → 앱 → 실제 Oboe 스트림 연결 통과 |
| 앱 동작 | 입력 횟수 표시, 페달, 전체 음 정지 버튼, 백그라운드에서 스트림 종료, 복귀/Activity 재생성 후 재연결 통과 |

MIDI 서비스 시작 중 동시 연결 요청을 Android가 일시적으로 거절하는 경우를 발견해,
제한된 자동 재시도와 장치 상태 변경 시 재연결을 추가하고 검증했다.

[네이티브 검증 요약](verification/native-checks.txt)과 [오디오 분석](verification/rendered-audio.json)을 함께 보존했다.
`dist/piano-demo.wav`는 실제 엔진이 만든 48 kHz, stereo, 6초 PCM16 출력이다.
파형은 무음이 아니며 양 채널 차이가 있고, 유한값과 출력 범위 검사를 통과했다.
내장 A4 샘플의 주파수 분석은 약 440 Hz를 확인했다. 이는 휴대폰 스피커 청음이나 실측 지연 검증을 대체하지 않는다.

일반 Google APIs 에뮬레이터의 최초 시도는 시스템 서비스 오류와 테스트 프로세스 종료로 실패했다.
기존 시스템 앱을 변경하지 않고 새 공식 AOSP ATD 이미지에서 전체 테스트를 완료했다.
ATD는 디스플레이 렌더링을 비활성화하므로 화면 검토 이미지는 실제 Activity의 측정된 View 트리를
Android Canvas로 그리는 방식으로 생성한다. 실제 S25 스크린샷으로 표현하지 않는다.

[앱 화면 렌더링](verification/midi-connected.png)을 확인했고, 캡처를 포함한 추가 UI 실행 검사도
통과했다([실행 결과](verification/ui-test.txt)). 화면의 KeyLab Test Loopback은 실제 건반이 아닌
계측용 가상 MIDI 장치다. 화면에 보이는 오디오 끊김은 가속 없는 에뮬레이터에서의 관측값이다.

## 실제 Galaxy S25 + KeyLab 확인 항목

개발 환경에 사용자의 실제 건반/휴대폰이 연결되어 있지 않으므로 아래는 실측하지 않았다.
에뮬레이터 결과로 USB 전원 공급이나 휴대폰의 실제 연주 지연을 보장하지 않는다.

1. 기존 USB-C 어댑터로 연결한 뒤 건반 전원이 유지되고 앱에 장치명이 표시되는지 확인.
2. 미리 듣기 및 건반 연주가 휴대폰 스피커로 들리는지, 부드럽게/강하게 칠 때 차이가 나는지 확인.
3. 페달을 누른 채 건반을 떼면 소리가 이어지고, 페달을 떼면 감쇠하는지 확인.
4. 여러 음을 누른 상태에서 USB를 빼거나 전체 음 정지를 눌렀을 때 소리가 멈추는지 확인.
5. USB 재연결, 전화/다른 앱 전환 후 복귀, 유선 오디오 연결 변경 후 다시 연주되는지 확인.
6. 빠른 반복음과 두 손 화음으로 지연/끊김을 확인. 수치가 필요하면 건반 입력과 오디오 출력을
   외부 장비로 함께 측정한다. 앱의 샘플레이트/버퍼 수치만으로 실제 지연을 계산하지 않는다.

직접 설치 APK는 개발용 서명이다. 앱스토어 공개 배포와 배포용 키 관리는 수행하지 않았다.
