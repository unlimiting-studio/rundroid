import type { DeviceControllerStub, DeviceResponse } from "./types";

export function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function arrayBufferToBase64(buffer: ArrayBuffer): string {
  const bytes = new Uint8Array(buffer);
  let binary = "";
  for (let i = 0; i < bytes.byteLength; i++) {
    binary += String.fromCharCode(bytes[i]);
  }
  return btoa(binary);
}

// --- Tool definitions ---

interface ToolDef {
  name: string;
  description: string;
  inputSchema: {
    type: "object";
    properties: Record<string, unknown>;
    required?: string[];
  };
}

const screenshotProp = {
  screenshot: { type: "boolean", description: "Capture screenshot after action" },
};
const a11yTreeProp = {
  a11y_tree: { type: "boolean", description: "Capture accessibility tree after action" },
};
const followUpProps = { ...screenshotProp, ...a11yTreeProp };

const TOOLS: ToolDef[] = [
  {
    name: "status",
    description: "Get device connection status",
    inputSchema: { type: "object", properties: {}, required: [] },
  },
  {
    name: "screenshot",
    description: "Capture a screenshot of the device screen",
    inputSchema: { type: "object", properties: {}, required: [] },
  },
  {
    name: "a11y_tree",
    description:
      "Get the accessibility tree of the current screen. Use flat=true for a simplified list of interactive elements with center coordinates.",
    inputSchema: {
      type: "object",
      properties: {
        flat: {
          type: "boolean",
          description: "Return flat list of interactive elements only (clickable/scrollable)",
        },
      },
    },
  },
  {
    name: "tap",
    description: "Tap at screen coordinates",
    inputSchema: {
      type: "object",
      properties: { x: { type: "number" }, y: { type: "number" }, ...followUpProps },
      required: ["x", "y"],
    },
  },
  {
    name: "tap_a11y",
    description: "Tap on a UI element by accessibility text or path",
    inputSchema: {
      type: "object",
      properties: {
        text: { type: "string", description: "Accessibility text to tap" },
        path: { type: "string", description: "Accessibility path to tap" },
        ...followUpProps,
      },
    },
  },
  {
    name: "swipe",
    description: "Swipe from one point to another",
    inputSchema: {
      type: "object",
      properties: {
        startX: { type: "number" },
        startY: { type: "number" },
        endX: { type: "number" },
        endY: { type: "number" },
        duration: { type: "number", description: "Duration in ms" },
        ...followUpProps,
      },
      required: ["startX", "startY", "endX", "endY", "duration"],
    },
  },
  {
    name: "back",
    description: "Press the back button",
    inputSchema: { type: "object", properties: { ...followUpProps } },
  },
  {
    name: "home",
    description: "Press the home button",
    inputSchema: { type: "object", properties: { ...followUpProps } },
  },
  {
    name: "recent",
    description: "Press the recent apps button",
    inputSchema: { type: "object", properties: { ...followUpProps } },
  },
  {
    name: "type",
    description: "Type text into the focused input field",
    inputSchema: {
      type: "object",
      properties: { text: { type: "string" }, ...followUpProps },
      required: ["text"],
    },
  },
  {
    name: "key",
    description: "Send a key event by keyCode",
    inputSchema: {
      type: "object",
      properties: { keyCode: { type: "number" }, ...followUpProps },
      required: ["keyCode"],
    },
  },
  {
    name: "clear_input",
    description: "Clear the focused input field",
    inputSchema: { type: "object", properties: { ...followUpProps } },
  },
  {
    name: "app_list",
    description: "List installed apps",
    inputSchema: { type: "object", properties: {}, required: [] },
  },
  {
    name: "app_launch",
    description: "Launch an app by package name",
    inputSchema: {
      type: "object",
      properties: { packageName: { type: "string" }, ...followUpProps },
      required: ["packageName"],
    },
  },
  {
    name: "app_stop",
    description: "Stop an app by package name",
    inputSchema: {
      type: "object",
      properties: { packageName: { type: "string" } },
      required: ["packageName"],
    },
  },
  {
    name: "app_install",
    description: "Install an APK from URL",
    inputSchema: {
      type: "object",
      properties: { url: { type: "string" } },
      required: ["url"],
    },
  },
  {
    name: "app_uninstall",
    description: "Uninstall an app by package name",
    inputSchema: {
      type: "object",
      properties: { packageName: { type: "string" } },
      required: ["packageName"],
    },
  },
];

