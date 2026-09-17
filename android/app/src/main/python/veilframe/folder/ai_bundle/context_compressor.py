"""
veilframe.folder.ai_bundle.context_compressor — Schema extractors, data summarizers, and content redaction.
"""

from __future__ import annotations

import os
import re
import sqlite3
from typing import List, Optional, Tuple

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


def summarize_lockfile(name: str, content: str) -> str:
    """Summarize massive machine-generated lockfiles into concise dependency lists without hash dumps."""
    lines = [f"LOCKFILE DEPENDENCY SUMMARY: {name}"]
    packages: List[str] = []

    # TOML format (uv.lock, poetry.lock, Cargo.lock)
    if name.lower().endswith(".lock") and ("[[package]]" in content or "name =" in content):
        current_pkg = ""
        current_ver = ""
        for line in content.splitlines():
            line_s = line.strip()
            if line_s == "[[package]]":
                if current_pkg:
                    packages.append(f"{current_pkg}=={current_ver}" if current_ver else current_pkg)
                    current_pkg = ""
                    current_ver = ""
            elif line_s.startswith("name = "):
                current_pkg = line_s.split("=", 1)[1].strip().strip('"').strip("'")
            elif line_s.startswith("version = "):
                current_ver = line_s.split("=", 1)[1].strip().strip('"').strip("'")
        if current_pkg:
            packages.append(f"{current_pkg}=={current_ver}" if current_ver else current_pkg)

    # JSON format (package-lock.json, composer.lock)
    elif name.lower().endswith(".json"):
        try:
            import json as _json
            data = _json.loads(content)
            if "packages" in data and isinstance(data["packages"], dict):
                for p_name, p_info in data["packages"].items():
                    p_clean = p_name.replace("node_modules/", "")
                    if p_clean and isinstance(p_info, dict):
                        ver = p_info.get("version", "")
                        packages.append(f"{p_clean}@{ver}" if ver else p_clean)
            elif "dependencies" in data and isinstance(data["dependencies"], dict):
                for p_name, p_info in data["dependencies"].items():
                    ver = p_info.get("version", "") if isinstance(p_info, dict) else str(p_info)
                    packages.append(f"{p_name}@{ver}")
        except Exception:
            pass

    if packages:
        lines.append(f"Total Resolved Dependencies: {len(packages)}")
        lines.append("Packages:")
        for p in sorted(set(packages))[:150]:
            lines.append(f"  • {p}")
        if len(packages) > 150:
            lines.append(f"  ... [{len(packages) - 150} additional dependencies omitted for brevity]")
    else:
        filtered = [l for l in content.splitlines() if not any(h in l.lower() for h in ("sha", "integrity", "hash", "resolved", "url"))][:80]
        lines.extend(filtered)

    lines.append("\nNOTE: Cryptographic hashes, download URLs, and integrity signatures omitted to maximize AI context.")
    return "\n".join(lines)


import ast
from dataclasses import dataclass


@dataclass
class TextReadResult:
    """Detailed result of reading a text file with encoding identification and binary detection."""
    text: str
    encoding: str
    confidence: float = 1.0
    is_binary: bool = False
    decode_errors: Optional[str] = None

    def __iter__(self):
        """Allows unpacking as (text, encoding) for 100% backward compatibility."""
        yield self.text
        yield self.encoding

    def __getitem__(self, index: int):
        return (self.text, self.encoding)[index]


