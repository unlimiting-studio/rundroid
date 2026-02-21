import type { CommandRequest, DeviceResponse } from "./types";

export { DeviceController } from "./device-controller";

export interface Env {
  DEVICE_CONTROLLER: DurableObjectNamespace;
}

type DeviceControllerStub = DurableObjectStub & {
  getStatus(): Promise<{ connected: boolean }>;
  sendCommand(
    command: string,
    params?: Record<string, unknown>,
  ): Promise<DeviceResponse | ArrayBuffer>;
};

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

function commandJsonResponse(result: DeviceResponse | ArrayBuffer): Response {
  if (result instanceof ArrayBuffer) {
    return withCors(
      new Response(result, {
        status: 200,
        headers: { "content-type": "application/octet-stream" },
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

      if (method === "GET" && pathname === "/api/status") {
        const status = await stub.getStatus();
        return jsonResponse(status);
      }

      if (method === "POST" && pathname === "/api/screenshot") {
        const result = await stub.sendCommand("screenshot");
        if (result instanceof ArrayBuffer) {
          return withCors(
            new Response(result, {
              status: 200,
              headers: { "content-type": "image/png" },
            }),
          );
        }
        return commandJsonResponse(result);
      }

      if (method === "POST" && pathname === "/api/a11y-tree") {
        return commandJsonResponse(await stub.sendCommand("a11y-tree"));
      }

      if (method === "POST" && pathname === "/api/action/tap") {
        const body = await readJsonObject(request);
        const params: CommandRequest["tap"] = {
          x: readNumber(body, "x"),
          y: readNumber(body, "y"),
        };
        return commandJsonResponse(await stub.sendCommand("action/tap", params));
      }

      if (method === "POST" && pathname === "/api/action/tap-a11y") {
        const body = await readJsonObject(request);
        const path = typeof body["path"] === "string" ? body["path"] : undefined;
        const text = typeof body["text"] === "string" ? body["text"] : undefined;
        if (!path && !text) {
          throw new HttpError(400, "path or text parameter is required");
        }
        const params: Record<string, string> = {};
        if (path) params.path = path;
        if (text) params.text = text;
        return commandJsonResponse(await stub.sendCommand("action/tap-a11y", params));
      }

      if (method === "POST" && pathname === "/api/action/swipe") {
        const body = await readJsonObject(request);
        const params: CommandRequest["swipe"] = {
          startX: readNumber(body, "startX"),
          startY: readNumber(body, "startY"),
          endX: readNumber(body, "endX"),
          endY: readNumber(body, "endY"),
          duration: readNumber(body, "duration"),
        };
        return commandJsonResponse(await stub.sendCommand("action/swipe", params));
      }

      if (method === "POST" && pathname === "/api/action/back") {
        return commandJsonResponse(await stub.sendCommand("action/back"));
      }

      if (method === "POST" && pathname === "/api/action/home") {
        return commandJsonResponse(await stub.sendCommand("action/home"));
      }

      if (method === "POST" && pathname === "/api/action/recent") {
        return commandJsonResponse(await stub.sendCommand("action/recent"));
      }

      if (method === "POST" && pathname === "/api/action/type") {
        const body = await readJsonObject(request);
        const params: CommandRequest["type"] = {
          text: readString(body, "text"),
        };
        return commandJsonResponse(await stub.sendCommand("action/type", params));
      }

      if (method === "POST" && pathname === "/api/action/key") {
        const body = await readJsonObject(request);
        const params: CommandRequest["key"] = {
          keyCode: readNumber(body, "keyCode"),
        };
        return commandJsonResponse(await stub.sendCommand("action/key", params));
      }

      if (method === "POST" && pathname === "/api/action/clear-input") {
        return commandJsonResponse(await stub.sendCommand("action/clear-input"));
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
        const params: CommandRequest["appLaunch"] = {
          packageName: readString(body, "packageName"),
        };
        return commandJsonResponse(await stub.sendCommand("app/launch", params));
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
