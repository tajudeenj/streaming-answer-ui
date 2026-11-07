import { describe, expect, it } from "vitest";
import { splitCitations } from "./cite";

describe("splitCitations", () => {
  const known = new Set(["S1", "S2"]);

  it("turns known markers into citation parts", () => {
    expect(splitCitations("Rent stops. [S1] Takaful applies [S2]", known)).toEqual([
      { kind: "text", text: "Rent stops. " },
      { kind: "cite", ref: "S1" },
      { kind: "text", text: " Takaful applies " },
      { kind: "cite", ref: "S2" },
    ]);
  });

  it("leaves unknown markers and masked data as plain text", () => {
    expect(splitCitations("See [S9] and [IBAN]", known)).toEqual([{ kind: "text", text: "See [S9] and [IBAN]" }]);
  });
});
