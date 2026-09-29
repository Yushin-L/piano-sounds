# Piano Sounds

**Arturia KeyLab Essential 88을 Galaxy S25에 연결해 연주하기 위해 만든 Android 오프라인 피아노 앱.**

## 왜 만들었나요?

KeyLab Essential 88은 건반을 누른 음과 세기 등을 MIDI로 전달하는 컨트롤러입니다.
피아노 소리를 내기 위해서는 이 연주 정보를 받아 소리로 바꿔 주는 음원이 필요합니다.
이 프로젝트는 **노트북이나 DAW 없이, 가지고 있는 MIDI 건반과 Android 스마트폰만으로
피아노를 연주하고 연습하고 싶다**는 필요에서 시작했습니다.

앱이 스마트폰 안에서 피아노 음원 역할을 합니다. 건반을 USB로 연결하면 입력을 받아
내장 그랜드피아노 샘플을 재생하고, 휴대폰 스피커 또는 연결된 오디오 출력으로 들려줍니다.
설치 후 연주에는 인터넷 연결이나 별도 음원 다운로드가 필요 없습니다.

## 사용에 필요한 구성

- Arturia KeyLab Essential 88: 이 프로젝트에서 사용한 건반은 USB-B 단자가 있는 모델입니다.
- Galaxy S25: 주 사용 기기입니다. 앱의 최소 지원 버전은 Android 8.0(API 26)이며,
  다른 기기에서는 USB 호스트·MIDI 지원과 연주 지연을 별도로 확인해야 합니다.
- USB-B 데이터 케이블과 USB-C OTG 어댑터: 건반과 휴대폰을 연결합니다.
  실제 사용 중 연결 문제의 원인 중 하나로 케이블이 의심되었으므로 데이터 통신 가능한 케이블이 필요합니다.
- 선택 사항: 서스테인 페달, 유선 오디오 출력. USB 전원이 부족할 때는 건반에 맞는 전원 공급이 필요합니다.

연결 흐름: `KeyLab USB-B → USB 케이블 → USB-C OTG 어댑터 → Galaxy S25 → 피아노 소리`

## 현재 상태

사용자는 실제 건반과 휴대폰으로 소리가 나는 것을 확인했습니다.
4~6개 음을 동시에 누르는 반주에서 소리가 뭉치는 현상이 남아 있으며,
화음 음질 개선은 진행 예정입니다. 1.1.0에서는 먼저 메트로놈을 추가했습니다.

USB MIDI 건반을 연결하면 앱에 포함된 실제 그랜드피아노 샘플을 재생합니다.
Android 네이티브 Kotlin UI, Android MIDI API, C++ 샘플러, Oboe를 사용합니다.

[앱 화면 미리보기](docs/verification/midi-connected.png) · [피아노 소리 샘플](dist/piano-demo.wav) · [검증 기록](docs/VERIFICATION.md)

