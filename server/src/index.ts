import type { CommandRequest, DeviceControllerStub, DeviceResponse } from "./types";
import { handleMcpRequest, sleep, type JsonRpcRequest } from "./mcp";

export { DeviceController } from "./device-controller";

export interface Env {
  DEVICE_CONTROLLER: DurableObjectNamespace;
}

const CORS_HEADERS: Record<string, string> = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET,POST,OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type",
};

class HttpError extends Error {
  constructor(
    public readonly status: number,
    message: string,
  ) {
    super(message);
  }
}

function withCors(response: Response): Response {
  const headers = new Headers(response.headers);
  for (const [key, value] of Object.entries(CORS_HEADERS)) {
    headers.set(key, value);
  }

  const init: ResponseInit & { webSocket?: WebSocket } = {
    status: response.status,
    statusText: response.statusText,
    headers,
  };
  const webSocket = (response as Response & { webSocket?: WebSocket }).webSocket;
  if (webSocket) {
    init.webSocket = webSocket;
  }

  return new Response(response.body, init);
}

function jsonResponse(data: unknown, status = 200): Response {
  return withCors(
    new Response(JSON.stringify(data), {
      status,
      headers: { "content-type": "application/json; charset=utf-8" },
    }),
  );
}

function getStub(env: Env): DeviceControllerStub {
  const id = env.DEVICE_CONTROLLER.idFromName("default");
  return env.DEVICE_CONTROLLER.get(id) as DeviceControllerStub;
}

async function readJsonObject(request: Request): Promise<Record<string, unknown>> {
  let body: unknown;
  try {
    body = await request.json();
  } catch {
    throw new HttpError(400, "Invalid JSON body");
  }
  if (typeof body !== "object" || body === null || Array.isArray(body)) {
    throw new HttpError(400, "Request body must be a JSON object");
  }
  return body as Record<string, unknown>;
}

function readNumber(body: Record<string, unknown>, key: string): number {
  const value = body[key];
  if (typeof value !== "number" || Number.isNaN(value)) {
    throw new HttpError(400, `${key} must be a number`);
  }
  return value;
}

function readString(body: Record<string, unknown>, key: string): string {
  const value = body[key];
  if (typeof value !== "string") {
    throw new HttpError(400, `${key} must be a string`);
  }
  return value;
}

function mapErrorToStatus(error: unknown): { status: number; message: string } {
  if (error instanceof HttpError) {
    return { status: error.status, message: error.message };
  }

  const message = error instanceof Error ? error.message : "Unknown error";
  const lower = message.toLowerCase();

  if (lower.includes("not connected")) {
    return { status: 503, message };
  }
  if (lower.includes("timed out")) {
    return { status: 504, message };
  }
  if (lower.includes("invalid json")) {
    return { status: 400, message };
  }
  return { status: 500, message };
}

function wantScreenshot(url: URL): boolean {
  return url.searchParams.get("screenshot") === "true";
}

function wantA11yTree(url: URL): boolean {
  return url.searchParams.get("a11y_tree") === "true";
}

function arrayBufferToBase64(buffer: ArrayBuffer): string {
  const bytes = new Uint8Array(buffer);
  let binary = "";
  for (let i = 0; i < bytes.byteLength; i++) {
    binary += String.fromCharCode(bytes[i]);
  }
  return btoa(binary);
}

async function executeWithFollowUp(
  stub: DeviceControllerStub,
  command: string,
  params: Record<string, unknown> | undefined,
  url: URL,
): Promise<Response> {
  const screenshot = wantScreenshot(url);
  const a11yTree = wantA11yTree(url);

  if (!a11yTree) {
    // Legacy behavior: pass screenshot param to device, return PNG or JSON directly
    const cmdParams = screenshot ? { ...params, screenshot: true } : params;
    return commandJsonResponse(await stub.sendCommand(command, cmdParams));
  }

  // a11y_tree mode: send action without screenshot flag, then capture separately
  const actionResult = await stub.sendCommand(command, params);
  if (actionResult instanceof ArrayBuffer) {
    return jsonResponse({ error: "Unexpected binary response from action" }, 502);
  }
  if (!actionResult.success) {
    return jsonResponse(actionResult, 502);
  }

  await sleep(500);

  const promises: Promise<unknown>[] = [];
  if (screenshot) promises.push(stub.sendCommand("screenshot"));
  promises.push(stub.sendCommand("a11y-tree"));

  const results = await Promise.all(promises);
  let idx = 0;

  const response: Record<string, unknown> = {
    requestId: actionResult.requestId,
    success: actionResult.success,
    data: actionResult.data,
  };

  if (screenshot) {
    const screenshotResult = results[idx++];
    if (screenshotResult instanceof ArrayBuffer) {
      response.screenshot = arrayBufferToBase64(screenshotResult);
    }
  }

  const a11yResult = results[idx] as DeviceResponse;
  if (a11yResult && !(a11yResult instanceof ArrayBuffer)) {
    response.a11yTree = a11yResult.data;
  }

  return jsonResponse(response);
}

function isValidPng(data: ArrayBuffer): boolean {
  if (data.byteLength < 8) return false;
  const h = new Uint8Array(data, 0, 8);
  return (
    h[0] === 0x89 &&
    h[1] === 0x50 &&
    h[2] === 0x4e &&
    h[3] === 0x47 &&
    h[4] === 0x0d &&
    h[5] === 0x0a &&
    h[6] === 0x1a &&
    h[7] === 0x0a
  );
}

