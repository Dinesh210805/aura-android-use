/**
 * proxy-core — builds an MCP Server that transparently forwards requests to a
 * backend MCP Client (the single phone connection).
 *
 * Used twice:
 *   - daemon: one proxy Server per HTTP client session → phone client
 *   - stdio shim: one proxy Server on stdio → daemon client
 *
 * The proxy advertises exactly the backend's capabilities and instructions,
 * so downstream agents get the same tool context at handshake as a direct
 * connection would give them.
 */
import { Server } from "@modelcontextprotocol/sdk/server/index.js";
import {
  ListToolsRequestSchema,
  CallToolRequestSchema,
  ListPromptsRequestSchema,
  GetPromptRequestSchema,
  ListResourcesRequestSchema,
  ListResourceTemplatesRequestSchema,
  ReadResourceRequestSchema,
  CompleteRequestSchema,
  SetLevelRequestSchema,
  ListToolsResultSchema,
  CallToolResultSchema,
  ListPromptsResultSchema,
  GetPromptResultSchema,
  ListResourcesResultSchema,
  ListResourceTemplatesResultSchema,
  ReadResourceResultSchema,
  CompleteResultSchema,
  EmptyResultSchema,
} from "@modelcontextprotocol/sdk/types.js";

/** Device tools drive a real screen — give them a generous budget and let
 *  progress notifications keep the clock alive. */
const CALL_TOOL_TIMEOUT_MS = 120_000;
const DEFAULT_TIMEOUT_MS = 30_000;

/**
 * @param {object} options
 * @param {() => Promise<import("@modelcontextprotocol/sdk/client/index.js").Client>} options.getClient
 *   Resolves the live backend client (reconnecting if needed). Called per
 *   request so a phone reconnect is picked up transparently.
 * @param {(fn: () => Promise<any>) => Promise<any>} [options.runExclusive]
 *   Serializer for tool calls — there is one physical screen, so concurrent
 *   tool calls from different sessions must queue, not interleave.
 * @param {() => void} [options.onBackendAlive]
 *   Called after every successful backend round-trip — lets the connection
 *   manager treat real traffic as proof of liveness (fewer health pings).
 * @param {string} options.name
 * @param {string} options.version
 * @param {Array<{definition: object, handler: (args: object) => Promise<object>, instructions?: string}>} [options.localTools]
 *   Tools served entirely by THIS process — never forwarded to the phone.
 *   Merged into tools/list alongside the backend's tools; tools/call for one
 *   of these names short-circuits before touching the phone connection at
 *   all. Used for machine-local capabilities (e.g. `aura-adb`) that make no
 *   sense to relay over the WebRTC link. Pass only where the tool should
 *   actually execute (the daemon — see daemon.js); the stdio shim leaves this
 *   empty and transparently forwards such calls through to the daemon.
 *   An optional `instructions` string on a local tool is appended to the
 *   backend's handshake instructions, since the backend cannot describe a tool
 *   it never sees.
 */