[다음 업데이트 마일스톤](https://github.com/Yushin-L/piano-sounds/milestone/1) ·
[화음 음질 개선 #1](https://github.com/Yushin-L/piano-sounds/issues/1) ·
[메트로놈 추가 #2](https://github.com/Yushin-L/piano-sounds/issues/2)

## 사용하기

1. 아래 빌드 안내로 만든 `app/build/outputs/apk/debug/app-debug.apk`를 휴대폰으로 옮겨 설치합니다.
   GitHub Actions의 성공한 실행에서도 `piano-sounds-debug` 아티팩트를 받을 수 있습니다.
   개발용 서명 APK이며 빌드된 APK 자체는 Git 소스에 포함하지 않습니다.
2. KeyLab의 USB-B → 기존 USB 케이블 → USB-C OTG 어댑터 → S25 순서로 연결합니다.
3. 앱을 열고 **연주 준비 완료**, **● 건반 이름** 표시를 확인합니다.
4. **소리 미리 듣기**로 스마트폰 오디오를 먼저 확인한 뒤 건반을 연주합니다.
5. 건반을 눌러도 입력 횟수가 늘지 않으면 **연결 확인 · 포트 선택**에서 일반 MIDI 포트를 선택합니다.

피아노 볼륨과 휴대폰 미디어 볼륨을 모두 확인하세요. 화면 건반은 C4–B5 미리 듣기용이며,
외부 건반은 A0–C8의 88개 음 전체를 지원합니다. 서스테인 페달은 CC64 켜짐/꺼짐 방식입니다.
인터넷·회원가입·마이크 권한은 필요하지 않습니다. 앱이 전면에 있을 때 화면을 켜 둡니다.
다른 앱으로 이동하거나 전화 등으로 오디오 포커스를 잃으면 연주를 정지합니다.

### 메트로놈 (1.1.0)

[메트로놈 화면 미리보기](docs/verification/metronome.png)

화면 아래 **메트로놈** 스위치로 시작/정지합니다. 건반 없이도 사용할 수 있습니다.
템포는 40~240 BPM(기본 100)이며 슬라이더와 −/+ 버튼으로 조절합니다.
1.1.1부터 템포 숫자의 ‘직접 입력’ 버튼을 누르면 숫자 키패드로 BPM을 입력할 수 있습니다.
‘적용’ 또는 키패드의 완료 버튼으로 저장하며, 빈 값이나 범위를 벗어난 값은 적용하지 않습니다.
클릭 음량은 피아노 볼륨과 별도로 조절하고, 템포와 클릭 음량은 다음 실행에도 유지됩니다.
일정한 간격의 클릭이며 박자별 강세는 아직 없습니다.

**전체 음 정지**, 앱을 나감, 오디오 포커스 상실 또는 오디오 재시작 시 메트로놈도 멈춥니다.
앱으로 돌아오면 스위치를 다시 켜세요. 화음과 클릭을 함께 재생할 때는 클릭 음량에 비례해
피아노 출력에 최대 25% 여유를 둡니다. 메트로놈을 끄면 원래 피아노 음량으로 부드럽게 돌아옵니다.

## 구현된 기능

- USB MIDI 자동 인식, 연결/해제 감지, 장치와 포트 직접 선택, KeyLab 우선 연결
- 16채널, 건반 누름/뗌, 누르는 세기, 페달, 여러 음 동시 연주(최대 96 voices)
- 실제 피아노의 30개 기준음 × 3개 세기층, stereo, 부드러운 릴리스와 음량 변화
- 오디오 경로 변경/연결 오류 복구, 앱 재진입과 화면 회전 후 재연결
- 입력 횟수/음이름/세기, 페달 상태, 울리는 음 수, 전체 음 정지
- 멀티터치 미리 듣기 건반, 미리 듣기 버튼, 앱 내 출처와 라이선스
- 메트로놈 켜기/끄기, 40~240 BPM, 독립 클릭 음량과 설정 저장

피치벤드, 리버브, 녹음, 백그라운드 연주는 이 버전에 포함하지 않습니다.
88건반 피아노 연주가 목표이므로 MIDI 음역 21–108 밖의 노트는 소리 내지 않습니다.

## 개발 환경과 빌드

필요 도구: JDK 17, Android SDK 35 / Build Tools 35.0.0,
NDK 27.2.12479018, CMake 3.22.1. Gradle Wrapper 8.11.1을 포함합니다.
Android Studio에서 이 폴더를 열거나 다음 명령을 실행하세요.

```sh
git clone https://github.com/Yushin-L/piano-sounds.git
cd piano-sounds
```

JDK 17을 `JAVA_HOME`에 지정하고, Android SDK 위치를 `ANDROID_HOME` 또는
로컬 `local.properties`의 `sdk.dir`로 설정하세요. Android Studio에서 SDK 위치를 설정해도 됩니다.
위 SDK·NDK·CMake 구성 요소를 설치한 뒤 빌드합니다.

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.
ABI는 S25용 `arm64-v8a`와 에뮬레이터용 `x86_64`를 포함하며 16 KB 페이지 크기에 맞춰 링크합니다.
최소 Android 8.0(API 26), 타깃 Android 15(API 35)입니다. Play Store 배포용 서명/타깃 정책 대응은 별도입니다.

약 81 MiB의 변환된 피아노 음원은 소스에 포함되어 있어 일반 빌드에서 재생성할 필요가 없습니다.
최초 빌드에는 Gradle과 빌드 의존성 다운로드를 위한 인터넷 연결이 필요합니다.

기존 개발 환경의 `.tools`에 도구를 따로 설치해 둔 경우에만 아래 설정을 사용합니다.
`.tools`, SDK, 개인별 설정 파일과 서명 키는 저장소에 포함하지 않습니다.

```sh
export JAVA_HOME="$PWD/.tools/jdk"
export ANDROID_HOME="$PWD/.tools/android-sdk"
export GRADLE_USER_HOME="$PWD/.gradle"
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

## 테스트

메트로놈 타이밍·믹싱 검사는 C++17 컴파일러가 있는 환경에서 Android 없이 실행할 수 있습니다.
10분 분량의 가상 오디오 프레임을 여러 BPM·샘플레이트로 처리해 누적 오차와 버퍼 경계를 검사합니다.

```sh
c++ -std=c++17 -O2 -Wall -Wextra -Werror -Iapp/src/main/cpp app/src/test/cpp/MetronomeTest.cpp -o /tmp/piano-metronome-test
/tmp/piano-metronome-test
```

```sh
# 연결된 에뮬레이터/테스트 기기에서 계측 테스트 실행
./gradlew :app:connectedDebugAndroidTest
```

JVM 테스트는 MIDI 데이터 분할, running status, 시스템/실시간 메시지 혼합과 오류 복구를 검증합니다.
계측 테스트는 실제 내장 음원과 네이티브 엔진의 88음, 세기, 페달, 채널 분리, 정지,
96 동시 발음, 출력 범위, 샘플레이트, 동시 이벤트 큐를 검사합니다.
별도 테스트 APK의 가상 MIDI 서비스를 통해 Android MIDI API → 앱 → 오디오 엔진 전체 흐름도 검사합니다.
이 가상 장치는 배포용 앱에는 들어가지 않습니다.

네이티브 테스트는 실제 엔진의 6초 stereo 출력 `piano-demo.wav`를 남깁니다.
Gradle 계측 테스트에서는 `app/build/outputs/connected_android_test_additional_output/` 아래에
결과·WAV·연결 화면이 수집됩니다. `am instrument`로 직접 실행하면 앱 내부 `files/`에 저장됩니다.
실측 결과와 남은 하드웨어 확인 항목은 [검증 기록](docs/VERIFICATION.md)을 참고하세요.

## 음원 출처와 재생성

Salamander Grand Piano v3, **Alexander Holm**, CC BY 3.0.
[원본](https://github.com/sfzinstruments/SalamanderGrandPiano) ·
[라이선스](https://creativecommons.org/licenses/by/3.0/)

원본 중 velocity 4/9/14, 30개 기준음을 사용합니다. SFZ의 시작점 값을 적용하고,
24 kHz stereo PCM16, 최대 12초 및 마지막 250ms fade out으로 변환했습니다.
원본의 모든 16개 세기층과 공명/페달 잡음까지 재현하는 엔진은 아닙니다.
약 81 MiB의 음원은 APK에 이미 포함되어 있으며 첫 실행에서도 다운로드하지 않습니다.

음원 재생성은 개발자만 필요합니다:

```sh
python3 -m venv .tools/audio-env
.tools/audio-env/bin/pip install -r scripts/audio-requirements.txt
.tools/audio-env/bin/python scripts/prepare_piano.py
```

원본 커밋, 파일별 SHA-256, 변환 정보는 [PIANO_SOURCES.json](docs/PIANO_SOURCES.json)에 기록되어 있습니다.
Oboe는 Apache 2.0 라이선스이며 전체 라이선스는 앱 `assets`와 앱 내 정보 화면에 포함합니다.

자세한 구조: [설계 문서](docs/DESIGN.md).