// --- Content builders ---

interface TextContent {
  type: "text";
  text: string;
}

interface ImageContent {
  type: "image";
  data: string;
  mimeType: string;
}

type Content = TextContent | ImageContent;

function textContent(text: string): TextContent {
  return { type: "text", text };
}

function imageContent(base64: string): ImageContent {
  return { type: "image", data: base64, mimeType: "image/png" };
}

// --- a11y tree flattening ---

interface FlatNode {
  text: string | null;
  description: string | null;
  bounds: [number, number, number, number];
  center: [number, number];
  clickable: boolean;
  scrollable: boolean;
  checked: boolean;
  enabled: boolean;
  id?: string;
}

interface A11yTreeNode {
  text?: string;
  contentDescription?: string;
  viewIdResourceName?: string;
  bounds?: { left: number; top: number; right: number; bottom: number };
  isClickable?: boolean;
  isScrollable?: boolean;
  isChecked?: boolean;
  isEnabled?: boolean;
  children?: A11yTreeNode[];
}

function flattenA11yTree(node: A11yTreeNode, result: FlatNode[] = []): FlatNode[] {
  if (node.isClickable || node.isScrollable) {
    const b = node.bounds ?? { left: 0, top: 0, right: 0, bottom: 0 };
    result.push({
      text: node.text ?? null,
      description: node.contentDescription ?? null,
      bounds: [b.left, b.top, b.right, b.bottom],
      center: [Math.round((b.left + b.right) / 2), Math.round((b.top + b.bottom) / 2)],
      clickable: node.isClickable ?? false,
      scrollable: node.isScrollable ?? false,
      checked: node.isChecked ?? false,
      enabled: node.isEnabled ?? false,
      ...(node.viewIdResourceName ? { id: node.viewIdResourceName } : {}),
    });
  }
  if (node.children) {
    for (const child of node.children) {
      flattenA11yTree(child, result);
    }
  }
  return result;
}

// --- Tool execution ---

async function executeAction(
  stub: DeviceControllerStub,
  command: string,
  actionParams: Record<string, unknown> | undefined,
  args: Record<string, unknown>,
): Promise<{ content: Content[]; isError?: boolean }> {
  const wantScreenshot = args.screenshot === true;
  const wantA11y = args.a11y_tree === true;

  // Pass screenshot flag to device so red dot overlay is included
  const params = wantScreenshot ? { ...actionParams, screenshot: true } : actionParams;

  const result = await stub.sendCommand(command, params);

  if (result instanceof ArrayBuffer) {
    // Device returned PNG (screenshot with red dot overlay)
    const content: Content[] = [
      textContent(JSON.stringify({ success: true, data: { performed: true } })),
      imageContent(arrayBufferToBase64(result)),
    ];
    if (wantA11y) {
      const a11yResult = await stub.sendCommand("a11y-tree");
      if (!(a11yResult instanceof ArrayBuffer)) {
        content.push(textContent(JSON.stringify(a11yResult)));
      }
    }
    return { content };
  }

  // Device returned JSON
  const content: Content[] = [textContent(JSON.stringify(result))];
  if (!result.success) {
    return { content, isError: true };
  }

  if (!wantScreenshot && !wantA11y) {
    return { content };
  }

  // Only a11y_tree requested (no screenshot) — wait for UI to settle
  await sleep(500);
  if (wantA11y) {
    const a11yResult = await stub.sendCommand("a11y-tree");
    if (!(a11yResult instanceof ArrayBuffer)) {
      content.push(textContent(JSON.stringify(a11yResult)));
    }
  }

  return { content };
}

