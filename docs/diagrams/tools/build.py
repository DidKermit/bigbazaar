"""Builds docs/diagrams/BigBazaar-Diagrams.pdf.

Steps:
  1. Generate the ERD Mermaid files (mermaid/20-erd-*.mmd) from schema.py.
  2. Render every mermaid/*.mmd to SVG with mermaid-cli.
  3. Assemble one HTML document: explanations, diagrams, full column tables, Mermaid source appendix.
  4. Print it to PDF with headless Chromium.

Needs Node with @mermaid-js/mermaid-cli installed in a folder given by MERMAID_CLI_DIR (that folder's
node_modules also provides puppeteer), and a Chromium given by CHROME_PATH. See ../README.md.
"""

import html
import os
import re
import subprocess
import sys
from datetime import date
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from schema import DATABASES, SHARED, SEARCH_INDEX  # noqa: E402
from content import SECTIONS, INTRO, CONCEPTS, GLOSSARY, CHOICES, OPEN_ITEMS  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
MERMAID = ROOT / "mermaid"
BUILD = Path(os.environ.get("BUILD_DIR", ROOT / "build"))
CLI_DIR = Path(os.environ["MERMAID_CLI_DIR"])
CHROME = os.environ["CHROME_PATH"]
PDF = ROOT / "BigBazaar-Diagrams.pdf"

SERVICE_OF = {
    "identity": "identity_db", "catalog": "catalog_db", "commerce": "commerce_db",
    "payment": "payment_db", "fulfillment": "fulfillment_db",
}


# ---------------------------------------------------------------------------------------------
# 1. ERD generation
# ---------------------------------------------------------------------------------------------

def erd_type(t):
    return re.sub(r"\(.*\)", "", t)


def erd_for(tables, title_note=None):
    lines = ["erDiagram"]
    for t in tables:
        lines.append(f"  {t.name} {{")
        for c in t.columns:
            if not c.main:
                continue
            keys = []
            if c.pk:
                keys.append("PK")
            if c.fk:
                keys.append("FK")
            if c.uk and not c.pk:
                keys.append("UK")
            key = (" " + ", ".join(keys)) if keys else ""
            comment = ' "logical id"' if c.ref else ""
            lines.append(f"    {erd_type(c.type)} {c.name}{key}{comment}")
        lines.append("  }")
    for t in tables:
        for c in t.columns:
            if not c.fk:
                continue
            parent = c.fk.split(".")[0]
            # One-to-one when the FK alone is unique: a plain unique key, or the whole primary key.
            pk_cols = [o for o in t.columns if o.pk]
            one_to_one = (c.uk and "unique with" not in c.note) or (c.pk and len(pk_cols) == 1)
            left = "|o" if c.nullable else "||"
            right = "o|" if one_to_one else "o{"
            lines.append(f'  {parent} {left}--{right} {t.name} : "{c.name}"')
    return "\n".join(lines) + "\n"


def overview():
    """Which database stores logical ids owned by which other database."""
    edges = {}
    for db in DATABASES:
        for t in db.tables:
            for c in t.columns:
                if c.ref:
                    owner = SERVICE_OF[c.ref.split(".")[0]]
                    if owner == db.name:
                        continue
                    edges.setdefault((db.name, owner), set()).add(c.ref.split(".")[1])
    lines = ["flowchart LR"]
    for db in DATABASES:
        lines.append(f'  {db.name}[("{db.name}<br/><small>{db.service}</small>")]')
    for (src, dst), refs in sorted(edges.items()):
        label = ", ".join(sorted(refs))
        lines.append(f'  {src} -. "{label}" .-> {dst}')
    lines.append("  classDef built fill:#d8f3dc,stroke:#2d6a4f")
    lines.append("  class identity_db,platform_db built")
    return "\n".join(lines) + "\n"


def write_erds():
    for old in MERMAID.glob("20-erd-*.mmd"):
        old.unlink()
    files = []
    p = MERMAID / "20-erd-00-overview.mmd"
    p.write_text(overview())
    files.append(p)
    for i, db in enumerate(DATABASES, start=1):
        p = MERMAID / f"20-erd-{i:02d}-{db.name.replace('_', '-')}.mmd"
        p.write_text(erd_for(db.tables))
        files.append(p)
    p = MERMAID / "20-erd-11-search-index.mmd"
    p.write_text(erd_for([SEARCH_INDEX]).replace("products (OpenSearch index)", "products_index"))
    files.append(p)
    return files


# ---------------------------------------------------------------------------------------------
# 2. Rendering
# ---------------------------------------------------------------------------------------------

