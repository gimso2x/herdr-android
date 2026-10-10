(function () {
  // Metrics-only snapshot of the herdr-web-ui shell (one JSON-serialisable expression). Numbers,
  // booleans, class names and counts only: no pane text, titles, ids or workspace labels.
  function rect(e) {
    if (!e) return null;
    var b = e.getBoundingClientRect();
    return { x: Math.round(b.x), y: Math.round(b.y), w: Math.round(b.width), h: Math.round(b.height) };
  }
  function style(e, p) { return e ? getComputedStyle(e)[p] : null; }
  function one(sel) { return document.querySelector(sel); }
  function supports(p, v) { try { return CSS.supports(p, v); } catch (e) { return null; } }
  var app = one('.app');
  var root = document.documentElement;
  var vv = window.visualViewport;
  var paneTerminal = one('.pane-terminal');
  var xterms = [].map.call(document.querySelectorAll('.xterm'), function (x) {
    var rows = x.querySelector('.xterm-rows');
    var kids = rows ? rows.children : [];
    var nonEmpty = 0;
    for (var i = 0; i < kids.length; i++) if ((kids[i].textContent || '').trim().length > 0) nonEmpty++;
    return {
      rect: rect(x), screen: rect(x.querySelector('.xterm-screen')), rows: rect(rows),
      rowsChildren: kids.length, nonEmptyRows: nonEmpty, hasNonEmptyRows: nonEmpty > 0,
      visibility: style(x, 'visibility'), display: style(x, 'display'),
    };
  });
  var transcript = one('.chat-transcript');
  var chatView = one('.chat-view');
  var d = window.__diag || null;
  return {
    env: {
      ua: navigator.userAgent.replace(/\([^)]*\)/, '(..)').slice(0, 120),
      coarse: matchMedia('(pointer: coarse)').matches,
      iw: window.innerWidth, ih: window.innerHeight, vvh: vv ? Math.round(vv.height) : null,
      dpr: window.devicePixelRatio, screenH: screen.height, clientH: root.clientHeight,
      dvh: supports('height', '100dvh'), lvh: supports('height', '100lvh'), has: supports('selector(:has(a))', ''),
      decompressionStream: typeof DecompressionStream, resizeObserver: typeof ResizeObserver,
      structuredClone: typeof structuredClone, at: typeof [].at, replaceAll: typeof ''.replaceAll,
      keyboardAttr: root.hasAttribute('data-keyboard'), appHeightVar: root.style.getPropertyValue('--app-height') || null,
    },
    page: {
      host: location.host, pathname: location.pathname,
      searchKeys: Array.from(new URLSearchParams(location.search).keys()).join(','),
      viewParam: new URLSearchParams(location.search).get('view'),
      titleIsHerdr: document.title === 'herdr web ui' || / · herdr$/.test(document.title),
      readyState: document.readyState, rootChildren: one('#root') ? one('#root').children.length : -1,
    },
    layout: {
      app: rect(app), appCssHeight: style(app, 'height'),
      header: rect(one('.app-header')), body: rect(one('.app-body')), sidebar: rect(one('.sidebar')),
      paneColumn: rect(one('.pane-column')), terminalHost: rect(one('.terminal-host')),
      terminalStack: rect(one('.terminal-stack')), stackClass: one('.terminal-stack') ? one('.terminal-stack').className : null,
      surface: rect(one('.terminal-surface')),
      paneTerminal: rect(paneTerminal), paneTerminalClass: paneTerminal ? paneTerminal.className : null,
      paneTerminalVisibility: style(paneTerminal, 'visibility'),
      keyBar: rect(one('.key-bar')), composer: rect(one('.composer')), tabStrip: rect(one('.tab-strip')),
      banners: [].map.call(document.querySelectorAll('.terminal-banner'), function (b) { return b.className; }),
      errorStatePresent: !!one('.error-state'), updateNotice: !!one('.update-notice'),
    },
    xtermCount: xterms.length,
    xterms: xterms,
    anyXtermTall: xterms.some(function (x) { return x.rect && x.rect.h > 100; }),
    anyXtermRows: xterms.some(function (x) { return x.hasNonEmptyRows; }),
    chat: {
      viewRect: rect(chatView), transcriptRect: rect(transcript),
      transcriptChildren: transcript ? transcript.children.length : 0, empty: !!one('.chat-empty'),
    },
    ws: d && d.sockets.map(function (s) {
      return {
        path: s.path, opened: s.opened, closeCode: s.closeCode, errors: s.errors, recv: s.recv, sent: s.sent,
        recvTextBytes: s.recvTextBytes, recvBinaryBytes: s.recvBinaryBytes, ptyDataMessages: s.ptyDataMessages,
        ptyDataChars: s.ptyDataChars, firstMessageMs: s.firstMessageMs,
      };
    }),
    diag: d && {
      consoleErrors: d.consoleErrors, pageErrors: d.pageErrors, resourceErrors: d.resourceErrors,
      cspViolations: d.cspViolations, fetches: d.fetches, dropped: d.dropped, blocked: d.blocked, warns: d.warnCount || 0,
    },
  };
})()
