# 검증 기록

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
