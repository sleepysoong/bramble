# 전체 코드 검토와 수정

2026-09-30 기준으로 Go 프록시·stdio MCP·Android 수신/전송·Compose UI·빌드/릴리스 설정을 검토했다. 구현과 회귀 검사는 아래 파일에서 함께 확인할 수 있다.

## 기능 결함과 수정

| 위치 | 재현 조건과 영향 | 수정 |
| --- | --- | --- |
| `internal/relay/relay.go`, `create` | 완료 결과를 잠시 보관하는 동안 연속 요청을 보내면 완료 작업까지 대기 큐 64개 한도에 포함돼 새 요청이 막혔다. | 대기 작업과 결과 보관 한도를 분리하고, 완료 결과 보관량을 제한했다. 요청 자체의 만료 타이머도 추가했다. |
| `internal/mcp/mcp.go`, `Run` / `internal/relay/relay.go`, `ClientRequest` | harness 취소 알림을 무시하고 stdin 종료 뒤에도 요청을 기다려 취소된 작업이 휴대폰에 남았다. 생성 응답 전에 취소하면 ID를 몰라 정리하지 못했다. | 진행 호출별 취소 컨텍스트, 취소 알림, EOF 정리를 연결한다. ID를 미리 생성하고 생성보다 취소가 먼저 도착한 경우에도 서버가 취소 기록을 남긴다. |
| `internal/relay/relay.go`, `decodeBody` / MCP 인자 해석 | JSON 뒤에 추가 데이터를 붙이거나 도구 스키마 밖 인자를 넣어도 통과했다. 한국어 요청 설명은 바이트 수로 잘못 제한했다. | JSON 객체 하나만 허용하고 알 수 없는 필드를 거부한다. 설명은 Unicode 500글자로 제한한다. |
| `internal/relay/relay.go`, `receiveFile` | 동일 요청에 동시 업로드하거나 완료 후 다시 업로드하면 불필요한 파일을 만들 수 있었다. | 업로드 상태를 잠금 아래에서 예약하고, 충돌·실패·만료 시 임시 파일을 정리한다. 파일명 경로 구분자와 UTF8 헤더도 검증한다. |
| `RelayClient.kt`, HTTP 요청 | 기본 리다이렉트가 설정 서버 밖으로 요청을 보낼 수 있었고, 코루틴 취소 후에도 긴 폴링 I/O가 남았다. | 자동 리다이렉트를 끄고 취소 시 연결을 IO 스레드에서 끊는다. 주소·토큰·응답 종류·ID·본문 크기를 검증한다. |
| `RelayClient.kt`, `upload` / `sendClipboard` | 앱에서 파일 제한을 확인하지 않았고, 한글 파일명은 ASCII 필터에 의해 사라졌다. | 메타데이터와 실제 스트림에 20 MiB 제한을 적용한다. UTF8 파일명과 UTF8 기준 1 MiB 클립보드 제한을 서버와 맞춘다. |
| `RelayService.kt`, `restartPolling` / `handleResponse` | 중지·만료·연결 변경 후 옛 전송이 새 상태를 덮어썼다. 동일 연결 START도 기존 요청을 지웠다. | 응답 작업을 별도로 취소하고 연결 세대와 작업 ID를 확인한다. 중복 시작은 기존 요청을 유지한다. |
| `RelayService.kt`, 상태 확인 | harness에서 취소해도 앱은 마감까지 새 요청을 받지 못했다. | 진행 요청의 서버 상태를 조회해 종료 알림과 전송을 정리한다. 구형 프록시의 상태 경로 404에서는 로컬 만료를 사용한다. |
| `MainActivity.kt`, 파일 선택 콜백 | 요청 A의 파일 선택 중 A가 만료되고 B가 도착하면 A의 파일을 B의 응답으로 보낼 수 있었다. | 선택 시작 당시 ID를 보관하고 회전 시 복원한다. 콜백에서 같은 요청인지 확인한 뒤 전송한다. |
| Android Manifest / 백업 XML | 연결 토큰과 수신 설정이 기본 백업 대상이었다. | 클라우드 백업과 기기 간 전송에서 `connection.xml`을 제외한다. |
| `MainActivity.kt`, 테마 / Android Manifest | Surface 없이 MaterialTheme만 적용하면 기본 글자색이 다크 테마에 전달되지 않았다. edge-to-edge 화면에 IME resize도 명시하지 않았다. | `LocalContentColor`에 앱의 `onSurface`를 제공하고, `adjustResize`와 소비되는 IME 인셋을 사용한다. |
| `.github/workflows/build-and-release.yml` | 빌드 뒤 rebase하면 태그 소스와 APK·Go 바이너리의 소스가 달라질 수 있었다. | 빌드 후 rebase를 제거하고 버전 커밋과 태그를 atomic push한다. 최신 main 충돌 시 새 실행에서 다시 빌드한다. |