def read_text_file_safe(path: str, max_bytes: Optional[int] = None) -> TextReadResult:
    """
    Read text file with automatic encoding detection, confidence scoring, and multi-encoding fallback:
      1. Detect BOM signatures (UTF-8-SIG, UTF-16-LE, UTF-16-BE).
      2. Attempt strict UTF-8 decoding.
      3. Fall back to UTF-16 if interleaved with null bytes.
      4. Fall back to CP1252 for western legacy encodings.
      5. Evaluate Latin-1 safely: NEVER silently treat arbitrary binary data as source code.
      6. Detect binary/corrupted files and return a safe TextReadResult with is_binary=True.
    Returns:
      TextReadResult(text, encoding, confidence, is_binary, decode_errors)
    """
    try:
        with open(path, "rb") as f:
            raw_bytes = f.read(max_bytes) if max_bytes is not None else f.read()
    except OSError as e:
        return TextReadResult(
            text=f"[Error reading file: {e}]",
            encoding="error",
            confidence=0.0,
            is_binary=False,
            decode_errors=str(e),
        )

    if not raw_bytes:
        return TextReadResult(text="", encoding="empty", confidence=1.0, is_binary=False)

    # 1. Check BOM
    if raw_bytes.startswith(b"\xef\xbb\xbf"):
        return TextReadResult(
            text=raw_bytes[3:].decode("utf-8", errors="replace"),
            encoding="utf-8-sig",
            confidence=1.0,
            is_binary=False,
        )
    if raw_bytes.startswith(b"\xff\xfe"):
        return TextReadResult(
            text=raw_bytes[2:].decode("utf-16-le", errors="replace"),
            encoding="utf-16-le",
            confidence=1.0,
            is_binary=False,
        )
    if raw_bytes.startswith(b"\xfe\xff"):
        return TextReadResult(
            text=raw_bytes[2:].decode("utf-16-be", errors="replace"),
            encoding="utf-16-be",
            confidence=1.0,
            is_binary=False,
        )

    # 2. Check for UTF-16 without BOM or binary data with high null byte ratio
    sample = raw_bytes[:1024]
    if sample.count(b"\x00") > len(sample) * 0.3:
        try:
            decoded = raw_bytes.decode("utf-16")
            # In true UTF-16 text, decoded characters contain 0 null bytes
            if decoded.count("\x00") <= len(decoded) * 0.05:
                return TextReadResult(text=decoded, encoding="utf-16", confidence=0.95, is_binary=False)
        except UnicodeDecodeError:
            pass

        return TextReadResult(
            text="[Binary or corrupted file content omitted from token context]",
            encoding="binary",
            confidence=1.0,
            is_binary=True,
            decode_errors="High null byte frequency (>30%)",
        )

    # 3. Try UTF-8 strict
    try:
        decoded = raw_bytes.decode("utf-8")
        if "\x00" in decoded:
            return TextReadResult(
                text="[Binary or corrupted file content omitted from token context]",
                encoding="binary",
                confidence=0.95,
                is_binary=True,
                decode_errors="Embedded null bytes found in UTF-8 text",
            )
        return TextReadResult(text=decoded, encoding="utf-8", confidence=1.0, is_binary=False)
    except UnicodeDecodeError:
        pass

    # 4. Fall back to CP1252 (Windows western encoding)
    try:
        decoded = raw_bytes.decode("cp1252")
        control_chars = sum(1 for c in decoded if ord(c) < 32 and c not in "\r\n\t")
        if "\x00" not in decoded and (len(decoded) == 0 or control_chars <= len(decoded) * 0.15):
            return TextReadResult(text=decoded, encoding="cp1252", confidence=0.80, is_binary=False)
    except UnicodeDecodeError:
        pass

    # 5. Latin-1 / ISO-8859-1 check
    # Note: decode("latin-1") never fails in Python, so we must strictly inspect content to prevent
    # silently interpreting arbitrary binary files (like .exe or .png) as source code.
    decoded_latin1 = raw_bytes.decode("latin-1")
    control_chars = sum(1 for c in decoded_latin1 if ord(c) < 32 and c not in "\r\n\t")
    null_count = decoded_latin1.count("\x00")
    if null_count > 0 or (len(decoded_latin1) > 0 and control_chars > len(decoded_latin1) * 0.10):
        return TextReadResult(
            text="[Binary or corrupted file content omitted from token context]",
            encoding="latin-1",
            confidence=0.41,
            is_binary=True,
            decode_errors="Binary data detected under Latin-1 decode",
        )

    return TextReadResult(
        text=decoded_latin1,
        encoding="latin-1",
        confidence=0.65,
        is_binary=False,
    )


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


BRACE_OUTLINE_REGEX = re.compile(
    r"""(?m)^[ \t]*(?:"""
    r"""(?:package|namespace)\s+[^;\n]+[;]?|"""
    r"""(?:(?:export|pub(?:\([^)]+\))?|public|private|protected|static|abstract|final|sealed|open|data)\s+)*(?:class|interface|struct|enum|trait|type|impl)\s+[A-Za-z0-9_]+[^{;\n]*|"""
    r"""func\s+(?:\([^)]+\)\s+)?[A-Za-z0-9_]+\s*\([^)]*\)[^{;\n]*|"""
    r"""(?:pub(?:\([^)]+\))?\s+)?(?:async\s+)?fn\s+[A-Za-z0-9_]+(?:<[^>]+>)?\s*\([^)]*\)[^{;\n]*|"""
    r"""(?:export\s+)?(?:async\s+)?function\s+[A-Za-z0-9_]+\s*\([^)]*\)[^{;\n]*|"""
    r"""(?:(?:public|private|protected|static|final|override|fun)\s+)+[A-Za-z0-9_<>[\]?*&]+\s+[A-Za-z0-9_]+\s*\([^)]*\)[^{;\n]*"""
    r""")"""
)


