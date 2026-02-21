# Android Remote Control System (rundroid) - 구현 계획

## 목표 개요
실물 안드로이드 기기를 REST API를 통해 원격 조작하는 시스템 구축 (Cloudflare Workers + Android App)

## 해결하고자 하는 문제 (니즈)
- 안드로이드 기기를 프로그래밍 방식으로 원격 제어할 수 있는 API가 필요
- Mobilerun과 유사한 아키텍처로, 안드로이드 앱이 기기에 설치되고 서버가 REST API를 제공하며 양방향 WebSocket으로 통신

## 현 상태
- 새 프로젝트 (``)
- 아직 코드 없음

## 솔루션 (목표)
```
API Client (curl/Claude)     Cloudflare Workers + DO        Android App
        |                           |                           |
        |--- REST API request ----->|                           |
        |                           |--- WebSocket command ---->|
        |                           |                           | (execute)
        |                           |<-- WebSocket response ----|
        |<-- HTTP response ---------|                           |
```

- **Worker**: REST API 라우팅, WebSocket upgrade 전달
- **Durable Object**: WebSocket 연결 관리, requestId 기반 요청-응답 매칭, Hibernation API 활용
- **Android App**: Accessibility Service (UI 트리 + 제스처) + MediaProjection (스크린샷) + OkHttp WebSocket

## 비목표 - 하면 안되는 것
- 복잡한 인증/권한 시스템 (초기 버전에서는 제외)
- 멀티 디바이스 지원 (단일 디바이스만 대상)
- UI 테스트 프레임워크 통합

## 비목표 - 범위 밖이지만 추후 가능
- 디바이스 인증 토큰 기반 보안
- 여러 디바이스 동시 제어
- 파일 전송 기능
- 비디오 스트리밍

## 확정된 주요 의사결정
1. **Durable Object RPC**: Worker가 DO 메서드를 직접 호출 (`stub.sendCommand()`)
2. **WebSocket Hibernation**: DO 유휴 시 메모리에서 해제, ping/pong 자동 처리
3. **바이너리 스크린샷**: `[36바이트 requestId][PNG 데이터]` 형태의 바이너리 프레임
4. **Accessibility Service 싱글톤**: `companion object`으로 인스턴스 접근
5. **MediaProjection 별도 서비스**: Android 14+ foreground service type `mediaProjection`

## 프로젝트 구조
```
rundroid/
├── PLAN.md
├── server/                          # Cloudflare Workers
│   ├── package.json
│   ├── tsconfig.json
│   ├── wrangler.toml
│   └── src/
│       ├── index.ts                 # Worker 진입점 (REST 라우팅 + WS upgrade)
│       ├── device-controller.ts     # Durable Object (WebSocket 관리 + 명령 중계)
│       └── types.ts                 # 공유 타입 정의
│
└── android/                         # Android App (Kotlin)
    └── app/src/main/
        ├── AndroidManifest.xml
        ├── res/xml/accessibility_service_config.xml
        └── java/com/example/remotecontrol/
            ├── MainActivity.kt
            ├── service/
            │   ├── RemoteControlAccessibilityService.kt
            │   └── ScreenCaptureService.kt
            ├── websocket/
            │   ├── WebSocketManager.kt
            │   └── MessageHandler.kt
            ├── command/
            │   └── CommandRouter.kt
            ├── model/
            │   ├── Command.kt
            │   ├── A11yNode.kt
            │   └── AppInfo.kt
            └── util/
                ├── ScreenshotUtil.kt
                ├── A11yTreeUtil.kt
                └── PackageUtil.kt
```

## 통신 프로토콜

**명령 (서버->앱):**
```json
{ "requestId": "uuid", "command": "action/tap", "params": { "x": 540, "y": 1200 } }
```

**응답 (앱->서버) - JSON:**
```json
{ "requestId": "uuid", "success": true, "data": { "performed": true } }
```

**응답 (앱->서버) - 바이너리 (스크린샷):**
```
[36 bytes requestId ASCII][PNG bytes]
```

## REST API 엔드포인트

| Method | Path | 설명 |
|--------|------|------|
| GET | `/api/status` | 디바이스 연결 상태 |
| POST | `/api/screenshot` | 스크린샷 캡처 -> `image/png` |
| POST | `/api/a11y-tree` | Accessibility 트리 |
| POST | `/api/action/tap` | 좌표 탭 |
| POST | `/api/action/tap-a11y` | A11y 경로 탭 |
| POST | `/api/action/swipe` | 스와이프 |
| POST | `/api/action/back` | 뒤로가기 |
| POST | `/api/action/home` | 홈 |
| POST | `/api/action/recent` | 최근 앱 |
| POST | `/api/action/type` | 텍스트 입력 |
| POST | `/api/action/key` | 키 입력 |
| POST | `/api/action/clear-input` | 입력 지우기 |
| POST | `/api/app/install` | 앱 설치 |
| GET | `/api/app/list` | 앱 목록 |
| POST | `/api/app/launch` | 앱 실행 |
| POST | `/api/app/stop` | 앱 종료 |
| POST | `/api/app/uninstall` | 앱 삭제 |

