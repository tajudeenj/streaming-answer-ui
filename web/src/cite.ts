export type Part = { kind: "text"; text: string } | { kind: "cite"; ref: string };

const MARKER = /\[(S\d{1,2})\]/g;

/**
 * Splits answer text into plain text and citation markers such as [S1], so citations render as
 * React elements. Only refs the server actually sent become citations; anything else stays literal text.
 */
export function splitCitations(text: string, known: ReadonlySet<string>): Part[] {
  const parts: Part[] = [];
  let last = 0;
  for (const m of text.matchAll(MARKER)) {
    const ref = m[1]!;
    if (!known.has(ref)) continue;
    const at = m.index ?? 0;
    if (at > last) parts.push({ kind: "text", text: text.slice(last, at) });
    parts.push({ kind: "cite", ref });
    last = at + m[0].length;
  }
  if (last < text.length) parts.push({ kind: "text", text: text.slice(last) });
  return parts;
}

export function formatDate(iso: string): string {
  const d = new Date(`${iso}T00:00:00Z`);
  return Number.isNaN(d.getTime())
    ? iso
    : d.toLocaleDateString("en-GB", { day: "numeric", month: "short", year: "numeric", timeZone: "UTC" });
}