function commandJsonResponse(result: DeviceResponse | ArrayBuffer): Response {
  if (result instanceof ArrayBuffer) {
    if (!isValidPng(result)) {
      return jsonResponse({ error: "Invalid or empty image" }, 502);
    }
    return withCors(
      new Response(result, {
        status: 200,
        headers: { "content-type": "image/png" },
      }),
    );
  }

  if (!result.success) {
    return jsonResponse(result, 502);
  }
  return jsonResponse(result, 200);
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    if (request.method === "OPTIONS") {
      return new Response(null, { status: 204, headers: CORS_HEADERS });
    }

    const url = new URL(request.url);
    const { pathname } = url;
    const method = request.method;
    const stub = getStub(env);

    try {
      if (method === "GET" && pathname === "/ws") {
        const response = await stub.fetch(request);
        return withCors(response);
      }

      if (method === "POST" && pathname === "/mcp") {
        const body = (await request.json()) as JsonRpcRequest;
        if (body.method?.startsWith("notifications/")) {
          await handleMcpRequest(body, stub);
          return jsonResponse({});
        }
        return jsonResponse(await handleMcpRequest(body, stub));
      }

      if (method === "GET" && pathname === "/api/status") {
        const status = await stub.getStatus();
        return jsonResponse(status);
      }

      if (method === "POST" && pathname === "/api/screenshot") {
        return commandJsonResponse(await stub.sendCommand("screenshot"));
      }

      if (method === "POST" && pathname === "/api/a11y-tree") {
        return commandJsonResponse(await stub.sendCommand("a11y-tree"));
      }

      if (method === "POST" && pathname === "/api/action/tap") {
        const body = await readJsonObject(request);
        const params: Record<string, unknown> = {
          x: readNumber(body, "x"),
          y: readNumber(body, "y"),
        };
        return executeWithFollowUp(stub, "action/tap", params, url);
      }

      if (method === "POST" && pathname === "/api/action/tap-a11y") {
        const body = await readJsonObject(request);
        const path = typeof body["path"] === "string" ? body["path"] : undefined;
        const text = typeof body["text"] === "string" ? body["text"] : undefined;
        if (!path && !text) {
          throw new HttpError(400, "path or text parameter is required");
        }
        const params: Record<string, unknown> = {};
        if (path) params.path = path;
        if (text) params.text = text;
        return executeWithFollowUp(stub, "action/tap-a11y", params, url);
      }

      if (method === "POST" && pathname === "/api/action/swipe") {
        const body = await readJsonObject(request);
        const params: Record<string, unknown> = {
          startX: readNumber(body, "startX"),
          startY: readNumber(body, "startY"),
          endX: readNumber(body, "endX"),
          endY: readNumber(body, "endY"),
          duration: readNumber(body, "duration"),
        };
        return executeWithFollowUp(stub, "action/swipe", params, url);
      }

      if (method === "POST" && pathname === "/api/action/back") {
        return executeWithFollowUp(stub, "action/back", undefined, url);
      }

      if (method === "POST" && pathname === "/api/action/home") {
        return executeWithFollowUp(stub, "action/home", undefined, url);
      }

      if (method === "POST" && pathname === "/api/action/recent") {
        return executeWithFollowUp(stub, "action/recent", undefined, url);
      }

      if (method === "POST" && pathname === "/api/action/type") {
        const body = await readJsonObject(request);
        const params: Record<string, unknown> = {
          text: readString(body, "text"),
        };
        return executeWithFollowUp(stub, "action/type", params, url);
      }

      if (method === "POST" && pathname === "/api/action/key") {
        const body = await readJsonObject(request);
        const params: Record<string, unknown> = {
          keyCode: readNumber(body, "keyCode"),
        };
        return executeWithFollowUp(stub, "action/key", params, url);
      }

      if (method === "POST" && pathname === "/api/action/clear-input") {
        return executeWithFollowUp(stub, "action/clear-input", undefined, url);
      }

      if (method === "POST" && pathname === "/api/app/install") {
        const body = await readJsonObject(request);
        const params: CommandRequest["appInstall"] = {
          url: readString(body, "url"),
        };
        return commandJsonResponse(await stub.sendCommand("app/install", params));
      }

      if (method === "GET" && pathname === "/api/app/list") {
        return commandJsonResponse(await stub.sendCommand("app/list"));
      }

      if (method === "POST" && pathname === "/api/app/launch") {
        const body = await readJsonObject(request);
        const params: Record<string, unknown> = {
          packageName: readString(body, "packageName"),
        };
        return executeWithFollowUp(stub, "app/launch", params, url);
      }

      if (method === "POST" && pathname === "/api/app/stop") {
        const body = await readJsonObject(request);
        const params: CommandRequest["appStop"] = {
          packageName: readString(body, "packageName"),
        };
        return commandJsonResponse(await stub.sendCommand("app/stop", params));
      }

      if (method === "POST" && pathname === "/api/app/uninstall") {
        const body = await readJsonObject(request);
        const params: CommandRequest["appUninstall"] = {
          packageName: readString(body, "packageName"),
        };
        return commandJsonResponse(await stub.sendCommand("app/uninstall", params));
      }

      return jsonResponse({ error: "Not Found" }, 404);
    } catch (error) {
      const mapped = mapErrorToStatus(error);
      return jsonResponse({ error: mapped.message }, mapped.status);
    }
  },
};