---

## 상세 실행 계획

### 작업 1: 서버 구현 (Cloudflare Workers) [의존: 없음]
**범위**: server/ 디렉토리 전체

1-1. 프로젝트 초기화
  - package.json, tsconfig.json, wrangler.toml 생성
  - 의존성: wrangler, @cloudflare/workers-types

1-2. types.ts 작성
  - DeviceCommand, DeviceResponse, BinaryResponse 타입
  - REST API 관련 타입

1-3. device-controller.ts (Durable Object)
  - WebSocket Hibernation API 사용
  - webSocketMessage/webSocketClose/webSocketError 핸들러
  - sendCommand() RPC 메서드 (requestId 기반 요청-응답 매칭)
  - 바이너리 프레임 처리 (스크린샷)
  - 30초 타임아웃

1-4. index.ts (Worker 진입점)
  - REST API 라우팅 (모든 엔드포인트)
  - WebSocket upgrade 처리 (/ws 경로)
  - DO stub을 통한 명령 전달
  - 스크린샷 엔드포인트는 image/png Content-Type으로 응답
  - CORS 헤더

### 작업 2: 안드로이드 앱 - 기초 구조 [의존: 없음]
**범위**: android/ 프로젝트 초기화 + WebSocket + 기본 서비스

2-1. Gradle 프로젝트 설정
  - build.gradle.kts (project, app)
  - settings.gradle.kts
  - gradle.properties
  - 의존성: OkHttp, Gson, Kotlin coroutines

2-2. AndroidManifest.xml
  - 권한: INTERNET, FOREGROUND_SERVICE, FOREGROUND_SERVICE_MEDIA_PROJECTION
  - Accessibility Service 선언
  - ScreenCapture foreground service 선언

2-3. 모델 클래스 (model/)
  - Command.kt: DeviceCommand 데이터 클래스
  - A11yNode.kt: Accessibility 노드 표현
  - AppInfo.kt: 앱 정보

2-4. WebSocket 통신 (websocket/)
  - WebSocketManager.kt: OkHttp WebSocket, 자동 재연결, 바이너리 전송
  - MessageHandler.kt: 수신 메시지 파싱 -> CommandRouter 전달

2-5. MainActivity.kt
  - Accessibility Service 활성화 안내/이동
  - MediaProjection 권한 요청
  - WebSocket 서버 URL 설정
  - 연결 상태 표시

2-6. accessibility_service_config.xml

### 작업 3: 안드로이드 앱 - 핵심 명령 [의존: 작업 2]
**범위**: 서비스 구현 + 핵심 명령

3-1. RemoteControlAccessibilityService.kt
  - companion object 싱글톤 패턴
  - onAccessibilityEvent/onServiceConnected
  - Accessibility 트리 탐색 메서드
  - dispatchGesture (탭, 스와이프)
  - performGlobalAction (back, home, recent)
  - 텍스트 입력 (setText via AccessibilityNodeInfo)

3-2. ScreenCaptureService.kt
  - Foreground service (mediaProjection 타입)
  - MediaProjection + ImageReader로 스크린샷
  - PNG 바이트 반환

3-3. CommandRouter.kt
  - 명령 문자열 -> 실행 함수 매핑
  - screenshot, a11y-tree, action/*, app/* 라우팅
  - 응답 생성 (JSON 또는 바이너리)

3-4. 유틸리티 (util/)
  - ScreenshotUtil.kt: Bitmap -> PNG 변환
  - A11yTreeUtil.kt: AccessibilityNodeInfo -> A11yNode 트리 변환
  - PackageUtil.kt: 앱 목록, 실행, 종료

### 작업 4: 안드로이드 앱 - 고급 명령 [의존: 작업 3]
**범위**: 나머지 명령 구현

4-1. A11y 경로 기반 탭 (tap-a11y)
  - 경로 문자열로 노드 검색 -> 노드 중심점 탭

4-2. 키 입력 (key)
  - KeyEvent 디스패치

4-3. 앱 관리
  - install (PackageInstaller API)
  - uninstall (Intent)
  - list, launch, stop (PackageManager + ActivityManager)

---

## 검증 계획

### 서버 검증
- [ ] `wrangler dev`로 로컬 실행 확인
- [ ] wscat으로 WebSocket 연결 테스트
- [ ] curl로 각 REST 엔드포인트 호출 확인 (디바이스 미연결 시 적절한 에러)
- [ ] 타입 체크 통과 (tsc --noEmit)

### 안드로이드 검증
- [ ] 프로젝트 빌드 성공 (./gradlew assembleDebug)
- [ ] APK 설치 가능
- [ ] Accessibility Service 활성화 가능
- [ ] MediaProjection 권한 획득 가능
- [ ] WebSocket 서버에 연결 가능

### E2E 검증
- [ ] curl로 REST API 호출 -> 기기에서 동작 확인 -> 응답 수신
- [ ] 스크린샷 요청 -> PNG 이미지 수신
- [ ] a11y-tree 요청 -> JSON 트리 수신
- [ ] 탭/스와이프 -> 기기에서 동작 확인
