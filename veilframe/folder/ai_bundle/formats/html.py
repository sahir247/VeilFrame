"""
veilframe.folder.ai_bundle.formats.html — Interactive standalone HTML bundle renderer.
Produces a self-contained, responsive dark-themed project viewer with syntax styling.
"""

from __future__ import annotations

import html
from typing import List, Tuple

from veilframe.folder.ai_bundle.bundle_config import BundleConfig
from veilframe.folder.ai_bundle.formats.aibundle import aggregate_excluded_summary, render_security_section
from veilframe.folder.ai_bundle.manifest_renderer import render_manifest_summaries
from veilframe.folder.ai_bundle.project_summary import generate_project_metadata, render_project_summary_text
from veilframe.folder.ai_bundle.relationship_renderer import render_project_relationships
from veilframe.folder.ai_bundle.tree_renderer import build_project_tree
from veilframe.folder.models.file_record import FileRecord, format_bytes
from veilframe.folder.models.scan_result import ScanResult


def render_html_bundle(
    scan_result: ScanResult,
    config: BundleConfig,
    included_files: List[Tuple[FileRecord, str, int]],
    excluded_files: List[Tuple[FileRecord, str]],
    total_tokens: int,
) -> str:
    """Render an interactive, single-page HTML report and context bundle."""
    meta = generate_project_metadata(scan_result)
    proj_name = html.escape(meta['name'] or 'Project')

    tree_text = build_project_tree([f for f, _, _ in included_files], excluded_files=excluded_files)
    esc_tree = html.escape(tree_text)

    # File Cards HTML
    file_cards: List[str] = []
    for i, (f, content, tokens) in enumerate(included_files):
        fid = f"F{i + 1:03d}"
        esc_path = html.escape(f.relative_path)
        esc_lang = html.escape(f.language or "text")
        esc_cat = html.escape(f.effective_category.value)
        esc_content = html.escape(content.strip())
        size_str = format_bytes(f.size)

        badge_class = "badge-entry" if f.is_entry_point else "badge-source"

        card = f"""
        <article class="file-card" id="{fid}">
            <div class="file-header">
                <div class="file-title">
                    <span class="file-id">{fid}</span>
                    <span class="file-path">{esc_path}</span>
                    <span class="badge {badge_class}">{esc_cat}</span>
                    <span class="badge badge-lang">{esc_lang}</span>
                </div>
                <div class="file-meta">
                    <span>{size_str}</span>
                    <span>~{tokens:,} tokens</span>
                    <button class="copy-btn" onclick="copyCode('{fid}-code')">Copy</button>
                </div>
            </div>
            <pre class="file-code"><code id="{fid}-code">{esc_content}</code></pre>
        </article>
        """
        file_cards.append(card)

    # Excluded summary
    ex_summary = aggregate_excluded_summary(excluded_files)
    ex_rows = "".join(f"<tr><td>{html.escape(line)}</td></tr>" for line in ex_summary)

    # Security summary
    sec_summary = render_security_section(scan_result, included_files, excluded_files)
    sec_rows = "".join(f"<tr><td>{html.escape(line)}</td></tr>" for line in sec_summary)

    html_doc = f"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>{proj_name} — VeilFrame AI Project Bundle</title>
