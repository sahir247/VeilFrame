"""
veilframe.folder.ai_bundle.context_compressor — Schema extractors, data summarizers, and content redaction.
"""

from __future__ import annotations

import os
import sqlite3
from typing import Optional, Tuple

from veilframe.folder.ai_bundle.token_estimator import estimate_tokens
from veilframe.folder.models.classification import FileCategory
from veilframe.folder.models.file_record import FileRecord, format_bytes
from veilframe.folder.security.secret_detector import mask_secret
from veilframe.folder.security.secret_patterns import SECRET_PATTERNS


def summarize_sqlite_database(db_path: str) -> str:
    """Extract table schemas, index definitions, and row counts from an SQLite database."""
    lines = [f"SQLITE DATABASE: {os.path.basename(db_path)}"]
    try:
        size = os.path.getsize(db_path)
        lines.append(f"Size: {format_bytes(size)}")
        conn = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True)
        cursor = conn.cursor()

        # Tables
        tables = [
            row[0] for row in cursor.execute("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name;").fetchall()
            if not row[0].startswith("sqlite_")
        ]
        lines.append(f"Total Tables: {len(tables)}")

        for tbl in tables[:15]:
            count_row = cursor.execute(f"SELECT COUNT(*) FROM `{tbl}`;").fetchone()
            count = count_row[0] if count_row else 0
            lines.append(f"\n--- Table: {tbl} ({count:,} rows) ---")
            schema_row = cursor.execute(f"SELECT sql FROM sqlite_master WHERE type='table' AND name='{tbl}';").fetchone()
            if schema_row and schema_row[0]:
                lines.append(schema_row[0].strip() + ";")

        conn.close()
    except Exception as e:
        lines.append(f"Schema summary unavailable: {e}")

    return "\n".join(lines)


def summarize_binary_or_model(file_rec: FileRecord) -> str:
    """Generate concise descriptive metadata for models, datasets, or binary artifacts."""
    lines = [
        f"{file_rec.effective_category.value}: {file_rec.name}",
        f"Path: {file_rec.relative_path.replace(chr(92), '/')}",
        f"Size: {file_rec.format_size()}",
        f"MIME Type: {file_rec.mime_type or 'application/octet-stream'}",
        "CONTENT: Binary/model artifact omitted from token context (metadata only).",
    ]
    return "\n".join(lines)


import ast
from typing import List, Optional, Tuple


def generate_python_ast_outline(code: str) -> Optional[str]:
    """Generate structural outline of classes, methods, functions, and docstrings from Python source."""
    try:
        tree = ast.parse(code)
    except Exception:
        return None

    outline_lines: List[str] = ["# --- STRUCTURAL OUTLINE (EXTRACTED VIA AST) ---"]
    mod_doc = ast.get_docstring(tree)
    if mod_doc:
        first_line = mod_doc.strip().splitlines()[0]
        outline_lines.append(f'"""Module: {first_line}"""\n')

    for node in tree.body:
        if isinstance(node, (ast.Import, ast.ImportFrom)):
            try:
                outline_lines.append(ast.unparse(node))
            except Exception:
                pass
        elif isinstance(node, ast.ClassDef):
            bases = ", ".join(ast.unparse(b) for b in node.bases) if node.bases else ""
            header = f"class {node.name}({bases}):" if bases else f"class {node.name}:"
            outline_lines.append(f"\n{header}")
            doc = ast.get_docstring(node)
            if doc:
                outline_lines.append(f'    """{doc.strip().splitlines()[0]}"""')
            for item in node.body:
                if isinstance(item, (ast.FunctionDef, ast.AsyncFunctionDef)):
                    prefix = "async def " if isinstance(item, ast.AsyncFunctionDef) else "def "
                    try:
                        args = ast.unparse(item.args)
                    except Exception:
                        args = "..."
                    ret = f" -> {ast.unparse(item.returns)}" if item.returns else ""
                    outline_lines.append(f"    {prefix}{item.name}({args}){ret}: ...")
        elif isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
            prefix = "async def " if isinstance(item, ast.AsyncFunctionDef) else "def "
            try:
                args = ast.unparse(node.args)
            except Exception:
                args = "..."
            ret = f" -> {ast.unparse(node.returns)}" if node.returns else ""
            outline_lines.append(f"\n{prefix}{node.name}({args}){ret}:")
            doc = ast.get_docstring(node)
            if doc:
                outline_lines.append(f'    """{doc.strip().splitlines()[0]}"""')
            outline_lines.append("    ...")

    return "\n".join(outline_lines)


def prepare_file_content(
    file_rec: FileRecord,
    max_tokens: Optional[int] = None,
    redact_secrets: bool = True,
    truncate_oversized: bool = False,
) -> Tuple[str, int]:
    """
    Read and prepare file content for inclusion in an AI bundle.
    Guarantees complete, untruncated file content for all included files by default.
    Returns:
        (prepared_content: str, token_count: int)
    """
    # 1. Handle Databases
    if file_rec.effective_category == FileCategory.DATABASE and file_rec.extension in (".sqlite", ".sqlite3", ".db"):
        summary = summarize_sqlite_database(file_rec.path)
        tokens = estimate_tokens(summary, "sql")
        return summary, tokens

    # 2. Handle Models or Binaries
    if file_rec.effective_category in (FileCategory.MODEL, FileCategory.BINARY, FileCategory.MEDIA) or file_rec.is_binary:
        summary = summarize_binary_or_model(file_rec)
        tokens = estimate_tokens(summary, "text")
        return summary, tokens

    # 3. Read Text Content
    raw_content = ""
    try:
        with open(file_rec.path, "r", encoding="utf-8", errors="ignore") as f:
            raw_content = f.read()
    except OSError as e:
        msg = f"[Error reading file: {e}]"
        return msg, estimate_tokens(msg, "text")

    # 4. Redact Secrets if enabled
    prepared = raw_content
    if redact_secrets:
        for _, _, pattern in SECRET_PATTERNS:
            def _sub_repl(m):
                val = m.group(1) if m.groups() else m.group(0)
                return m.group(0).replace(val, mask_secret(val))
            prepared = pattern.sub(_sub_repl, prepared)

    # 5. Calculate precise token count; DO NOT truncate included files unless explicitly forced
    estimated = estimate_tokens(prepared, file_rec.language)
    if truncate_oversized and max_tokens is not None and estimated > max_tokens:
        char_limit = int(max_tokens * 3.5)
        # Check if AST structural outline can be extracted for Python
        is_python = (file_rec.language or "").lower() == "python" or file_rec.extension in (".py", ".pyw")
        ast_outline = generate_python_ast_outline(prepared) if is_python else None

        if ast_outline:
            head = prepared[: int(char_limit * 0.4)]
            tail = prepared[-int(char_limit * 0.2) :]
            truncation_notice = (
                f"\n\n... [TRUNCATED {estimated - max_tokens:,} TOKENS FOR CONTEXT BUDGET] ...\n"
                f"{ast_outline}\n"
                f"... [END STRUCTURAL OUTLINE] ...\n\n"
            )
            prepared = head + truncation_notice + tail
        else:
            head = prepared[: int(char_limit * 0.7)]
            tail = prepared[-int(char_limit * 0.3) :]
            truncation_notice = f"\n\n... [TRUNCATED {estimated - max_tokens:,} TOKENS FOR CONTEXT BUDGET] ...\n\n"
            prepared = head + truncation_notice + tail

        estimated = max_tokens

    return prepared, estimated
