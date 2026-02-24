export interface DeviceCommand {
  requestId: string;
  command: string;
  params?: Record<string, unknown>;
}

export interface DeviceResponse {
  requestId: string;
  success: boolean;
  data?: unknown;
  error?: string;
}

export interface CommandRequest {
  tap: {
    x: number;
    y: number;
  };
  tapA11y: {
    path: string;
  };
  swipe: {
    startX: number;
    startY: number;
    endX: number;
    endY: number;
    duration: number;
  };
  type: {
    text: string;
  };
  key: {
    keyCode: number;
  };
  appInstall: {
    url: string;
  };
  appLaunch: {
    packageName: string;
  };
  appStop: {
    packageName: string;
  };
  appUninstall: {
    packageName: string;
  };
}

export interface PendingRequest {
  resolve: (value: DeviceResponse | ArrayBuffer) => void;
  reject: (reason?: unknown) => void;
  timer: ReturnType<typeof setTimeout>;
}

export type DeviceControllerStub = DurableObjectStub & {
  getStatus(): Promise<{ connected: boolean }>;
  sendCommand(
    command: string,
    params?: Record<string, unknown>,
  ): Promise<DeviceResponse | ArrayBuffer>;
};