def generate_brace_language_outline(code: str, language: str = "") -> Optional[str]:
    """Generate structural signatures (classes, functions, interfaces, structs) for brace-based languages."""
    if not code:
        return None
    matches = BRACE_OUTLINE_REGEX.findall(code)
    if not matches:
        return None

    lang_title = (language or "SOURCE").upper()
    lines = [f"// --- STRUCTURAL OUTLINE (EXTRACTED SIGNATURES FOR {lang_title}) ---"]
    for m in matches[:60]:
        lines.append(f"{m.strip()} {{ ... }}")
    if len(matches) > 60:
        lines.append(f"// ... [{len(matches) - 60} additional declarations omitted] ...")
    return "\n".join(lines)


def generate_structural_outline(code: str, language: Optional[str] = None, extension: str = "") -> Optional[str]:
    """Universal structural outliner routing between Python AST and brace/C-family signatures."""
    lang = (language or "").lower()
    ext = (extension or "").lower()

    if lang == "python" or ext in (".py", ".pyw", ".pyi"):
        return generate_python_ast_outline(code)

    brace_langs = {
        "javascript", "typescript", "go", "rust", "java", "kotlin",
        "c", "cpp", "csharp", "dart", "swift", "php", "scala",
    }
    brace_exts = {
        ".js", ".jsx", ".ts", ".tsx", ".go", ".rs", ".java", ".kt",
        ".c", ".cpp", ".cc", ".cxx", ".h", ".hpp", ".cs", ".dart", ".swift", ".php", ".scala",
    }

    if lang in brace_langs or ext in brace_exts:
        return generate_brace_language_outline(code, language=lang or ext.lstrip("."))

    return None


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

    # 3. Read Text Content with multi-encoding fallback and binary protection
    read_res = read_text_file_safe(file_rec.path)
    if read_res.is_binary:
        file_rec.is_binary = True
        summary = summarize_binary_or_model(file_rec)
        tokens = estimate_tokens(summary, "text")
        return summary, tokens

    raw_content = read_res.text
    if raw_content.startswith("[Error reading file:"):
        return raw_content, estimate_tokens(raw_content, "text")

    # 3b. Summarize massive lockfiles (dependencies and versions without hashes)
    base_lower = file_rec.name.lower()
    is_lockfile = (
        base_lower in ("uv.lock", "package-lock.json", "poetry.lock", "cargo.lock", "yarn.lock", "pnpm-lock.yaml", "composer.lock", "gemfile.lock")
        or base_lower.endswith(("-lock.json", "-lock.yaml"))
    )
    if is_lockfile and len(raw_content) > 3_000:
        summary = summarize_lockfile(file_rec.name, raw_content)
        tokens = estimate_tokens(summary, "text")
        return summary, tokens

    # 4. Redact Secrets if enabled
    prepared = raw_content
    if redact_secrets and not raw_content.startswith("[Binary or corrupted"):
        from veilframe.folder.security.secret_detector import redact_inline_secrets
        lang = getattr(file_rec, "language", "") or getattr(file_rec, "detected_language", "") or ""
        prepared, sec_alerts = redact_inline_secrets(prepared, file_rec.path, language=lang)
        if sec_alerts and not getattr(file_rec, "secret_alerts", None):
            file_rec.secret_alerts = [a.description for a in sec_alerts]

    # 5. Calculate precise token count; DO NOT truncate included files unless explicitly forced
    estimated = estimate_tokens(prepared, file_rec.language)
    if truncate_oversized and max_tokens is not None and estimated > max_tokens:
        char_limit = int(max_tokens * 3.5)
        # Extract structural outline across Python and brace/C-family languages
        structural_outline = generate_structural_outline(prepared, file_rec.language, file_rec.extension)

        if structural_outline:
            head = prepared[: int(char_limit * 0.4)]
            tail = prepared[-int(char_limit * 0.2) :]
            truncation_notice = (
                f"\n\n... [TRUNCATED {estimated - max_tokens:,} TOKENS FOR CONTEXT BUDGET] ...\n"
                f"{structural_outline}\n"
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

