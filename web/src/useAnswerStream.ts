import { useCallback, useEffect, useRef, useState } from "react";
import { AnswerError, streamAnswer, type HistoryTurn, type SourceRef } from "./sse";

export type TurnStatus = "waiting" | "streaming" | "done" | "stopped" | "error";

export interface Turn {
  id: string;
  question: string;
  answer: string;
  status: TurnStatus;
  error?: string;
  requestId?: string;
  redactions: number;
  sources: SourceRef[];
  abstained: boolean;
  model?: string;
  firstTokenMs?: number;
  totalMs?: number;
}

const MAX_HISTORY_TURNS = 6;

/**
 * Owns the conversation and one in-flight stream. Deltas are buffered and flushed once per
 * animation frame, so a fast stream causes at most ~60 renders per second regardless of token rate.
 */
export function useAnswerStream() {
  const [turns, setTurns] = useState<Turn[]>([]);
  const controller = useRef<AbortController | null>(null);
  const pending = useRef("");
  const frame = useRef<number | null>(null);
  const turnsRef = useRef<Turn[]>([]);
  turnsRef.current = turns;

  const patch = useCallback((id: string, fn: (t: Turn) => Turn) => {
    setTurns((all) => all.map((t) => (t.id === id ? fn(t) : t)));
  }, []);

  const flush = useCallback((id: string) => {
    frame.current = null;
    const text = pending.current;
    if (!text) return;
    pending.current = "";
    patch(id, (t) => ({ ...t, answer: t.answer + text, status: "streaming" }));
  }, [patch]);

  const cancelFrame = () => {
    if (frame.current !== null) cancelAnimationFrame(frame.current);
    frame.current = null;
  };

  const ask = useCallback(async (question: string) => {
    const q = question.trim();
    if (!q) return;
    controller.current?.abort();

    const history: HistoryTurn[] = turnsRef.current
      .filter((t) => t.status === "done" && !t.abstained)
      .slice(-MAX_HISTORY_TURNS)
      .flatMap((t) => [
        { role: "user" as const, content: t.question },
        { role: "assistant" as const, content: t.answer },
      ]);

    const id = crypto.randomUUID();
    const ac = new AbortController();
    controller.current = ac;
    pending.current = "";
    cancelFrame();
    const started = performance.now();
    let firstSeen = false;

    setTurns((all) => [...all, { id, question: q, answer: "", status: "waiting", redactions: 0, sources: [], abstained: false }]);

    try {
      await streamAnswer(q, history, {
        onMeta: (m) => patch(id, (t) => ({ ...t, requestId: m.requestId, redactions: m.redactions, sources: m.sources, model: m.model })),
        onDelta: (text) => {
          if (!firstSeen) {
            firstSeen = true;
            const ms = Math.round(performance.now() - started);
            patch(id, (t) => ({ ...t, firstTokenMs: ms }));
          }
          pending.current += text;
          if (frame.current === null) frame.current = requestAnimationFrame(() => flush(id));
        },
        onDone: (d) => {
          cancelFrame();
          flush(id);
          const ms = Math.round(performance.now() - started);
          patch(id, (t) => ({ ...t, status: "done", totalMs: ms, abstained: d.abstained }));
        },
      }, ac.signal);
    } catch (e) {
      cancelFrame();
      flush(id);
      const ms = Math.round(performance.now() - started);
      if (ac.signal.aborted) {
        patch(id, (t) => ({ ...t, status: "stopped", totalMs: ms }));
      } else {
        const err = e instanceof AnswerError ? e : new AnswerError("Something went wrong. Ask again to retry.");
        patch(id, (t) => ({ ...t, status: "error", error: err.message, requestId: t.requestId ?? err.requestId, totalMs: ms }));
      }
    } finally {
      if (controller.current === ac) controller.current = null;
    }
  }, [flush, patch]);

  const stop = useCallback(() => controller.current?.abort(), []);

  useEffect(() => () => {
    controller.current?.abort();
    cancelFrame();
  }, []);

  const busy = turns.some((t) => t.status === "waiting" || t.status === "streaming");
  return { turns, ask, stop, busy };
}