MERMAID_CONFIG = """{
  "theme": "default",
  "themeVariables": {"fontFamily": "Inter, Helvetica, Arial, sans-serif", "fontSize": "14px"},
  "flowchart": {"curve": "basis", "htmlLabels": true, "padding": 12},
  "sequence": {"mirrorActors": false, "wrap": false, "width": 150, "messageFontSize": 14, "noteFontSize": 13, "actorFontSize": 14, "messageMargin": 30},
  "er": {"layoutDirection": "TB", "minEntityWidth": 120, "fontSize": 12}
}"""


def render_all():
    BUILD.mkdir(parents=True, exist_ok=True)
    (BUILD / "mermaid-config.json").write_text(MERMAID_CONFIG)
    # ELK lays out wide ER diagrams far more compactly than dagre; flowcharts read better with dagre.
    (BUILD / "mermaid-config-elk.json").write_text(MERMAID_CONFIG.replace('"theme"', '"layout": "elk", "theme"', 1))
    (BUILD / "puppeteer.json").write_text(
        '{"executablePath": "%s", "args": ["--no-sandbox"]}' % CHROME)
    mmdc = CLI_DIR / "node_modules" / ".bin" / "mmdc"
    svgs = {}
    for src in sorted(MERMAID.glob("*.mmd")):
        out = BUILD / (src.stem + ".svg")
        if out.exists() and out.stat().st_mtime > src.stat().st_mtime:
            svgs[src.stem] = out.read_text()
            continue
        config = "mermaid-config-elk.json" if src.read_text().startswith("erDiagram") else "mermaid-config.json"
        subprocess.run([str(mmdc), "-q", "-i", str(src), "-o", str(out), "-c",
                        str(BUILD / config), "-p", str(BUILD / "puppeteer.json"),
                        "-b", "white"], check=True)
        svgs[src.stem] = out.read_text()
        print("rendered", src.name)
    return svgs


# ---------------------------------------------------------------------------------------------
# 3. HTML
# ---------------------------------------------------------------------------------------------

def esc(s):
    return html.escape(s, quote=False)


def md(s):
    """Tiny inline markdown: `code` and **bold**."""
    s = esc(s)
    s = re.sub(r"`([^`]+)`", r"<code>\1</code>", s)
    s = re.sub(r"\*\*([^*]+)\*\*", r"<b>\1</b>", s)
    return s


def paras(text):
    out = []
    for block in text.strip().split("\n\n"):
        block = block.strip()
        if block.startswith("- "):
            items = [md(" ".join(x.split())) for x in ("\n" + block).split("\n- ")[1:]]
            out.append("<ul>" + "".join(f"<li>{i}</li>" for i in items) + "</ul>")
        else:
            out.append(f"<p>{md(' '.join(block.split()))}</p>")
    return "\n".join(out)


def prepare_svg(svg, stem):
    """Make an SVG scale to its box, with ids unique to this diagram (mermaid-cli names every SVG my-svg,
    and each SVG's CSS is scoped by that id, so two inlined diagrams would share styles)."""
    svg = re.sub(r'<\?xml[^>]*\?>', '', svg)
    uid = "d-" + stem
    svg = svg.replace("my-svg", uid)
    svg = re.sub(r'<svg([^>]*?) width="100%"', r'<svg\1', svg, count=1)
    svg = re.sub(r'(<svg[^>]*?)style="max-width: [0-9.]+px;', r'\1preserveAspectRatio="xMidYMid meet" style="', svg, count=1)
    return svg


def aspect(svg):
    m = re.search(r'viewBox="([-0-9.]+) ([-0-9.]+) ([0-9.]+) ([0-9.]+)"', svg)
    return float(m.group(3)) / float(m.group(4))


def svg_block(svgs, stem):
    return (f'<div class="diagram">{prepare_svg(svgs[stem], stem)}</div>'
            f'<div class="src">Source: <code>mermaid/{stem}.mmd</code></div>')


BADGE = {"built": "Built", "planned": "Planned", "added": "Planned, not in SRS 8.4"}


