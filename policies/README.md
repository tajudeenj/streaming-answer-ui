# Sample policy library

Illustrative documents for demonstrating grounded answers. They describe general Islamic-finance
practice for a fictional bank and are **not** the terms of any real institution.

Each file starts with front matter that the gateway uses for access control and versioning:

| Field | Meaning |
|---|---|
| `id` | Stable document id; several versions may share it |
| `version` | Document version shown in citations |
| `effective_from` / `effective_to` | Validity window (ISO dates, `effective_to` exclusive and optional) |
| `audience` | Comma-separated: `customer`, `staff` |

This README is ignored by the loader. Files whose name starts with `_` are ignored as well.

Two files exist to demonstrate controls:

- `murabaha-terms-v1.md` has expired and must never be cited.
- `service-charges.md` contains a hidden instruction and is quarantined at load time.
