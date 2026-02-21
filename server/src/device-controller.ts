import type { DeviceCommand, DeviceResponse, PendingRequest } from "./types";
import { DurableObject } from "cloudflare:workers";

type CommandResult = DeviceResponse | ArrayBuffer;

export class DeviceController extends DurableObject {
  private readonly pendingRequests = new Map<string, PendingRequest>();
  private readonly textDecoder = new TextDecoder();

  private getActiveSocket(): WebSocket | null {
    const sockets = this.ctx.getWebSockets();
    return sockets.length > 0 ? sockets[0] : null;
  }

  private resolvePending(requestId: string, value: CommandResult): void {
    const pending = this.pendingRequests.get(requestId);
    if (!pending) {
      return;
    }
    clearTimeout(pending.timer);
    this.pendingRequests.delete(requestId);
    pending.resolve(value);
  }

  private rejectAllPending(reason: Error): void {
    for (const pending of this.pendingRequests.values()) {
      clearTimeout(pending.timer);
      pending.reject(reason);
    }
    this.pendingRequests.clear();
  }

  async sendCommand(
    command: string,
    params?: Record<string, unknown>,
  ): Promise<CommandResult> {
    const socket = this.getActiveSocket();
    if (!socket) {
      throw new Error("Device not connected");
    }

    const requestId = crypto.randomUUID();
    const payload: DeviceCommand = { requestId, command };
    if (params) {
      payload.params = params;
    }

    return new Promise<CommandResult>((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pendingRequests.delete(requestId);
        reject(new Error(`Command timed out: ${command}`));
      }, 30_000);

      this.pendingRequests.set(requestId, { resolve, reject, timer });

      try {
        socket.send(JSON.stringify(payload));
      } catch (error) {
        clearTimeout(timer);
        this.pendingRequests.delete(requestId);
        reject(error instanceof Error ? error : new Error("Failed to send command"));
      }
    });
  }

  async getStatus(): Promise<{ connected: boolean }> {
    return { connected: this.ctx.getWebSockets().length > 0 };
  }

  async fetch(request: Request): Promise<Response> {
    const upgradeHeader = request.headers.get("Upgrade");
    if (!upgradeHeader || upgradeHeader.toLowerCase() !== "websocket") {
      return new Response("Expected WebSocket upgrade", { status: 426 });
    }

    const existingSockets = this.ctx.getWebSockets();
    if (existingSockets.length > 0) {
      for (const socket of existingSockets) {
        socket.close(1012, "Replaced by new connection");
      }
      this.rejectAllPending(new Error("WebSocket replaced by new connection"));
    }

    const pair = new WebSocketPair();
    const clientSocket = pair[0];
    const serverSocket = pair[1];

    this.ctx.acceptWebSocket(serverSocket);

    return new Response(null, {
      status: 101,
      webSocket: clientSocket,
    });
  }

  async webSocketMessage(_ws: WebSocket, message: ArrayBuffer | string): Promise<void> {
    if (typeof message === "string") {
      let parsed: unknown;
      try {
        parsed = JSON.parse(message);
      } catch {
        return;
      }

      if (
        typeof parsed === "object" &&
        parsed !== null &&
        "requestId" in parsed &&
        typeof (parsed as { requestId: unknown }).requestId === "string"
      ) {
        this.resolvePending(
          (parsed as { requestId: string }).requestId,
          parsed as DeviceResponse,
        );
      }
      return;
    }

    if (message.byteLength <= 36) {
      return;
    }

    const requestId = this.textDecoder.decode(message.slice(0, 36));
    const pngData = message.slice(36);
    this.resolvePending(requestId, pngData);
  }

  async webSocketClose(
    _ws: WebSocket,
    _code: number,
    _reason: string,
    _wasClean: boolean,
  ): Promise<void> {
    this.rejectAllPending(new Error("WebSocket closed"));
  }

  async webSocketError(_ws: WebSocket, error: unknown): Promise<void> {
    this.rejectAllPending(
      error instanceof Error ? error : new Error("WebSocket error"),
    );
  }
}