def column_table(t):
    rows = []
    for c in t.columns:
        keys = []
        if c.pk:
            keys.append("PK")
        if c.uk:
            keys.append("UK")
        if c.fk:
            keys.append("FK → " + c.fk)
        if c.ref:
            keys.append("ref → " + c.ref)
        rows.append(
            "<tr>"
            f"<td class='{'main' if c.main else ''}'><code>{esc(c.name)}</code></td>"
            f"<td><code>{esc(c.type)}</code></td>"
            f"<td>{'yes' if c.nullable else ''}</td>"
            f"<td>{esc(', '.join(keys))}</td>"
            f"<td>{'●' if c.main else ''}</td>"
            f"<td>{md(c.note)}</td>"
            "</tr>")
    notes = "".join(f"<p class='tnote'>{md(n)}</p>" for n in t.notes)
    return (f"<div class='tbl'><h4><code>{esc(t.name)}</code> <span class='badge {t.status}'>{BADGE[t.status]}</span></h4>"
            f"<p class='purpose'>{md(t.purpose)}</p>"
            "<table class='cols'><thead><tr><th>Column</th><th>Type</th><th>Null</th><th>Key</th><th>ERD</th><th>Notes</th></tr></thead>"
            f"<tbody>{''.join(rows)}</tbody></table>{notes}</div>")


def diagram_section(svgs, sec, number):
    """One page per diagram, with the text either above it or on its left, whichever lets the diagram
    be drawn larger. The areas are the free space in mm on an A4 landscape page in each layout."""
    a = aspect(svgs[sec["file"]])
    side_scale = min(185 / a, 160)
    wide_scale = min(270 / a, 130)
    wide = wide_scale > side_scale
    text = [f"<p class='srs'>From SRS v1.1: {esc(sec['srs'])}</p>", paras(sec["read"])]
    if sec.get("concepts"):
        text.append("<div class='concept'><b>Key ideas</b>" + paras(sec["concepts"]) + "</div>")
    return (f"<section class='dpage {'wide' if wide else 'side'}'>"
            f"<h2 id='{sec['id']}'>{number} {esc(sec['title'])}</h2>"
            f"<div class='dbody'><div class='dtext'>{''.join(text)}</div>"
            f"<div class='dfig'>{svg_block(svgs, sec['file'])}</div></div></section>")


def build_html(svgs):
    toc = []
    body = []

    body.append(f"""
<section class="cover">
  <h1>BigBazaar<br/>Architecture Diagrams</h1>
  <p class="sub">System architecture, components, data flow, database schema and request flows</p>
  <p class="meta">Based on SRS v1.1 and the Phase 1 code on <code>main</code> · {date.today().isoformat()}</p>
</section>""")

    body.append("<section><h2 id='intro'>How to read this document</h2>" + paras(INTRO) + "</section>")
    body.append("<section><h2 id='concepts'>Five ideas behind every diagram</h2>" + paras(CONCEPTS) + "</section>")

    n = 0
    for group in SECTIONS:
        n += 1
        toc.append((f"{n}", group["title"], group["id"]))
        body.append(f"<section class='part'><h1 id='{group['id']}'>{n}. {esc(group['title'])}</h1>{paras(group['intro'])}</section>")
        for i, sec in enumerate(group["diagrams"], start=1):
            if sec["file"] == "@columns":
                body.append(columns_section(f"{n}.{i}", sec))
            else:
                body.append(diagram_section(svgs, sec, f"{n}.{i}"))
            toc.append((f"{n}.{i}", sec["title"], sec["id"]))

    n += 1
    toc.append((f"{n}", "Choices made and alternatives", "choices"))
    rows = "".join(f"<tr><td>{md(a)}</td><td>{md(b)}</td><td>{md(c)}</td><td>{md(d)}</td></tr>" for a, b, c, d in CHOICES)
    body.append(f"<section><h1 id='choices'>{n}. Choices made and alternatives</h1>"
                "<table class='grid'><thead><tr><th>Decision</th><th>Chosen</th><th>Alternatives</th><th>Why</th></tr></thead>"
                f"<tbody>{rows}</tbody></table></section>")

    n += 1
    toc.append((f"{n}", "Open items found while drawing", "open"))
    body.append(f"<section><h1 id='open'>{n}. Open items found while drawing</h1>" + paras(OPEN_ITEMS) + "</section>")

    n += 1
    toc.append((f"{n}", "Glossary", "glossary"))
    rows = "".join(f"<tr><td><b>{esc(a)}</b></td><td>{md(b)}</td></tr>" for a, b in GLOSSARY)
    body.append(f"<section><h1 id='glossary'>{n}. Glossary</h1><table class='grid'><tbody>{rows}</tbody></table></section>")

    n += 1
    toc.append((f"{n}", "Appendix: Mermaid source", "appendix"))
    srcs = []
    for src in sorted(MERMAID.glob("*.mmd")):
        srcs.append(f"<div class='mmd'><h4><code>mermaid/{src.name}</code></h4><pre>{esc(src.read_text())}</pre></div>")
    body.append(f"<section class='part'><h1 id='appendix'>{n}. Appendix: Mermaid source</h1>"
                "<p>Every diagram above is plain text. Paste any block into <code>mermaid.live</code> or a GitHub "
                "Markdown file inside a <code>```mermaid</code> fence to see and edit it. The ERD files are "
                "generated from <code>tools/schema.py</code>, so edit the schema there, not the .mmd.</p>"
                + "<div class='mmds'>" + "".join(srcs) + "</div></section>")

    toc_html = "<section class='toc'><h2>Contents</h2><ol>" + "".join(
        f"<li class='{'top' if '.' not in num else 'sub'}'><a href='#{i}'><span>{num}</span> {esc(t)}</a></li>"
        for num, t, i in toc) + "</ol></section>"
    body.insert(1, toc_html)

    return f"<!doctype html><html><head><meta charset='utf-8'><title>BigBazaar Architecture Diagrams</title><style>{CSS}</style></head><body>{''.join(body)}</body></html>"


