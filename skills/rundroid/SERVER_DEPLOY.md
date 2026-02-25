# rundroid 서버 배포 가이드

rundroid 서버는 Cloudflare Workers + Durable Objects로 구성됩니다.

## 사전 요구사항

- Node.js 18+
- Cloudflare 계정 (Workers Paid 또는 Free plan - Durable Objects는 Paid 필요)
- `wrangler` CLI (프로젝트 devDependencies에 포함)

## 초기 설정

```bash
cd server
npm install
```

## 프로젝트 구조

```
server/
├── wrangler.toml          # Workers 설정 (DO 바인딩, 마이그레이션)
├── package.json
├── tsconfig.json
└── src/
    ├── index.ts            # Worker 진입점 (REST 라우팅)
    ├── device-controller.ts # Durable Object (WebSocket + 명령 중계)
    └── types.ts            # 공유 타입
```

## wrangler.toml 설정

```toml
name = "rundroid-server"        # Worker 이름 (URL에 반영됨)
main = "src/index.ts"
compatibility_date = "2024-12-01"

[durable_objects]
bindings = [
  { name = "DEVICE_CONTROLLER", class_name = "DeviceController" }
]

[[migrations]]
tag = "v1"
new_classes = ["DeviceController"]
```

- `name`: 배포 시 `https://<name>.<subdomain>.workers.dev` URL이 됩니다. 원하는 이름으로 변경하세요.
- Durable Object `DeviceController`는 단일 인스턴스(`idFromName("default")`)로 동작합니다.

## 타입 체크

```bash
npm run typecheck
```

## 인증 설정 (선택)

토큰을 설정하면 인증이 강제됩니다. 설정하지 않으면 기존처럼 무인증으로 동작합니다.

```bash
cd server
npx wrangler secret put API_TOKEN
npx wrangler secret put DEVICE_TOKEN
```

- `API_TOKEN`: `/api/*`, `/mcp` 호출 시 필요 (Authorization Bearer 또는 `X-API-Key`)
- `DEVICE_TOKEN`: `/ws` 연결 시 필요 (Authorization Bearer 헤더 또는 `?token=` 쿼리)
- Android 앱은 메인 화면의 `Device Token (optional)` 입력칸에 `DEVICE_TOKEN` 값을 넣으면 됩니다.

예시:

```bash
curl -s https://<server>/api/status \
  -H "Authorization: Bearer <API_TOKEN>"
```

## 로컬 개발

```bash
npm run dev
# → http://localhost:8787 에서 실행
```

`wrangler dev`는 Durable Objects를 로컬에서 에뮬레이션합니다. WebSocket 연결, REST API 모두 테스트 가능합니다.

## 배포

```bash
npm run deploy
# 또는
npx wrangler deploy
```

처음 배포 시 `wrangler login`으로 Cloudflare 인증이 필요합니다.

배포 완료 후 출력되는 URL이 서버 주소입니다:

```
Published rundroid-server (x.xx sec)
  https://rundroid-server.<your-subdomain>.workers.dev
```

## 배포 확인

```bash
# 서버 상태 확인 (디바이스 미연결 시)
curl -s https://rundroid-server.<your-subdomain>.workers.dev/api/status
# → {"connected": false}
```

## 커스텀 도메인 (선택)

Cloudflare 대시보드에서 Workers → rundroid-server → Settings → Domains & Routes에서 커스텀 도메인을 추가할 수 있습니다.

## 아키텍처 참고

```
API Client          Cloudflare Workers + DO         Android App
    |                       |                           |
    |--- REST request ----->|                           |
    |                       |--- WebSocket command ---->|
    |                       |<-- WebSocket response ----|
    |<-- HTTP response -----|                           |
```

- **Worker** (`index.ts`): REST API 라우팅, CORS 처리, WebSocket upgrade 전달
- **Durable Object** (`device-controller.ts`): WebSocket 연결 관리, requestId 기반 요청-응답 매칭, WebSocket Hibernation API 사용 (유휴 시 메모리 해제)
- 스크린샷은 바이너리 WebSocket 프레임으로 전송됩니다: `[36바이트 requestId][PNG 데이터]`

## 트러블슈팅

### Durable Object 에러
- `migrations` 섹션이 누락되면 DO 바인딩이 실패합니다. `wrangler.toml`에 `[[migrations]]` 블록이 있는지 확인하세요.
- DO 클래스 이름을 변경하면 새 마이그레이션 태그가 필요합니다.

### WebSocket 연결 안 됨
- Worker가 `/ws` 경로에서 WebSocket upgrade를 처리합니다. 안드로이드 앱의 WebSocket URL이 `wss://<server-url>/ws`인지 확인하세요.

### 504 Timeout
- 명령 타임아웃은 30초입니다. 기기가 연결되어 있지만 응답하지 않으면 504가 반환됩니다.
- Accessibility Service가 비활성화되었거나 앱이 백그라운드로 전환된 경우 발생할 수 있습니다.
