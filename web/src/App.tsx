import { useEffect, useRef, useState, type FormEvent, type KeyboardEvent } from "react";
import { useAnswerStream, type Turn } from "./useAnswerStream";
import { formatDate, splitCitations } from "./cite";

const MAX_CHARS = 4000;

const STARTERS = [
  "How is Murabaha different from interest?",
  "Can I get a rebate if I settle my Murabaha early?",
  "Does rent stop if my Ijarah home is destroyed?",
];

export function App() {
  const { turns, ask, stop, busy } = useAnswerStream();
  const [draft, setDraft] = useState("");
  const endRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLTextAreaElement>(null);

  const last = turns[turns.length - 1];
  useEffect(() => {
    endRef.current?.scrollIntoView({ block: "end", behavior: "smooth" });
  }, [turns.length, last?.answer.length]);

  const submit = (e?: FormEvent) => {
    e?.preventDefault();
    if (busy || !draft.trim()) return;
    void ask(draft);
    setDraft("");
  };

  const onKey = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === "Enter" && !e.shiftKey && !e.nativeEvent.isComposing) submit();
  };

  return (
    <div className="shell">
      <header className="masthead">
        <h1>Policy desk</h1>
        <p>Answers from the bank’s current product policies, with the source for every statement.</p>
      </header>

      <main className="thread" aria-label="Conversation">
        {turns.length === 0 ? (
          <section className="empty">
            <h2>Ask about a product</h2>
            <p>
              Answers come only from current policy documents you are allowed to see. If the documents don’t cover
              your question, you’ll be told so instead of getting a guess. Account numbers, Emirates IDs, cards, emails
              and phone numbers are masked before your question leaves this server.
            </p>
            <div className="starters">
              {STARTERS.map((s) => (
                <button key={s} type="button" className="starter" onClick={() => void ask(s)}>
                  {s}
                </button>
              ))}
            </div>
          </section>
        ) : (
          turns.map((t) => <TurnView key={t.id} turn={t} onRetry={() => void ask(t.question)} canRetry={!busy} />)
        )}
        <div ref={endRef} />
      </main>

      <form className="composer" onSubmit={submit}>
        <label htmlFor="question" className="visually-hidden">Your question</label>
        <textarea
          id="question"
          ref={inputRef}
          value={draft}
          maxLength={MAX_CHARS}
          rows={2}
          placeholder="Ask about Murabaha, Ijarah, Takaful…"
          onChange={(e) => setDraft(e.target.value)}
          onKeyDown={onKey}
        />
        {busy ? (
          <button type="button" className="action stop" onClick={stop}>Stop</button>
        ) : (
          <button type="submit" className="action" disabled={!draft.trim()}>Ask</button>
        )}
      </form>
    </div>
  );
}

function TurnView({ turn, onRetry, canRetry }: { turn: Turn; onRetry: () => void; canRetry: boolean }) {
  const live = turn.status === "waiting" || turn.status === "streaming";
  const paragraphs = turn.answer.split(/\n{2,}/);
  const known = new Set(turn.sources.map((s) => s.ref));
  const anchor = (ref: string) => `${turn.id}-${ref}`;

  return (
    <article className="turn" aria-busy={live}>
      <h3 className="question">{turn.question}</h3>

      <div className={turn.abstained ? "answer abstained" : "answer"} aria-live="polite">
        {turn.status === "waiting" && <p className="pending">Checking the policy library</p>}
        {turn.answer &&
          paragraphs.map((p, i) => (
            <p key={i}>
              {splitCitations(p, known).map((part, j) =>
                part.kind === "text" ? (
                  <span key={j}>{part.text}</span>
                ) : (
                  <a key={j} className="cite" href={`#${anchor(part.ref)}`} aria-label={`Source ${part.ref.slice(1)}`}>
                    {part.ref.slice(1)}
                  </a>
                ),
              )}
              {live && i === paragraphs.length - 1 && <span className="caret" aria-hidden="true" />}
            </p>
          ))}
      </div>

      {turn.sources.length > 0 && (
        <ol className="sources" aria-label="Sources">
          {turn.sources.map((s) => (
            <li key={s.ref} id={anchor(s.ref)}>
              <span className="src-num">{s.ref.slice(1)}</span>
              <span className="src-body">
                <span className="src-title">{s.title}, {s.section}</span>
                <span className="src-meta">
                  Version {s.version}, effective {formatDate(s.effectiveFrom)}
                </span>
              </span>
            </li>
          ))}
        </ol>
      )}

      {turn.status === "error" && (
        <div className="notice" role="alert">
          <p>{turn.error}</p>
          {turn.requestId && <p className="ref">Reference {turn.requestId}</p>}
          <button type="button" className="link" onClick={onRetry} disabled={!canRetry}>Ask again</button>
        </div>
      )}
      {turn.status === "stopped" && (
        <div className="notice quiet">
          <p>You stopped this answer.</p>
          <button type="button" className="link" onClick={onRetry} disabled={!canRetry}>Ask again</button>
        </div>
      )}

      <Telemetry turn={turn} />
    </article>
  );
}

function Telemetry({ turn }: { turn: Turn }) {
  const words = turn.answer.trim() ? turn.answer.trim().split(/\s+/).length : 0;
  const streamMs = turn.totalMs !== undefined && turn.firstTokenMs !== undefined ? turn.totalMs - turn.firstTokenMs : undefined;
  const rate = streamMs && streamMs > 0 && words > 1 ? Math.round((words / streamMs) * 1000) : undefined;
  const firstShare =
    turn.firstTokenMs !== undefined && turn.totalMs ? Math.min(100, (turn.firstTokenMs / turn.totalMs) * 100) : undefined;

  if (turn.abstained) {
    return (
      <footer className="telemetry">
        <dl>
          <div><dt>Sources</dt><dd>None that cover this question</dd></div>
          {turn.redactions > 0 && (
            <div><dt>Masked</dt><dd>{turn.redactions} {turn.redactions === 1 ? "detail" : "details"}</dd></div>
          )}
        </dl>
      </footer>
    );
  }
  if (turn.firstTokenMs === undefined && turn.redactions === 0) return null;

  return (
    <footer className="telemetry">
      {turn.totalMs !== undefined && firstShare !== undefined && (
        <div className="rail" role="img" aria-label={`First words after ${turn.firstTokenMs} ms of ${turn.totalMs} ms total`}>
          <span className="rail-wait" style={{ width: `${firstShare}%` }} />
          <span className="rail-stream" />
        </div>
      )}
      <dl>
        {turn.firstTokenMs !== undefined && (
          <div><dt>First words</dt><dd>{turn.firstTokenMs} ms</dd></div>
        )}
        {rate !== undefined && (
          <div><dt>Pace</dt><dd>{rate} words/s</dd></div>
        )}
        {turn.redactions > 0 && (
          <div><dt>Masked</dt><dd>{turn.redactions} {turn.redactions === 1 ? "detail" : "details"}</dd></div>
        )}
      </dl>
    </footer>
  );
}