def columns_section(number, sec):
    parts = [f"<section class='columns'><h2 id='{sec['id']}'>{number} {esc(sec['title'])}</h2>", paras(sec["read"])]
    parts.append("<h3>Shared tables (in every service that publishes or consumes events)</h3>")
    parts.append("<div class='twocol'>" + "".join(column_table(t) for t in SHARED) + "</div>")
    for db in DATABASES:
        shared = ", ".join(f"<code>{s}</code>" for s in db.shared)
        parts.append(f"<h3 class='dbh'>{db.name} <span class='svc'>· {esc(db.service)} service</span></h3>"
                     f"<p>{md(db.summary)} Also has the shared {shared} table{'s' if len(db.shared) > 1 else ''}.</p>")
        parts.append("<div class='twocol'>" + "".join(column_table(t) for t in db.tables) + "</div>")
    parts.append("<h3 class='dbh'>OpenSearch <span class='svc'>· Search service</span></h3>")
    parts.append(column_table(SEARCH_INDEX))
    parts.append("</section>")
    return "\n".join(parts)


CSS = """
@page { size: A4 landscape; margin: 14mm 14mm 16mm 14mm; }
* { box-sizing: border-box; }
body { font-family: Inter, Helvetica, Arial, sans-serif; font-size: 10.5pt; color: #1d2433; line-height: 1.45; margin: 0; }
h1 { font-size: 22pt; color: #12355b; margin: 0 0 8pt; }
h2 { font-size: 15pt; color: #12355b; margin: 0 0 6pt; border-bottom: 2px solid #dbe4f0; padding-bottom: 3pt; }
h3 { font-size: 12.5pt; color: #12355b; margin: 14pt 0 4pt; }
h4 { font-size: 10.5pt; margin: 10pt 0 2pt; }
p { margin: 3pt 0 5pt; }
ul { margin: 3pt 0 6pt 16pt; padding: 0; }
code { font-family: "JetBrains Mono", Menlo, Consolas, monospace; font-size: 9pt; background: #f1f4f9; padding: 0 2pt; border-radius: 2pt; }
section { page-break-before: always; }
section.cover { page-break-before: auto; height: 170mm; display: flex; flex-direction: column; justify-content: center; padding-left: 20mm; border-left: 10mm solid #12355b; }
.cover h1 { font-size: 40pt; line-height: 1.1; }
.cover .sub { font-size: 15pt; color: #3d4b63; }
.cover .meta { color: #6b7790; margin-top: 18pt; }
.toc ol { list-style: none; padding: 0; columns: 2; column-gap: 14mm; }
.toc li { margin: 1.5pt 0; break-inside: avoid; }
.toc li.top { font-weight: 600; margin-top: 7pt; }
.toc li.sub { padding-left: 14pt; }
.toc a { color: inherit; text-decoration: none; }
.toc span { display: inline-block; min-width: 26pt; color: #6b7790; }
.part h1 { margin-top: 0; }
.srs { color: #6b7790; font-size: 9pt; margin-top: -2pt; }
.dpage { height: 180mm; display: flex; flex-direction: column; overflow: hidden; }
.dbody { flex: 1; min-height: 0; display: flex; gap: 7mm; }
.side .dbody { flex-direction: row; }
.side .dtext { width: 78mm; flex: none; font-size: 9.8pt; }
.wide .dbody { flex-direction: column; gap: 2mm; }
.wide .dtext { columns: 2; column-gap: 8mm; font-size: 9.8pt; flex: none; }
.wide .dtext .concept { break-inside: avoid; margin-top: 0; }
.dfig { flex: 1; min-width: 0; min-height: 0; display: flex; flex-direction: column; }
.diagram { flex: 1; min-height: 0; }
.diagram svg { width: 100%; height: 100%; display: block; }
.src { font-size: 7.5pt; color: #6b7790; text-align: right; flex: none; }
.concept { background: #f4f8fc; border-left: 3pt solid #4a7fb5; padding: 5pt 9pt; margin-top: 6pt; page-break-inside: avoid; }
.concept p, .concept ul { margin: 2pt 0; }
table { border-collapse: collapse; width: 100%; }
table.grid td, table.grid th { border: 1px solid #dbe4f0; padding: 4pt 6pt; vertical-align: top; text-align: left; }
table.grid th { background: #eef3f9; }
.twocol { columns: 2; column-gap: 7mm; }
.tbl { break-inside: avoid; page-break-inside: avoid; margin-bottom: 6pt; }
.tbl h4 { margin-top: 0; }
.purpose { color: #3d4b63; margin: 0 0 3pt; font-size: 8.6pt; }
table.cols { font-size: 7.6pt; }
table.cols code { font-size: 7.4pt; }
table.cols th { background: #eef3f9; text-align: left; padding: 2pt 5pt; border: 1px solid #dbe4f0; }
table.cols td { padding: 1.5pt 5pt; border: 1px solid #e6ecf4; vertical-align: top; }
table.cols td:nth-child(1) { width: 24%; }
table.cols td:nth-child(2) { width: 16%; }
table.cols td:nth-child(3) { width: 5%; }
table.cols td:nth-child(4) { width: 20%; }
table.cols td:nth-child(5) { width: 5%; text-align: center; color: #4a7fb5; }
table.cols td { word-break: break-word; }
table.cols td:nth-child(2), table.cols td:nth-child(1) { white-space: nowrap; }
table.cols td.main code { font-weight: 700; }
.tnote { font-size: 8.8pt; color: #3d4b63; margin: 2pt 0 0; }
.badge { font-size: 7.5pt; font-weight: 600; padding: 1pt 5pt; border-radius: 6pt; vertical-align: middle; font-family: Inter, Helvetica, Arial, sans-serif; }
.badge.built { background: #d8f3dc; color: #1b4332; }
.badge.planned { background: #e7ecf5; color: #344767; }
.badge.added { background: #fff1d6; color: #7a4b00; }
.dbh { page-break-before: always; }
.svc { color: #6b7790; font-weight: 400; font-size: 11pt; }
.mmds { columns: 2; column-gap: 7mm; }
.mmd { break-inside: avoid; page-break-inside: avoid; }
.mmd pre { font-family: "JetBrains Mono", Menlo, Consolas, monospace; font-size: 6.6pt; background: #f6f8fb; border: 1px solid #e3e9f2; padding: 5pt 7pt; white-space: pre-wrap; margin: 0 0 6pt; }
"""


