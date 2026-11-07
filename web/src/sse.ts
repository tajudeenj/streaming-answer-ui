export interface SseEvent {
  event: string;
  data: string;
}

/**
 * Incremental Server-Sent Events parser. Handles frames split across network chunks,
 * CRLF line endings, multi-line data fields and comment (heartbeat) lines.
 */
export function createSseParser(onEvent: (e: SseEvent) => void): (chunk: string) => void {
  let buffer = "";
  return (chunk: string) => {
    buffer += chunk.replace(/\r\n?/g, "\n");
    let sep: number;
    while ((sep = buffer.indexOf("\n\n")) !== -1) {
      const frame = buffer.slice(0, sep);
      buffer = buffer.slice(sep + 2);
      let event = "message";
      const data: string[] = [];
      for (const line of frame.split("\n")) {
        if (line === "" || line.startsWith(":")) continue;
        const colon = line.indexOf(":");
        const field = colon === -1 ? line : line.slice(0, colon);
        let value = colon === -1 ? "" : line.slice(colon + 1);
        if (value.startsWith(" ")) value = value.slice(1);
        if (field === "event") event = value;
        else if (field === "data") data.push(value);
      }
      if (data.length > 0) onEvent({ event, data: data.join("\n") });
    }
    if (buffer.length > 1_000_000) throw new Error("Stream frame too large");
  };
}

export interface HistoryTurn {
  role: "user" | "assistant";
  content: string;
}

export interface SourceRef {
  ref: string;
  docId: string;
  title: string;
  version: string;
  effectiveFrom: string;
  section: string;
  hash: string;
}

export interface StreamMeta {
  requestId: string;
  model: string;
  redactions: number;
  audience: string;
  grounded: boolean;
  sources: SourceRef[];
}

export interface StreamDone {
  fragments: number;
  elapsedMs: number;
  abstained: boolean;
}

export interface StreamCallbacks {
  onMeta(meta: StreamMeta): void;
  onDelta(text: string): void;
  onDone(summary: StreamDone): void;
}

export class AnswerError extends Error {
  constructor(message: string, readonly status?: number, readonly requestId?: string) {
    super(message);
    this.name = "AnswerError";
  }
}

const NETWORK_MESSAGE = "Could not reach the server. Check your connection and ask again.";

export async function streamAnswer(
  question: string,
  history: HistoryTurn[],
  cb: StreamCallbacks,
  signal: AbortSignal,
): Promise<void> {
  let res: Response;
  try {
    res = await fetch("/api/answer/stream", {
      method: "POST",
      headers: { "Content-Type": "application/json", Accept: "text/event-stream" },
      body: JSON.stringify({ question, history }),
      signal,
    });
  } catch (e) {
    if (signal.aborted) throw e;
    throw new AnswerError(NETWORK_MESSAGE);
  }

  const requestId = res.headers.get("X-Request-Id") ?? undefined;
  if (!res.ok || !res.body) {
    let message = `The server returned an error (${res.status}).`;
    try {
      const body = (await res.json()) as { message?: unknown };
      if (typeof body.message === "string") message = body.message;
    } catch {
      /* non-JSON error body */
    }
    throw new AnswerError(message, res.status, requestId);
  }

  let finished = false;
  let failure = null as AnswerError | null;
  const parse = createSseParser(({ event, data }) => {
    const payload = JSON.parse(data) as Record<string, unknown>;
    switch (event) {
      case "meta":
        cb.onMeta({
          ...(payload as unknown as StreamMeta),
          sources: Array.isArray(payload.sources) ? (payload.sources as SourceRef[]) : [],
        });
        break;
      case "delta":
        if (typeof payload.t === "string") cb.onDelta(payload.t);
        break;
      case "done":
        finished = true;
        cb.onDone({ ...(payload as unknown as StreamDone), abstained: payload.abstained === true });
        break;
      case "error":
        finished = true;
        failure = new AnswerError(String(payload.message ?? "The answer stopped unexpectedly."), undefined, requestId);
        break;
    }
  });

  const reader = res.body.pipeThrough(new TextDecoderStream()).getReader();
  try {
    for (;;) {
      const { value, done } = await reader.read();
      if (done) break;
      parse(value);
    }
  } catch (e) {
    if (signal.aborted) throw e;
    throw new AnswerError("The connection dropped before the answer finished. Ask again to retry.", undefined, requestId);
  } finally {
    reader.releaseLock();
  }
  if (failure) throw failure;
  if (!finished) throw new AnswerError("The answer ended early. Ask again to retry.", undefined, requestId);
}
