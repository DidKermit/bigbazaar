# BigBazaar architecture diagrams

`BigBazaar-Diagrams.pdf` is the full set, built from SRS v1.1 and the code on `main`:

1. System architecture and deployment
2. Component diagrams (Identity service, Angular web app)
3. Data-flow diagrams (context, level 1, event flow)
4. Database schema: one ERD per database with the main columns, then every table and every column
5. Request flows (sequence diagrams) for login, checkout, seller verification, shipping, returns,
   settlement, reviews and the outbox
6. Choices and alternatives, open items, glossary, and the Mermaid source of every diagram

## Files

| Path | What it is |
|---|---|
| `mermaid/*.mmd` | Diagram sources. GitHub renders them inside a ```` ```mermaid ```` fence; mermaid.live edits them. |
| `mermaid/20-erd-*.mmd` | **Generated** from `tools/schema.py`. Edit the schema, not these files. |
| `eraser/system-design.eraser` | The system design as Eraser diagram-as-code. Paste into eraser.io: new diagram, Diagram as code, Cloud architecture. |
| `tools/schema.py` | Every table and column of the ten service databases, marked built or planned. |
| `tools/content.py` | The explanations printed around each diagram. |
| `tools/build.py` | Generates the ERDs, renders every diagram and prints the PDF. |

## Rebuilding the PDF

Needs Python 3, Node 18+ and a Chromium.

```bash
mkdir -p /tmp/mmdc && (cd /tmp/mmdc && PUPPETEER_SKIP_DOWNLOAD=1 npm i @mermaid-js/mermaid-cli@11.4.2)
MERMAID_CLI_DIR=/tmp/mmdc CHROME_PATH=/path/to/chrome python3 docs/diagrams/tools/build.py
```

ER diagrams are laid out with ELK, which mermaid-cli 11 includes; the other diagrams use the default
layout. When a planned service is built, update `tools/schema.py` to match its Flyway migrations and
rebuild.