# ---------------------------------------------------------------------------------------------
# 4. PDF
# ---------------------------------------------------------------------------------------------

PRINT_JS = r"""
const puppeteer = require(process.argv[2] + '/node_modules/puppeteer');
(async () => {
  const browser = await puppeteer.launch({ executablePath: process.argv[3], args: ['--no-sandbox'] });
  const page = await browser.newPage();
  await page.goto('file://' + process.argv[4], { waitUntil: 'networkidle0' });
  await page.pdf({
    path: process.argv[5], format: 'A4', landscape: true, printBackground: true,
    displayHeaderFooter: true,
    headerTemplate: '<span></span>',
    footerTemplate: '<div style="font-size:7pt;color:#8a94a8;width:100%;padding:0 14mm;display:flex;justify-content:space-between;font-family:Helvetica"><span>BigBazaar architecture diagrams · SRS v1.1</span><span><span class="pageNumber"></span> / <span class="totalPages"></span></span></div>',
    margin: { top: '12mm', bottom: '14mm', left: '12mm', right: '12mm' },
  });
  await browser.close();
})();
"""


def main():
    write_erds()
    svgs = render_all()
    out = BUILD / "diagrams.html"
    out.write_text(build_html(svgs))
    js = BUILD / "print.js"
    js.write_text(PRINT_JS)
    subprocess.run(["node", str(js), str(CLI_DIR), CHROME, str(out), str(PDF)], check=True)
    print("wrote", PDF)


if __name__ == "__main__":
    main()