## Liquid Glass 적용

[Hoard의 Liquid Glass 문서](https://github.com/sleepysoong/hoard/blob/main/LIQUID_GLASS.md)의 재질·레이어·모션·시각 검증 기준을 사용한다.

- `Glass.kt`: Thin/Regular/Thick 톤, 헤어라인 림, 톤별 내부 그림자, 카드 그림자, 누름에 따른 블러와 렌즈 변화를 공통화했다. 카드의 넓은 하이라이트와 렌즈 깊이 효과는 끄고, 테마 선택 캡슐에만 스페큘러 림을 사용한다.
- 단색 배경 기록 레이어와 모든 유리 소비자를 형제로 유지한다. 테마 선택의 라벨·틴트도 별도 기록 레이어이며 캡슐은 그 레이어의 형제다. 썸의 부풀기와 속도에 따른 늘어남은 `drawBackdrop.layerBlock`에서 계산한다.
- `MainActivity.kt`: 고정 상단 바, 수신 상태 배지, 요청 카드, 입력창, 토큰 보기 버튼, 저장·중지·공유·거절·배터리 설정 버튼과 테마 선택을 공통 컴포넌트로 구성했다. 들어온 요청을 화면 위에 배치한다.
- 모든 클릭 컨트롤은 44dp 이상이다. 폼은 전체 폭을 사용하고 파일 공유·거절도 세로로 배치해 좁은 화면과 큰 글꼴에서 넘치지 않게 한다.
- 앱 테마를 재질·선택 라벨·시스템 바까지 전달한다. 기기 테마와 앱 테마가 달라도 선택 글자가 읽히게 한다.
- 상단 바를 스크롤 영역 밖에 두고, 스크롤 끝에 그림자 여유를 준다. 시스템 바와 키보드 인셋을 소비하는 Compose 패딩을 적용한다.

## 검증과 남은 한계

회귀 검사 위치:

- `internal/relay/relay_test.go`, `internal/mcp/mcp_test.go`: 큐·취소·JSON 검증·한도·파일명·동시 업로드·MCP 계약.
- `RelayClientTest.kt`, `RelayServiceTest.kt`: 리다이렉트·취소·파일 제한·요청 상태·중복 시작·중지 경쟁·Activity 없는 요청 수신.
- `ActivityRequestTest.kt`: 선택했던 요청과 현재 요청이 달라진 경우 전송 금지, 정상 선택의 원래 ID 전달, Activity 재생성 후 원래 ID 복원.
- `GlassUiTest.kt`: native 렌더링, 앱/기기 테마가 다른 경우의 선택 글자와 제목 대비, 좁은 화면과 큰 글꼴, 고정 상단 바, 테마 선택 탭/드래그, 버튼 눌림·해제 overshoot·복귀. PNG는 `app/build/test-artifacts/glass/`에 생성한다. CI는 PNG와 HTML 결과를 `android-review-<run_id>` 아티팩트로 7일간 보관한다.

실제 휴대폰의 GPU 렌더링, 제조사 절전 정책, 재부팅 후 수신과 Android 문서 제공자별 파일 선택은 실기기 검증이 필요하다. JVM native 렌더링과 서비스 테스트는 이 검증을 대신하지 않는다. 업로드 파일은 자동 삭제하지 않으며, HTTP LAN 통신과 한 토큰을 공유하는 초기 연결 구조는 README의 사용 범위를 따른다.

최종 소스에서 Android 회귀 테스트 23개(전송 9, 서비스 5, Activity 3, UI 6)와 Go `-race`·`vet` 검사를 통과했다. UI PNG를 직접 확인했으며 [docs/screenshots](docs/screenshots)에 라이트·다크 요청/설정 화면을 보관했다. Lint는 오류 0개, 경고 16개, 힌트 1개다. 의존성 업데이트·KTX 사용·Compose 최적화·아이콘 형태 등의 권고는 남아 있다.