async function executeTool(
  name: string,
  args: Record<string, unknown>,
  stub: DeviceControllerStub,
): Promise<{ content: Content[]; isError?: boolean }> {
  try {
    switch (name) {
      case "status": {
        const status = await stub.getStatus();
        return { content: [textContent(JSON.stringify(status))] };
      }

      case "screenshot": {
        const result = await stub.sendCommand("screenshot");
        if (result instanceof ArrayBuffer) {
          return { content: [imageContent(arrayBufferToBase64(result))] };
        }
        return { content: [textContent(JSON.stringify(result))], isError: !result.success };
      }

      case "a11y_tree": {
        const result = await stub.sendCommand("a11y-tree");
        if (result instanceof ArrayBuffer) {
          return { content: [textContent("Unexpected binary response")], isError: true };
        }
        if (!result.success) {
          return { content: [textContent(JSON.stringify(result))], isError: true };
        }
        if (args.flat === true && result.data) {
          const flat = flattenA11yTree(result.data as A11yTreeNode);
          return { content: [textContent(JSON.stringify(flat))] };
        }
        return { content: [textContent(JSON.stringify(result))] };
      }

      case "tap":
        return executeAction(stub, "action/tap", { x: args.x, y: args.y }, args);

      case "tap_a11y": {
        const params: Record<string, unknown> = {};
        if (args.path) params.path = args.path;
        if (args.text) params.text = args.text;
        if (!params.path && !params.text) {
          return { content: [textContent("text or path parameter is required")], isError: true };
        }
        return executeAction(stub, "action/tap-a11y", params, args);
      }

      case "swipe":
        return executeAction(
          stub,
          "action/swipe",
          {
            startX: args.startX,
            startY: args.startY,
            endX: args.endX,
            endY: args.endY,
            duration: args.duration,
          },
          args,
        );

      case "back":
      case "home":
      case "recent":
      case "clear_input": {
        const cmd = name === "clear_input" ? "action/clear-input" : `action/${name}`;
        return executeAction(stub, cmd, undefined, args);
      }

      case "type":
        return executeAction(stub, "action/type", { text: args.text }, args);

      case "key":
        return executeAction(stub, "action/key", { keyCode: args.keyCode }, args);

      case "app_list": {
        const result = await stub.sendCommand("app/list");
        if (result instanceof ArrayBuffer) {
          return { content: [textContent("Unexpected binary response")], isError: true };
        }
        return { content: [textContent(JSON.stringify(result))], isError: !result.success };
      }

      case "app_launch":
        return executeAction(stub, "app/launch", { packageName: args.packageName }, args);

      case "app_stop": {
        const result = await stub.sendCommand("app/stop", { packageName: args.packageName });
        if (result instanceof ArrayBuffer) {
          return { content: [textContent("Unexpected binary response")], isError: true };
        }
        return { content: [textContent(JSON.stringify(result))], isError: !result.success };
      }

      case "app_install": {
        const result = await stub.sendCommand("app/install", { url: args.url });
        if (result instanceof ArrayBuffer) {
          return { content: [textContent("Unexpected binary response")], isError: true };
        }
        return { content: [textContent(JSON.stringify(result))], isError: !result.success };
      }

      case "app_uninstall": {
        const result = await stub.sendCommand("app/uninstall", { packageName: args.packageName });
        if (result instanceof ArrayBuffer) {
          return { content: [textContent("Unexpected binary response")], isError: true };
        }
        return { content: [textContent(JSON.stringify(result))], isError: !result.success };
      }

      default:
        return { content: [textContent(`Unknown tool: ${name}`)], isError: true };
    }
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    return { content: [textContent(`Error: ${message}`)], isError: true };
  }
}

// --- JSON-RPC handler ---

export interface JsonRpcRequest {
  jsonrpc: string;
  id?: number | string;
  method: string;
  params?: Record<string, unknown>;
}

interface JsonRpcResponse {
  jsonrpc: "2.0";
  id: number | string | null;
  result?: unknown;
  error?: { code: number; message: string };
}

export async function handleMcpRequest(
  body: JsonRpcRequest,
  stub: DeviceControllerStub,
): Promise<JsonRpcResponse> {
  const id = body.id ?? null;

  switch (body.method) {
    case "initialize":
      return {
        jsonrpc: "2.0",
        id,
        result: {
          protocolVersion: "2024-11-05",
          capabilities: { tools: {} },
          serverInfo: { name: "rundroid", version: "1.0.0" },
        },
      };

    case "notifications/initialized":
      return { jsonrpc: "2.0", id, result: {} };

    case "tools/list":
      return { jsonrpc: "2.0", id, result: { tools: TOOLS } };

    case "tools/call": {
      const params = body.params ?? {};
      const toolName = params.name as string;
      const toolArgs = (params.arguments as Record<string, unknown>) ?? {};

      if (!toolName || !TOOLS.find((t) => t.name === toolName)) {
        return {
          jsonrpc: "2.0",
          id,
          error: { code: -32602, message: `Unknown tool: ${toolName}` },
        };
      }

      const result = await executeTool(toolName, toolArgs, stub);
      return { jsonrpc: "2.0", id, result };
    }

    default:
      return {
        jsonrpc: "2.0",
        id,
        error: { code: -32601, message: `Method not found: ${body.method}` },
      };
  }
}
