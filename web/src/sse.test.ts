import { describe, expect, it } from "vitest";
import { createSseParser, type SseEvent } from "./sse";

function collect(chunks: string[]): SseEvent[] {
  const out: SseEvent[] = [];
  const parse = createSseParser((e) => out.push(e));
  chunks.forEach(parse);
  return out;
}

describe("createSseParser", () => {
  it("parses named events", () => {
    expect(collect(['event: delta\ndata: {"t":"Hi"}\n\n'])).toEqual([{ event: "delta", data: '{"t":"Hi"}' }]);
  });

  it("reassembles frames split across chunks", () => {
    expect(collect(["event: del", 'ta\ndata: {"t"', ':"a"}\n', "\n"])).toEqual([{ event: "delta", data: '{"t":"a"}' }]);
  });

  it("ignores heartbeat comments and handles CRLF", () => {
    expect(collect([": keep-alive\r\n\r\nevent: done\r\ndata: {}\r\n\r\n"])).toEqual([{ event: "done", data: "{}" }]);
  });

  it("joins multi-line data fields", () => {
    expect(collect(["data: a\ndata: b\n\n"])).toEqual([{ event: "message", data: "a\nb" }]);
  });
});
