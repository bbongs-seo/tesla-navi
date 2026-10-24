# 경고음

0.4.5의 기본 경고음은 프로젝트에서 직접 합성한 48 kHz, 16-bit, mono 2음 종소리입니다. 외부 녹음이나 샘플을 포함하지 않습니다. `WarningChime.kt`에서 부드러운 어택과 릴리스, 감쇠하는 배음을 사용합니다. 앱 볼륨과 휴대폰 미디어 볼륨이 적용되며 실제 과속 경고와 설정의 테스트가 동일한 PCM을 재생합니다. 테스트가 내보낸 실제 PCM 미리듣기는 `app/build/reports/audio/warning-chime.wav`입니다.

## 외부 음원 후보 — 2026-10-24 확인

아래 음원은 현재 앱에 포함하지 않았습니다. 원본 다운로드는 Freesound 로그인이 필요합니다. 각 페이지는 Creative Commons 0(CC0)로 표시되며 복사·수정·배포 및 상업적 이용을 허용한다고 명시합니다. 실제 교체 시 해당 파일의 라이선스를 다시 확인하고 출처를 기록합니다.

- [Soft-Notifications - Bell - HighDing / LegitCheese](https://freesound.org/people/LegitCheese/sounds/571512/): 짧은 종소리, 0.696초, MP3. 짧은 반복 경고의 후보.
- [complete_sound.wav / JustVic](https://freesound.org/people/JustVic/sounds/430800/): 종소리, 2초, 44.1 kHz 16-bit mono WAV. 현재 2초 반복 주기에 맞추려면 끝부분 길이를 조정해야 할 수 있음.
- [Freesound 라이선스 안내](https://freesound.org/help/faq/): 사이트 전체가 같은 라이선스는 아니므로 파일별 CC0 여부 확인 필요.

음색 평가는 설명과 길이에 기반한 후보 선정이며 외부 음원 청취 평가는 하지 않았습니다. 새 기본음의 파형 검사와 재생 상태 테스트는 통과하더라도 실제 차량 스피커에서의 음량·명료도는 별도 확인이 필요합니다.
