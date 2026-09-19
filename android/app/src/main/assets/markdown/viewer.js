(function() {
  'use strict';

  let currentToc = [];
  let searchMatches = [];
  let currentSearchIndex = -1;
  let isDarkMode = true;

  // Initialize Mermaid with safe defaults
  if (typeof mermaid !== 'undefined') {
    try {
      mermaid.initialize({
        startOnLoad: false,
        theme: 'dark',
        securityLevel: 'loose',
        fontFamily: '-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif'
      });
    } catch (e) {
      console.warn('Mermaid init error:', e);
    }
  }

  // Reading progress tracker
  window.addEventListener('scroll', function() {
    const docHeight = document.documentElement.scrollHeight - document.documentElement.clientHeight;
    const scrollPos = window.scrollY || document.documentElement.scrollTop;
    const pct = docHeight > 0 ? (scrollPos / docHeight) * 100 : 0;
    const bar = document.getElementById('reading-progress-bar');
    if (bar) {
      bar.style.width = Math.min(100, Math.max(0, pct)) + '%';
    }
    if (window.VeilFrameBridge && typeof window.VeilFrameBridge.onScrollPositionChanged === 'function') {
      window.VeilFrameBridge.onScrollPositionChanged(scrollPos, docHeight);
    }
  }, { passive: true });

  window.VeilFrameMarkdown = {
    render: function(markdownText, baseUri) {
      currentToc = [];
      searchMatches = [];
      currentSearchIndex = -1;

      const bodyEl = document.getElementById('markdown-body');
      const frontMatterEl = document.getElementById('front-matter-card');
      const emptyEl = document.getElementById('empty-state');

      if (!markdownText || !markdownText.trim()) {
        bodyEl.innerHTML = '';
        frontMatterEl.style.display = 'none';
        emptyEl.style.display = 'block';
        if (window.VeilFrameBridge && typeof window.VeilFrameBridge.onTableOfContents === 'function') {
          window.VeilFrameBridge.onTableOfContents('[]');
        }
        return;
      }
      emptyEl.style.display = 'none';

      // 1. Parse YAML Front Matter
      let content = markdownText;
      const frontMatterMatch = content.match(/^---\r?\n([\s\S]*?)\r?\n---\r?\n?/);
      if (frontMatterMatch) {
        content = content.substring(frontMatterMatch[0].length);
        const yamlStr = frontMatterMatch[1];
        renderFrontMatter(yamlStr, frontMatterEl);
      } else {
        frontMatterEl.style.display = 'none';
      }

      // 2. Protect LaTeX Math from Marked parser
      const mathPlaceholders = [];
      // Display math: $$ ... $$ or \[ ... \]
      content = content.replace(/\$\$([\s\S]*?)\$\$/g, function(match, math) {
        const id = '%%%KATEX_DISPLAY_' + mathPlaceholders.length + '%%%';
        mathPlaceholders.push({ id: id, math: math.trim(), display: true });
        return id;
      });
      content = content.replace(/\\\[([\s\S]*?)\\\]/g, function(match, math) {
        const id = '%%%KATEX_DISPLAY_' + mathPlaceholders.length + '%%%';
        mathPlaceholders.push({ id: id, math: math.trim(), display: true });
        return id;
      });
      // Inline math: $ ... $ or \( ... \) (avoid matching currency like $10)
      content = content.replace(/(^|[^\$])\$([^\$\n]+?)\$(?!\$)/g, function(match, prefix, math) {
        const id = '%%%KATEX_INLINE_' + mathPlaceholders.length + '%%%';
        mathPlaceholders.push({ id: id, math: math.trim(), display: false });
        return prefix + id;
      });
      content = content.replace(/\\\(([\s\S]*?)\\\)/g, function(match, math) {
        const id = '%%%KATEX_INLINE_' + mathPlaceholders.length + '%%%';
        mathPlaceholders.push({ id: id, math: math.trim(), display: false });
        return id;
      });

      // 3. Configure Marked.js
      const renderer = new marked.Renderer();

      // Custom Code & Mermaid Renderer
      renderer.code = function(code, lang) {
        const safeLang = (lang || '').trim().toLowerCase();
        if (safeLang === 'mermaid') {
          const encoded = encodeURIComponent(code);
          return '<div class="mermaid-container">' +
                   '<div class="mermaid" data-source="' + encoded + '">' + escapeHtml(code) + '</div>' +
                   '<div class="mermaid-actions">' +
                     '<button class="mermaid-copy-btn" onclick="window.VeilFrameMarkdown.copyCode(decodeURIComponent(\'' + encoded.replace(/'/g, "\\'") + '\'), this)">Copy Diagram</button>' +
                   '</div>' +
                 '</div>';
        }

        let highlighted = '';
        let displayLang = safeLang || 'text';
        if (typeof hljs !== 'undefined' && safeLang && hljs.getLanguage(safeLang)) {
          try {
            highlighted = hljs.highlight(code, { language: safeLang, ignoreIllegals: true }).value;
          } catch (e) {
            highlighted = escapeHtml(code);
          }
        } else if (typeof hljs !== 'undefined' && !safeLang) {
          try {
            const autoRes = hljs.highlightAuto(code);
            highlighted = autoRes.value;
            displayLang = autoRes.language || 'text';
          } catch (e) {
            highlighted = escapeHtml(code);
          }
        } else {
          highlighted = escapeHtml(code);
        }

        const encodedCode = encodeURIComponent(code);
        return '<div class="code-block-wrapper">' +
                 '<div class="code-block-header">' +
                   '<span>' + escapeHtml(displayLang.toUpperCase()) + '</span>' +
                   '<button class="code-copy-btn" onclick="window.VeilFrameMarkdown.copyCode(decodeURIComponent(\'' + encodedCode.replace(/'/g, "\\'") + '\'), this)">Copy</button>' +
                 '</div>' +
                 '<pre><code class="hljs ' + (safeLang ? 'language-' + safeLang : '') + '">' + highlighted + '</code></pre>' +
               '</div>';
      };

      // Custom Table Renderer (wraps in responsive scroll container)
      renderer.table = function(header, body) {
        return '<div class="table-wrapper">' +
                 '<table>' +
                   '<thead>' + header + '</thead>' +
                   '<tbody>' + body + '</tbody>' +
                 '</table>' +
               '</div>';
      };

      // Custom Heading Renderer (stable IDs + TOC generation)
      renderer.heading = function(text, level) {
        const cleanText = text.replace(/<[^>]*>/g, '').trim();
        const slug = 'heading-' + currentToc.length + '-' + cleanText.toLowerCase().replace(/[^\w\u4e00-\u9fa5]+/g, '-').replace(/^-+|-+$/g, '');
        if (level >= 1 && level <= 4) {
          currentToc.push({ id: slug, title: cleanText, level: level });
        }
        return '<h' + level + ' id="' + slug + '">' + text + '</h' + level + '>';
      };

      // Custom Image Renderer (resolves relative image paths)
      renderer.image = function(href, title, text) {
        let resolvedHref = href;
        if (href && !href.startsWith('http://') && !href.startsWith('https://') && !href.startsWith('data:')) {
          if (baseUri) {
            resolvedHref = '/__vf_resource__?path=' + encodeURIComponent(href) + '&base=' + encodeURIComponent(baseUri);
          }
        }
        const titleAttr = title ? ' title="' + escapeHtml(title) + '"' : '';
        return '<img src="' + resolvedHref + '" alt="' + escapeHtml(text || '') + '"' + titleAttr + ' loading="lazy" style="max-width: 100%; height: auto; border-radius: 6px;">';
      };

      // Custom Link Renderer (security - opens in external browser)
      renderer.link = function(href, title, text) {
        const titleAttr = title ? ' title="' + escapeHtml(title) + '"' : '';
        return '<a href="' + href + '"' + titleAttr + ' target="_blank" rel="noopener noreferrer">' + text + '</a>';
      };

      // 4. Parse Markdown into HTML
      let html = marked.parse(content, {
        renderer: renderer,
        gfm: true,
        breaks: true,
        pedantic: false
      });

      // 5. Restore Math with KaTeX
      for (let i = 0; i < mathPlaceholders.length; i++) {
        const item = mathPlaceholders[i];
        let renderedMath = '';
        if (typeof katex !== 'undefined') {
          try {
            renderedMath = katex.renderToString(item.math, {
              displayMode: item.display,
              throwOnError: false
            });
          } catch (e) {
            renderedMath = '<span class="katex-error">' + escapeHtml(item.math) + '</span>';
          }
        } else {
          renderedMath = escapeHtml(item.math);
        }
        html = html.split(item.id).join(renderedMath);
      }

      bodyEl.innerHTML = html;

      // 6. Render Mermaid Diagrams
      renderMermaidDiagrams();

      // 7. Notify TOC to Kotlin
      if (window.VeilFrameBridge && typeof window.VeilFrameBridge.onTableOfContents === 'function') {
        window.VeilFrameBridge.onTableOfContents(JSON.stringify(currentToc));
      }

      // 8. Notify Render Finished
      if (window.VeilFrameBridge && typeof window.VeilFrameBridge.onRenderFinished === 'function') {
        setTimeout(function() {
          window.VeilFrameBridge.onRenderFinished();
        }, 80);
      }
    },

    setTheme: function(theme) {
      isDarkMode = (theme === 'dark');
      document.body.className = document.body.className.replace(/theme-(dark|light)/g, '').trim();
      document.body.classList.add(isDarkMode ? 'theme-dark' : 'theme-light');

      const hljsTheme = document.getElementById('hljs-theme');
      if (hljsTheme) {
        hljsTheme.href = isDarkMode ? 'lib/highlightjs/github-dark.min.css' : 'lib/highlightjs/github.min.css';
      }

      if (typeof mermaid !== 'undefined') {
        try {
          mermaid.initialize({
            startOnLoad: false,
            theme: isDarkMode ? 'dark' : 'default',
            securityLevel: 'loose'
          });
          renderMermaidDiagrams();
        } catch (e) {}
      }
    },

    setTextScale: function(scale) {
      const s = parseFloat(scale) || 1.0;
      document.documentElement.style.setProperty('--vf-body-size', (16 * s) + 'px');
      document.documentElement.style.setProperty('--vf-code-size', (14 * s) + 'px');
      document.documentElement.style.setProperty('--vf-heading-scale', s.toString());
    },

    setReadingWidth: function(mode) {
      document.body.className = document.body.className.replace(/width-(comfortable|full)/g, '').trim();
      document.body.classList.add(mode === 'full' ? 'width-full' : 'width-comfortable');
    },

    scrollToHeading: function(id) {
      const el = document.getElementById(id);
      if (el) {
        el.scrollIntoView({ behavior: 'smooth', block: 'start' });
      }
    },

    getScrollY: function() {
      return window.scrollY || document.documentElement.scrollTop || 0;
    },

    setScrollY: function(y) {
      window.scrollTo({ top: parseInt(y, 10) || 0, behavior: 'auto' });
    },

    copyCode: function(text, btnElement) {
      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(text).then(function() {
          showCopySuccess(btnElement);
        }).catch(function() {
          fallbackCopy(text, btnElement);
        });
      } else {
        fallbackCopy(text, btnElement);
      }
    },

    // Search / Find in page
    find: function(query) {
      this.clearFind();
      if (!query || !query.trim()) return 0;

      const q = query.trim().toLowerCase();
      const bodyEl = document.getElementById('markdown-body');
      highlightTextNodes(bodyEl, q);

      searchMatches = Array.from(document.querySelectorAll('mark.vf-search-highlight'));
      currentSearchIndex = -1;
      if (searchMatches.length > 0) {
        this.findNext();
      }
      return searchMatches.length;
    },

    findNext: function() {
      if (searchMatches.length === 0) return;
      if (currentSearchIndex >= 0 && currentSearchIndex < searchMatches.length) {
        searchMatches[currentSearchIndex].classList.remove('active');
      }
      currentSearchIndex = (currentSearchIndex + 1) % searchMatches.length;
      const target = searchMatches[currentSearchIndex];
      target.classList.add('active');
      target.scrollIntoView({ behavior: 'smooth', block: 'center' });
      if (window.VeilFrameBridge && typeof window.VeilFrameBridge.onSearchMatchChanged === 'function') {
        window.VeilFrameBridge.onSearchMatchChanged(currentSearchIndex + 1, searchMatches.length);
      }
    },

    findPrevious: function() {
      if (searchMatches.length === 0) return;
      if (currentSearchIndex >= 0 && currentSearchIndex < searchMatches.length) {
        searchMatches[currentSearchIndex].classList.remove('active');
      }
      currentSearchIndex = (currentSearchIndex - 1 + searchMatches.length) % searchMatches.length;
      const target = searchMatches[currentSearchIndex];
      target.classList.add('active');
      target.scrollIntoView({ behavior: 'smooth', block: 'center' });
      if (window.VeilFrameBridge && typeof window.VeilFrameBridge.onSearchMatchChanged === 'function') {
        window.VeilFrameBridge.onSearchMatchChanged(currentSearchIndex + 1, searchMatches.length);
      }
    },

    clearFind: function() {
      searchMatches = [];
      currentSearchIndex = -1;
      const marks = document.querySelectorAll('mark.vf-search-highlight');
      marks.forEach(function(m) {
        const parent = m.parentNode;
        parent.replaceChild(document.createTextNode(m.textContent), m);
        parent.normalize();
      });
      if (window.VeilFrameBridge && typeof window.VeilFrameBridge.onSearchMatchChanged === 'function') {
        window.VeilFrameBridge.onSearchMatchChanged(0, 0);
      }
    }
  };

  function renderMermaidDiagrams() {
    if (typeof mermaid === 'undefined') return;
    const containers = document.querySelectorAll('.mermaid');
    containers.forEach(function(el, idx) {
      const source = el.getAttribute('data-source') ? decodeURIComponent(el.getAttribute('data-source')) : el.textContent;
      const uniqueId = 'mermaid-svg-' + idx + '-' + Date.now();
      try {
        mermaid.render(uniqueId, source).then(function(res) {
          el.innerHTML = '<div class="mermaid-diagram-box">' + res.svg + '</div>';
        }).catch(function(err) {
          el.innerHTML = '<div class="mermaid-error"><span>⚠️ Mermaid rendering error</span><pre>' + escapeHtml(source) + '</pre></div>';
        });
      } catch (e) {
        el.innerHTML = '<div class="mermaid-error"><span>⚠️ Mermaid rendering failed</span><pre>' + escapeHtml(source) + '</pre></div>';
      }
    });
  }

  function renderFrontMatter(yamlStr, container) {
    try {
      const lines = yamlStr.split(/\r?\n/);
      const items = [];
      let title = '';
      lines.forEach(function(line) {
        const colonIdx = line.indexOf(':');
        if (colonIdx > 0) {
          const key = line.substring(0, colonIdx).trim();
          const val = line.substring(colonIdx + 1).trim().replace(/^["']|["']$/g, '');
          if (key.toLowerCase() === 'title' && !title) {
            title = val;
          } else if (key && val) {
            items.push({ key: key, val: val });
          }
        }
      });

      if (!title && items.length === 0) {
        container.style.display = 'none';
        return;
      }

      let html = '';
      if (title) {
        html += '<div class="front-matter-title">' + escapeHtml(title) + '</div>';
      }
      if (items.length > 0) {
        html += '<div class="front-matter-grid">';
        items.forEach(function(it) {
          html += '<div class="front-matter-item">' +
                    '<span class="front-matter-key">' + escapeHtml(it.key) + '</span>' +
                    '<span class="front-matter-val">' + escapeHtml(it.val) + '</span>' +
                  '</div>';
        });
        html += '</div>';
      }
      container.innerHTML = html;
      container.style.display = 'block';
    } catch (e) {
      container.style.display = 'none';
    }
  }

  function showCopySuccess(btn) {
    if (!btn) return;
    const oldText = btn.textContent;
    btn.textContent = 'Copied ✓';
    btn.classList.add('copied');
    setTimeout(function() {
      btn.textContent = oldText;
      btn.classList.remove('copied');
    }, 1500);
  }

  function fallbackCopy(text, btn) {
    try {
      const ta = document.createElement('textarea');
      ta.value = text;
      ta.style.position = 'fixed';
      ta.style.opacity = '0';
      document.body.appendChild(ta);
      ta.select();
      document.execCommand('copy');
      document.body.removeChild(ta);
      showCopySuccess(btn);
    } catch (e) {}
  }

  function highlightTextNodes(node, query) {
    if (node.nodeType === 3) { // Text node
      const val = node.nodeValue;
      const lower = val.toLowerCase();
      const pos = lower.indexOf(query);
      if (pos >= 0) {
        const mark = document.createElement('mark');
        mark.className = 'vf-search-highlight';
        mark.appendChild(document.createTextNode(val.substring(pos, pos + query.length)));

        const after = node.splitText(pos);
        after.nodeValue = after.nodeValue.substring(query.length);
        node.parentNode.insertBefore(mark, after);
        highlightTextNodes(after, query);
      }
    } else if (node.nodeType === 1 && node.childNodes && !/(script|style|mark|textarea)/i.test(node.tagName)) {
      for (let i = 0; i < node.childNodes.length; i++) {
        highlightTextNodes(node.childNodes[i], query);
      }
    }
  }

  function escapeHtml(str) {
    if (!str) return '';
    return str.replace(/&/g, '&amp;')
              .replace(/</g, '&lt;')
              .replace(/>/g, '&gt;')
              .replace(/"/g, '&quot;')
              .replace(/'/g, '&#039;');
  }

})();
