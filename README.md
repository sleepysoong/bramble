# Bramble

Bramble은 코딩 도구의 MCP 호출을 Android 휴대폰에 전달하는 로컬 릴레이입니다. 현재 제공하는 도구는 두 개입니다.

| MCP 도구 | 휴대폰 동작 | 결과 |
| --- | --- | --- |
| `phone_read_clipboard` | 사용자가 **클립보드 공유**를 누름 | 텍스트를 도구 결과로 반환 |
| `phone_pick_file` | 사용자가 Android 파일 선택기로 파일을 고름 | Go 프록시 호스트에 저장한 파일의 절대 경로를 반환 |

Go 프록시는 요청을 메모리에 보관하고 Android 앱의 긴 폴링 요청에 전달합니다. 사용자가 수신을 켜면 `connectedDevice` 포그라운드 서비스가 앱 화면을 닫은 뒤에도 연결을 유지하고 요청 알림을 표시합니다. 알림을 눌러 앱에서 자료를 공유하거나 요청을 거절합니다. 이 설정은 재부팅과 앱 업데이트 후에도 복원됩니다. 요청은 2분 후 만료되고 서버를 재시작하면 대기 요청이 사라집니다.

## 빌드

- Go 1.23 이상: `go build -o bramble ./cmd/bramble`
- Android: JDK 21, Android SDK 37.0과 Build Tools 36.0.0을 설치한 다음 `./gradlew :app:assembleDebug`
- 디버그 APK: `app/build/outputs/apk/debug/app-debug.apk`

UI는 [Hoard Liquid Glass 기록](https://github.com/sleepysoong/hoard/blob/main/LIQUID_GLASS.md)의 Backdrop 2.0.1, 배경 기록과 소비자 분리, 세 톤의 공통 재질, 헤어라인 림, 다크 테마 규칙을 따릅니다.

## 연결

1. 호스트에서 `./bramble token`을 실행해 토큰을 생성합니다. 토큰은 개인 비밀 저장소에 보관합니다.
2. 호스트에서 아래와 같이 같은 토큰으로 서버를 엽니다. 방화벽에서 휴대폰의 접근만 허용하세요. 기본 주소 `127.0.0.1:8787`은 호스트 내부 전용입니다.

   ```bash
   export BRAMBLE_TOKEN='발급받은 토큰'
   export BRAMBLE_SERVER='http://127.0.0.1:8787'
   ./bramble serve -listen 0.0.0.0:8787 -data "$HOME/bramble-uploads"
   ```

3. APK를 설치하고 앱에 `http://<호스트 LAN IP>:8787`과 같은 토큰을 입력한 뒤 **저장하고 백그라운드 수신 시작**을 누릅니다. 요청 알림 권한을 허용합니다. 수신 중에는 지속 알림이 표시되고 알림이나 앱 화면에서 중지할 수 있습니다. Android 에뮬레이터에서 호스트는 `http://10.0.2.2:8787`입니다.
4. 코딩 도구가 실행되는 호스트에서 `BRAMBLE_TOKEN`과 `BRAMBLE_SERVER` 환경 변수를 설정하고 MCP stdio 서버를 등록합니다.

Codex CLI의 `~/.codex/config.toml` 예시입니다. 실행 파일 경로를 실제 절대 경로로 바꾸고, Codex를 시작할 때 `BRAMBLE_TOKEN`을 환경 변수로 전달하세요. 휴대폰에서 응답할 시간을 위해 도구 제한 시간을 130초로 설정합니다. 이 설정 형식은 [OpenAI Docs의 Codex MCP 문서](https://learn.chatgpt.com/docs/extend/mcp?surface=cli)를 따릅니다.

```toml
[mcp_servers.bramble]
command = "/absolute/path/to/bramble"
args = ["mcp"]
env_vars = ["BRAMBLE_TOKEN", "BRAMBLE_SERVER"]
tool_timeout_sec = 130
```

설정 후 `codex mcp list`로 등록을 확인합니다. 다른 MCP stdio 호스트에서도 같은 실행 파일에 `mcp` 인자를 주면 됩니다. MCP 전송은 줄 단위 JSON-RPC이며 `initialize`, `ping`, `tools/list`, `tools/call`을 지원합니다.

## 데이터와 네트워크

- 모든 HTTP 경로에 Bearer 토큰을 요구합니다. 기본 바인드는 localhost입니다. 브라우저 `Origin` 요청은 거절합니다.
- LAN의 일반 HTTP는 암호화되지 않습니다. 신뢰할 수 있는 LAN에서만 쓰거나 프록시 앞에 HTTPS를 구성하세요. 토큰을 인터넷에 노출하지 마세요.
- 클립보드 텍스트는 서버 메모리에서만 처리합니다. 파일은 `-data` 디렉터리에 0600 권한으로 저장하고 자동 삭제하지 않습니다. 파일 크기 한도는 20 MiB입니다.
- 토큰은 Android 앱의 비공개 설정 저장소에 보관됩니다. 이 초기 버전은 한 토큰을 앱과 MCP 양쪽에 사용하므로 토큰을 공유하면 모든 작업에 접근할 수 있습니다.
- Android의 절전 모드(Doze)는 포그라운드 서비스의 네트워크도 지연시킬 수 있습니다. 즉시 수신이 필요하면 앱의 **배터리 최적화 설정 열기**에서 Bramble을 최적화 예외로 지정하세요. 사용자가 서비스를 중지하거나 앱을 강제 종료한 경우 자동 수신은 중단될 수 있습니다.

## 릴리스 CI

`main` 푸시마다 Go 테스트, Linux amd64/arm64 바이너리, 서명된 APK를 만들고 `version.properties`의 패치 버전을 올린 다음 GitHub Release를 게시합니다. CI에는 `BRAMBLE_KEYSTORE_B64`, `BRAMBLE_KEYSTORE_PASSWORD`, `BRAMBLE_KEY_ALIAS`, `BRAMBLE_KEY_PASSWORD` 저장소 secret이 필요합니다. 키 파일은 Git에 넣지 않습니다.