<style>
  :root {{
    --bg-dark: #0f172a;
    --bg-card: #1e293b;
    --bg-code: #090d16;
    --text-primary: #f8fafc;
    --text-secondary: #94a3b8;
    --accent: #38bdf8;
    --accent-focus: #0284c7;
    --border: #334155;
    --badge-bg: #334155;
    --badge-entry: #059669;
  }}
  * {{ box-sizing: border-box; margin: 0; padding: 0; }}
  body {{
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
    background-color: var(--bg-dark);
    color: var(--text-primary);
    line-height: 1.5;
    padding: 24px;
  }}
  .container {{ max-width: 1200px; margin: 0 auto; }}
  header {{
    border-bottom: 1px solid var(--border);
    padding-bottom: 20px;
    margin-bottom: 24px;
    display: flex;
    justify-content: space-between;
    align-items: center;
  }}
  h1 {{ font-size: 24px; color: var(--accent); }}
  .tagline {{ color: var(--text-secondary); font-size: 13px; }}
  .stats-grid {{
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
    gap: 16px;
    margin-bottom: 24px;
  }}
  .stat-box {{
    background: var(--bg-card);
    border: 1px solid var(--border);
    border-radius: 8px;
    padding: 16px;
  }}
  .stat-label {{ font-size: 11px; text-transform: uppercase; color: var(--text-secondary); }}
  .stat-value {{ font-size: 20px; font-weight: bold; color: var(--text-primary); margin-top: 4px; }}
  details {{
    background: var(--bg-card);
    border: 1px solid var(--border);
    border-radius: 8px;
    margin-bottom: 16px;
    padding: 12px 16px;
  }}
  summary {{ font-weight: bold; cursor: pointer; color: var(--accent); outline: none; }}
  pre {{
    font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
    font-size: 12px;
    overflow-x: auto;
    background: var(--bg-code);
    padding: 12px;
    border-radius: 6px;
    margin-top: 10px;
  }}
  .file-card {{
    background: var(--bg-card);
    border: 1px solid var(--border);
    border-radius: 8px;
    margin-bottom: 20px;
    overflow: hidden;
  }}
  .file-header {{
    display: flex;
    justify-content: space-between;
    align-items: center;
    padding: 10px 16px;
    background: rgba(255, 255, 255, 0.03);
    border-bottom: 1px solid var(--border);
  }}
  .file-title {{ display: flex; align-items: center; gap: 10px; font-size: 13px; }}
  .file-id {{ font-weight: bold; color: var(--accent); }}
  .file-path {{ font-family: monospace; font-weight: 600; }}
  .badge {{
    font-size: 10px;
    padding: 2px 6px;
    border-radius: 4px;
    background: var(--badge-bg);
    text-transform: uppercase;
  }}
  .badge-entry {{ background: var(--badge-entry); }}
  .file-meta {{ display: flex; align-items: center; gap: 12px; font-size: 12px; color: var(--text-secondary); }}
  .copy-btn {{
    background: var(--accent);
    color: #000;
    border: none;
    border-radius: 4px;
    padding: 4px 10px;
    font-size: 11px;
    font-weight: bold;
    cursor: pointer;
  }}
  .copy-btn:hover {{ background: var(--accent-focus); color: #fff; }}
  table {{ width: 100%; border-collapse: collapse; margin-top: 10px; font-size: 12px; }}
  td, th {{ border: 1px solid var(--border); padding: 8px 12px; text-align: left; }}
</style>
</head>
<body>
<div class="container">
  <header>
    <div>
      <h1>{proj_name}</h1>
      <div class="tagline">VeilFrame AI Project Bundle v1 &bull; Ready for LLM Context</div>
    </div>
    <div>
      <span class="badge badge-entry">Native UTF-8</span>
    </div>
  </header>

  <section class="stats-grid">
    <div class="stat-box">
      <div class="stat-label">Estimated Tokens</div>
      <div class="stat-value">~{total_tokens:,}</div>
    </div>
    <div class="stat-box">
      <div class="stat-label">Included Files</div>
      <div class="stat-value">{len(included_files):,}</div>
    </div>
    <div class="stat-box">
      <div class="stat-label">Excluded Files</div>
      <div class="stat-value">{len(excluded_files):,}</div>
    </div>
    <div class="stat-box">
      <div class="stat-label">Primary Languages</div>
      <div class="stat-value">{', '.join(meta['languages'][:2]) if meta['languages'] else 'None'}</div>
    </div>
  </section>

  <details open>
    <summary>Project Directory Tree</summary>
    <pre>{esc_tree}</pre>
  </details>

  <details>
    <summary>Excluded Content Summary ({len(excluded_files):,} files)</summary>
    <table>
      {ex_rows}
    </table>
  </details>

  <details>
    <summary>Security &amp; Privacy Safeguards</summary>
    <table>
      {sec_rows}
    </table>
  </details>

  <section style="margin-top: 24px;">
    <h2 style="font-size: 16px; margin-bottom: 12px; color: var(--accent);">Project Source Files</h2>
    {''.join(file_cards)}
  </section>
</div>

<script>
function copyCode(id) {{
  const el = document.getElementById(id);
  if (!el) return;
  navigator.clipboard.writeText(el.innerText).then(() => {{
    alert("Copied code to clipboard!");
  }}).catch(err => {{
    console.error("Copy failed", err);
  }});
}}
</script>
</body>
</html>
"""
    return html_doc