export async function buildProxyServer({
  getClient,
  runExclusive = (fn) => fn(),
  onBackendAlive = () => {},
  name,
  version,
  localTools = [],
}) {
  const backend = await getClient();
  const backendCaps = backend.getServerCapabilities() ?? {};
  const localToolsByName = new Map(localTools.map((t) => [t.definition.name, t]));

  // Local tools are invisible to the phone, so the phone's instructions can't
  // mention them. Append their own guidance after it — a tool description is
  // only read once a model is already picking a tool, whereas instructions
  // shape strategy before the first call. Phone text stays first and unedited:
  // it owns the safety doctrine, and these sections defer to it.
  const instructions = [
    backend.getInstructions(),
    ...[...localToolsByName.values()].map((t) => t.instructions).filter(Boolean),
  ]
    .filter(Boolean)
    .join("\n\n")
    .trim() || undefined;

  // Only advertise what we actually forward (and what the phone declared).
  const capabilities = {};
  if (backendCaps.tools || localToolsByName.size > 0) {
    capabilities.tools = backendCaps.tools ?? { listChanged: false };
  }
  if (backendCaps.prompts) capabilities.prompts = backendCaps.prompts;
  if (backendCaps.resources) capabilities.resources = backendCaps.resources;
  if (backendCaps.logging) capabilities.logging = backendCaps.logging;
  if (backendCaps.completions) capabilities.completions = backendCaps.completions;

  const server = new Server({ name, version }, { capabilities, instructions });

  const forward = (requestSchema, resultSchema, options = {}) => {
    server.setRequestHandler(requestSchema, async (request) => {
      const call = async () => {
        const client = await getClient();
        return client.request(
          { method: request.method, params: request.params },
          resultSchema,
          {
            timeout: options.timeout ?? DEFAULT_TIMEOUT_MS,
            resetTimeoutOnProgress: true,
          },
        );
      };
      const run = () => (options.exclusive ? runExclusive(call) : call());
      let result;
      try {
        result = await run();
      } catch (e) {
        // "Connection closed" = the request never completed against a link
        // that is now provably dead; getClient() will reconnect. Retry once —
        // but never for tool calls: a lost RESPONSE still moved real pixels,
        // and replaying a tap is worse than surfacing the error.
        const closed = /connection closed|not open/i.test(e?.message ?? "");
        if (!closed || options.exclusive) throw e;
        result = await run();
      }
      onBackendAlive();
      return result;
    });
  };

  if (backendCaps.tools || localToolsByName.size > 0) {
    server.setRequestHandler(ListToolsRequestSchema, async (request) => {
      const backendResult = backendCaps.tools
        ? await (async () => {
            const client = await getClient();
            const result = await client.request(
              { method: request.method, params: request.params },
              ListToolsResultSchema,
              { timeout: DEFAULT_TIMEOUT_MS, resetTimeoutOnProgress: true },
            );
            onBackendAlive();
            return result;
          })()
        : { tools: [] };
      return {
        ...backendResult,
        tools: [
          ...backendResult.tools,
          ...[...localToolsByName.values()].map((t) => t.definition),
        ],
      };
    });

    server.setRequestHandler(CallToolRequestSchema, async (request) => {
      // Local tools never touch the phone connection — no need for the
      // exclusive queue, which exists only to serialize against the one
      // physical screen.
      const local = localToolsByName.get(request.params.name);
      if (local) return local.handler(request.params.arguments ?? {});

      const call = async () => {
        const client = await getClient();
        return client.request(
          { method: request.method, params: request.params },
          CallToolResultSchema,
          { timeout: CALL_TOOL_TIMEOUT_MS, resetTimeoutOnProgress: true },
        );
      };
      let result;
      try {
        result = await runExclusive(call);
      } catch (e) {
        const closed = /connection closed|not open/i.test(e?.message ?? "");
        if (!closed) throw e;
        result = await runExclusive(call);
      }
      onBackendAlive();
      return result;
    });
  }
  if (backendCaps.prompts) {
    forward(ListPromptsRequestSchema, ListPromptsResultSchema);
    forward(GetPromptRequestSchema, GetPromptResultSchema);
  }
  if (backendCaps.resources) {
    forward(ListResourcesRequestSchema, ListResourcesResultSchema);
    forward(ListResourceTemplatesRequestSchema, ListResourceTemplatesResultSchema);
    forward(ReadResourceRequestSchema, ReadResourceResultSchema);
  }
  if (backendCaps.completions) {
    forward(CompleteRequestSchema, CompleteResultSchema);
  }
  if (backendCaps.logging) {
    forward(SetLevelRequestSchema, EmptyResultSchema);
  }

  return server;
}

/**
 * Forward a notification from the backend to a set of live proxy servers.
 * Sessions that can't take it (closing, capability mismatch) are skipped —
 * a notification must never take a session down.
 */
export async function broadcastNotification(servers, notification) {
  for (const server of servers) {
    try {
      await server.notification(notification);
    } catch (e) {
      // Best-effort fan-out.
    }
  }
}
