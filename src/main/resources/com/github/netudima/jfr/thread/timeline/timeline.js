/* jfr-thread-timeline viewer - self contained, no external dependencies. */
(function () {
  'use strict';

  var $ = function (id) { return document.getElementById(id); };

  var D = null;             // raw model from the embedded payload
  var threads = [];         // decoded per-thread timelines
  var stateColors = [];
  var stateNames = [];
  var theme = {};

  var S = {
    t0: 0, t1: 1,           // visible time window, microseconds from recording start
    span: 1,                // full recording span
    rowH: 16,
    groupH: 28,          // group headers are taller: their summary band carries real detail
    rowY: null,          // cumulative row offsets, so heights can differ per row
    totalH: 0,
    gutterW: 250,
    scrollTop: 0,
    filter: '',
    sort: 'name',
    activeOnly: false,
    grouped: true,
    collapsed: new Set(),
    hidden: new Set(),
    rows: [],            // display rows: group headers and thread rows
    visibleThreads: [],  // the threads behind them, flat
    hoverRow: -1,
    pinned: null,
    drag: null,
    ovDrag: null
  };

  var chart = $('chart'), axis = $('axis'), overview = $('overview'), scroller = $('scroller');
  var cctx = chart.getContext('2d'), actx = axis.getContext('2d'), octx = overview.getContext('2d');
  var overviewCache = null;

  // ---------------------------------------------------------------- data load

  function loadData() {
    var el = $('tl-data');
    var mode = el.dataset.mode;
    var text = el.textContent;
    el.textContent = '';                       // release the raw payload early
    if (mode === 'json') {
      return Promise.resolve(JSON.parse(text));
    }
    if (typeof DecompressionStream === 'undefined') {
      return Promise.reject(new Error(
        'This page stores its data gzipped and the browser has no DecompressionStream support.\n' +
        'Re-generate the report with:  jfr-thread-timeline --compress never ...'));
    }
    var bin = atob(text.trim());
    var bytes = new Uint8Array(bin.length);
    for (var i = 0; i < bin.length; i++) { bytes[i] = bin.charCodeAt(i); }
    var stream = new Blob([bytes]).stream().pipeThrough(new DecompressionStream('gzip'));
    return new Response(stream).text().then(JSON.parse);
  }

  function decode(model) {
    D = model;
    S.span = Math.max(1, D.meta.durationUs);
    S.t0 = 0;
    S.t1 = S.span;
    stateColors = D.states.map(function (s) { return s.c; });
    stateNames = D.states.map(function (s) { return s.n; });

    threads = D.threads.map(function (t, i) {
      var seg = t.seg, n = seg.length / 5;
      var start = new Float64Array(n), end = new Float64Array(n);
      var state = new Int32Array(n), stack = new Int32Array(n), samples = new Int32Array(n);
      var cur = 0;
      for (var k = 0, p = 0; k < n; k++, p += 5) {
        cur += seg[p];
        start[k] = cur;
        end[k] = cur + seg[p + 1];
        state[k] = seg[p + 2];
        stack[k] = seg[p + 3];
        samples[k] = seg[p + 4];
      }
      t.seg = null;
      var key = splitTrailingNumber(t.n);
      return {
        idx: i, name: t.n, javaId: t.j, osId: t.o, sampleCount: t.s, stateTime: t.st,
        group: t.g === undefined ? -1 : t.g,
        sortPrefix: key.prefix, sortNum: key.num,
        start: start, end: end, state: state, stack: stack, samples: samples, n: n,
        first: n ? start[0] : 0, covered: sum(t.st)
      };
    });
    D.threads = null;
  }

  function sum(a) { var s = 0; for (var i = 0; i < a.length; i++) { s += a[i]; } return s; }

  /**
   * Splits a trailing run of digits off a thread name, so names can be ordered by their prefix
   * and then by that suffix numerically: "MutatePool-Worker-10" becomes ["MutatePool-Worker-", 10].
   * Names not ending in a digit keep the whole name as the prefix and sort ahead of numbered
   * siblings, which is what "Worker" before "Worker-1" should do.
   */
  function splitTrailingNumber(name) {
    var i = name.length;
    while (i > 0) {
      var c = name.charCodeAt(i - 1);
      if (c < 48 || c > 57) { break; }
      i--;
    }
    if (i === name.length) {
      return { prefix: name, num: -1 };
    }
    // a suffix long enough to lose precision is not a counter; treat it as part of the name
    var digits = name.slice(i);
    if (digits.length > 15) {
      return { prefix: name, num: -1 };
    }
    return { prefix: name.slice(0, i), num: parseInt(digits, 10) };
  }

  // ------------------------------------------------------------- formatting

  function fmtTime(us) {
    var a = Math.abs(us);
    if (a < 0.5) { return '0'; }
    if (a >= 1e6) { return (us / 1e6).toFixed(a >= 1e7 ? 2 : 3) + ' s'; }
    if (a >= 1e3) { return (us / 1e3).toFixed(a >= 1e4 ? 1 : 2) + ' ms'; }
    return us.toFixed(0) + ' µs';
  }

  function fmtDur(us) {
    if (us >= 1e6) { return (us / 1e6).toFixed(3) + ' s'; }
    if (us >= 1e3) { return (us / 1e3).toFixed(2) + ' ms'; }
    return us.toFixed(0) + ' µs';
  }

  function fmtCount(n) { return n.toLocaleString(); }

  function fmtWall(us) {
    if (!D.meta.startEpochMs) { return ''; }
    var d = new Date(D.meta.startEpochMs + us / 1000);
    var p = function (x, w) { return String(x).padStart(w || 2, '0'); };
    return p(d.getHours()) + ':' + p(d.getMinutes()) + ':' + p(d.getSeconds()) + '.' + p(d.getMilliseconds(), 3);
  }

  function esc(s) {
    return String(s).replace(/[&<>"]/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c];
    });
  }

  // ------------------------------------------------------------------ theme

  function readTheme() {
    var cs = getComputedStyle(document.documentElement);
    theme = {
      fg: cs.getPropertyValue('--fg').trim(),
      fgDim: cs.getPropertyValue('--fg-dim').trim(),
      bg: cs.getPropertyValue('--bg').trim(),
      bgAlt: cs.getPropertyValue('--bg-alt').trim(),
      bgRow: cs.getPropertyValue('--bg-row').trim(),
      border: cs.getPropertyValue('--border').trim(),
      borderStrong: cs.getPropertyValue('--border-strong').trim(),
      accent: cs.getPropertyValue('--accent').trim(),
      sel: cs.getPropertyValue('--sel').trim()
    };
  }

  // ----------------------------------------------------------------- layout

  var dpr = 1, plotW = 0, viewH = 0;

  function sizeCanvas(cv, ctx, cssW, cssH) {
    dpr = window.devicePixelRatio || 1;
    cv.width = Math.max(1, Math.round(cssW * dpr));
    cv.height = Math.max(1, Math.round(cssH * dpr));
    cv.style.height = cssH + 'px';
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  }

  var lastW = -1, lastH = -1;

  function relayout() {
    var w = scroller.clientWidth;
    viewH = scroller.clientHeight;
    if (w === lastW && viewH === lastH) { return false; }
    lastW = w;
    lastH = viewH;
    plotW = Math.max(10, w - S.gutterW);
    sizeCanvas(chart, cctx, w, viewH);
    sizeCanvas(axis, actx, w, 22);
    sizeCanvas(overview, octx, w, 42);
    var total = Math.max(S.totalH || viewH, viewH);
    $('spacer').style.height = total + 'px';
    $('spacer').style.marginTop = (-viewH) + 'px';
    overviewCache = null;
    return true;
  }

  function timeToX(t) { return S.gutterW + (t - S.t0) * plotW / (S.t1 - S.t0); }
  function xToTime(x) { return S.t0 + (x - S.gutterW) * (S.t1 - S.t0) / plotW; }

  // ------------------------------------------------------------------- rows

  function compileFilter(text) {
    text = text.trim();
    if (!text) { return null; }
    var m = /^\/(.*)\/([a-z]*)$/.exec(text);
    try {
      return m ? new RegExp(m[1], m[2] || 'i') : new RegExp(escapeRe(text), 'i');
    } catch (e) {
      return new RegExp(escapeRe(text), 'i');
    }
  }

  function escapeRe(s) { return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'); }

  function firstSegmentAfter(t, time) {
    var lo = 0, hi = t.n;
    while (lo < hi) {
      var mid = (lo + hi) >> 1;
      if (t.end[mid] <= time) { lo = mid + 1; } else { hi = mid; }
    }
    return lo;
  }

  function activeInView(t) {
    var i = firstSegmentAfter(t, S.t0);
    return i < t.n && t.start[i] < S.t1;
  }

  function groupName(t) {
    return (D.groups && t.group >= 0 && t.group < D.groups.length) ? D.groups[t.group] : '';
  }

  function hasGroups() {
    return !!(D.groups && D.groups.length);
  }

  function rebuildRows() {
    var re = compileFilter(S.filter);
    // a thread matches the filter by its own name or by the group it belongs to
    var list = threads.filter(function (t) {
      return !re || re.test(t.name) || re.test(groupName(t));
    });
    if (S.activeOnly) { list = list.filter(activeInView); }

    var mode = S.sort;
    if (mode === 'name') {
      // natural order, so Worker-2 precedes Worker-10 instead of following Worker-19
      list.sort(function (a, b) {
        return a.sortPrefix.localeCompare(b.sortPrefix)
            || (a.sortNum - b.sortNum)
            || (a.osId - b.osId);
      });
    } else if (mode === 'samples') {
      list.sort(function (a, b) { return b.sampleCount - a.sampleCount; });
    } else if (mode === 'first') {
      list.sort(function (a, b) { return a.first - b.first; });
    } else if (mode.indexOf('state:') === 0) {
      var si = +mode.slice(6);
      list.sort(function (a, b) {
        var da = a.covered ? a.stateTime[si] / a.covered : 0;
        var db = b.covered ? b.stateTime[si] / b.covered : 0;
        return db - da || b.stateTime[si] - a.stateTime[si];
      });
    }
    S.visibleThreads = list;

    if (!S.grouped || !hasGroups()) {
      S.rows = list.map(function (t) { return { thread: t }; });
    } else {
      // groups keep their configured order; the sort above orders threads inside each one
      var buckets = [];
      for (var g = 0; g < D.groups.length; g++) { buckets.push([]); }
      var loose = [];
      for (var i = 0; i < list.length; i++) {
        var gi = list[i].group;
        if (gi >= 0 && gi < buckets.length) { buckets[gi].push(list[i]); } else { loose.push(list[i]); }
      }
      S.rows = [];
      for (var gg = 0; gg < buckets.length; gg++) {
        if (!buckets[gg].length) { continue; }
        S.rows.push({ group: gg, members: buckets[gg] });
        if (!S.collapsed.has(gg)) {
          for (var m = 0; m < buckets[gg].length; m++) { S.rows.push({ thread: buckets[gg][m] }); }
        }
      }
      for (var l = 0; l < loose.length; l++) { S.rows.push({ thread: loose[l] }); }
    }

    measureRows();
    var total = Math.max(S.totalH, viewH);
    $('spacer').style.height = total + 'px';
    $('spacer').style.marginTop = (-viewH) + 'px';
    if (scroller.scrollTop > total - viewH) { scroller.scrollTop = Math.max(0, total - viewH); }
    overviewCache = null;
    renderLegend();
    updateStatus();
  }

  function measureRows() {
    var n = S.rows.length;
    var ys = new Float64Array(n + 1);
    var y = 0;
    for (var i = 0; i < n; i++) {
      ys[i] = y;
      y += S.rows[i].group !== undefined ? S.groupH : S.rowH;
    }
    ys[n] = y;
    S.rowY = ys;
    S.totalH = y;
  }

  function rowHeight(i) { return S.rowY[i + 1] - S.rowY[i]; }

  /** Index of the row covering an absolute y offset, or -1. */
  function rowIndexAt(yAbs) {
    if (!S.rowY || yAbs < 0 || yAbs >= S.totalH) { return -1; }
    var lo = 0, hi = S.rows.length;
    while (lo < hi) {
      var mid = (lo + hi) >> 1;
      if (S.rowY[mid + 1] <= yAbs) { lo = mid + 1; } else { hi = mid; }
    }
    return lo < S.rows.length ? lo : -1;
  }

  // ---------------------------------------------------------------- drawing

  var rafPending = false;

  function requestDraw() {
    if (rafPending) { return; }
    rafPending = true;
    requestAnimationFrame(function () { rafPending = false; draw(); });
  }

  function draw() {
    drawAxis();
    drawChart();
    drawOverview();
  }

  function niceStep(raw) {
    var mag = Math.pow(10, Math.floor(Math.log10(raw)));
    var n = raw / mag;
    var s = n <= 1 ? 1 : n <= 2 ? 2 : n <= 5 ? 5 : 10;
    return s * mag;
  }

  function ticks() {
    var span = S.t1 - S.t0;
    var step = niceStep(span / Math.max(2, Math.floor(plotW / 110)));
    var out = [];
    for (var t = Math.ceil(S.t0 / step) * step; t <= S.t1; t += step) { out.push(t); }
    return out;
  }

  function drawAxis() {
    var w = axis.width / dpr, h = 22;
    actx.clearRect(0, 0, w, h);
    actx.fillStyle = theme.bgAlt;
    actx.fillRect(0, 0, w, h);
    actx.fillStyle = theme.fgDim;
    actx.font = '10px ui-monospace, SFMono-Regular, Menlo, monospace';
    actx.textBaseline = 'middle';

    var tk = ticks();
    for (var i = 0; i < tk.length; i++) {
      var x = Math.round(timeToX(tk[i])) + 0.5;
      if (x < S.gutterW) { continue; }
      actx.strokeStyle = theme.border;
      actx.beginPath();
      actx.moveTo(x, 14);
      actx.lineTo(x, 22);
      actx.stroke();
      actx.textAlign = i === tk.length - 1 && x > w - 40 ? 'right' : 'center';
      actx.fillText(fmtTime(tk[i]), Math.min(x, w - 2), 7);
    }
    actx.strokeStyle = theme.border;
    actx.beginPath();
    actx.moveTo(S.gutterW + 0.5, 0);
    actx.lineTo(S.gutterW + 0.5, h);
    actx.stroke();
  }

  function drawChart() {
    var w = chart.width / dpr, h = chart.height / dpr;
    cctx.clearRect(0, 0, w, h);
    cctx.fillStyle = theme.bg;
    cctx.fillRect(0, 0, w, h);

    var top = S.scrollTop;
    var first = rowIndexAt(top);
    if (first < 0) { first = 0; }
    var last = rowIndexAt(top + h - 1);
    if (last < 0) { last = S.rows.length - 1; }

    // vertical grid
    var tk = ticks();
    cctx.strokeStyle = theme.border;
    cctx.lineWidth = 1;
    cctx.beginPath();
    for (var i = 0; i < tk.length; i++) {
      var gx = Math.round(timeToX(tk[i])) + 0.5;
      if (gx > S.gutterW) { cctx.moveTo(gx, 0); cctx.lineTo(gx, h); }
    }
    cctx.stroke();

    cctx.font = '11px -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif';
    cctx.textBaseline = 'middle';

    for (var r = first; r <= last; r++) {
      var row = S.rows[r];
      if (!row) { continue; }
      var y = S.rowY[r] - top;
      var rh = rowHeight(r);

      if (row.group !== undefined) {
        cctx.fillStyle = theme.bgAlt;
        cctx.fillRect(0, y, w, rh);
      } else if (r % 2 === 1) {
        cctx.fillStyle = theme.bgRow;
        cctx.fillRect(0, y, w, rh);
      }
      if (r === S.hoverRow) {
        cctx.fillStyle = theme.sel;
        cctx.fillRect(0, y, w, rh);
      }
      if (row.group !== undefined) {
        drawGroupBand(row.members, y, rh);
      } else {
        drawRow(row.thread, y, rh);
      }
    }

    // gutter: overpaint the plot that bled left, then the labels
    cctx.fillStyle = theme.bg;
    cctx.fillRect(0, 0, S.gutterW, h);
    for (var r2 = first; r2 <= last; r2++) {
      var row2 = S.rows[r2];
      if (!row2) { continue; }
      var yy = S.rowY[r2] - top;
      var rh2 = rowHeight(r2);
      var isGroup = row2.group !== undefined;
      if (isGroup) {
        cctx.fillStyle = theme.bgAlt;
        cctx.fillRect(0, yy, S.gutterW, rh2);
      } else if (r2 % 2 === 1) {
        cctx.fillStyle = theme.bgRow;
        cctx.fillRect(0, yy, S.gutterW, rh2);
      }
      if (r2 === S.hoverRow) { cctx.fillStyle = theme.sel; cctx.fillRect(0, yy, S.gutterW, rh2); }

      cctx.textAlign = 'left';
      cctx.save();
      cctx.beginPath();
      cctx.rect(4, yy, S.gutterW - 10, rh2);
      cctx.clip();
      if (isGroup) {
        var caret = S.collapsed.has(row2.group) ? '▸' : '▾';
        var label = caret + '  ' + D.groups[row2.group];
        cctx.fillStyle = theme.fg;
        cctx.font = '600 11.5px -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif';
        cctx.fillText(label, 6, yy + rh2 / 2);
        var labelW = cctx.measureText(label).width;
        cctx.fillStyle = theme.fgDim;
        cctx.font = '10px -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif';
        cctx.fillText(row2.members.length + (row2.members.length === 1 ? ' thread' : ' threads'),
          10 + labelW, yy + rh2 / 2);
        cctx.font = '11px -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif';
      } else {
        cctx.fillStyle = r2 === S.hoverRow ? theme.fg : theme.fgDim;
        cctx.fillText(row2.thread.name, S.grouped && hasGroups() ? 18 : 6, yy + rh2 / 2);
      }
      cctx.restore();

      if (isGroup) {
        cctx.strokeStyle = theme.borderStrong;
        cctx.beginPath();
        cctx.moveTo(0, yy + 0.5);
        cctx.lineTo(w, yy + 0.5);
        cctx.stroke();
      }
    }

    cctx.strokeStyle = theme.borderStrong;
    cctx.beginPath();
    cctx.moveTo(S.gutterW + 0.5, 0);
    cctx.lineTo(S.gutterW + 0.5, h);
    cctx.stroke();

    if (S.drag && S.drag.select) {
      var x0 = Math.min(S.drag.x0, S.drag.x1), x1 = Math.max(S.drag.x0, S.drag.x1);
      cctx.fillStyle = theme.sel;
      cctx.fillRect(x0, 0, x1 - x0, h);
      cctx.strokeStyle = theme.accent;
      cctx.strokeRect(x0 + 0.5, 0.5, x1 - x0 - 1, h - 1);
      drawSelectionLabel(x0, x1, h);
    }

    if (S.rows.length === 0) {
      cctx.fillStyle = theme.fgDim;
      cctx.textAlign = 'center';
      cctx.fillText('no threads match the current filter', S.gutterW + plotW / 2, 30);
    }
  }

  /** Span and duration of the range being dragged out, pinned to the top of the selection. */
  function drawSelectionLabel(x0, x1, h) {
    if (x1 - x0 < 4) {
      return;
    }
    var a = xToTime(x0), b = xToTime(x1);
    var label = fmtDur(b - a) + '   ' + fmtTime(a) + ' → ' + fmtTime(b);
    cctx.font = '11px -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif';
    cctx.textBaseline = 'middle';
    cctx.textAlign = 'center';
    var w = cctx.measureText(label).width + 12;
    var cx = Math.min(Math.max((x0 + x1) / 2, S.gutterW + w / 2), chart.width / dpr - w / 2);
    cctx.fillStyle = theme.accent;
    cctx.fillRect(cx - w / 2, 4, w, 18);
    cctx.fillStyle = '#ffffff';
    cctx.fillText(label, cx, 13);
  }

  function drawRow(t, y, rowH) {
    var t0 = S.t0, t1 = S.t1, scale = plotW / (t1 - t0);
    var barY = y + 1, barH = rowH - 2;
    var k = firstSegmentAfter(t, t0);

    var bx = -1, bw = 0, bstate = -1;   // sub-pixel accumulator

    function flush() {
      if (bx >= 0) {
        cctx.fillStyle = stateColors[bstate];
        cctx.fillRect(bx, barY, 1, barH);
        bx = -1; bw = 0; bstate = -1;
      }
    }

    for (; k < t.n; k++) {
      var s = t.start[k];
      if (s >= t1) { break; }
      var st = t.state[k];
      if (S.hidden.has(st)) { continue; }
      var a = s < t0 ? t0 : s;
      var b = t.end[k] > t1 ? t1 : t.end[k];
      if (b <= a) { continue; }
      var x0 = S.gutterW + (a - t0) * scale;
      var x1 = S.gutterW + (b - t0) * scale;
      var wpx = x1 - x0;
      if (wpx >= 1) {
        flush();
        cctx.fillStyle = stateColors[st];
        cctx.fillRect(x0, barY, wpx, barH);
      } else {
        var px = x0 | 0;
        if (px === bx) {
          if (wpx > bw) { bw = wpx; bstate = st; }
        } else {
          flush();
          bx = px; bw = wpx; bstate = st;
        }
      }
    }
    flush();
  }

  var bandBuf = null;

  /**
   * A group's summary band: for every pixel column, how the group's threads split across states.
   * Bar height is the share of the group that was observed at all, so an idle pool shows a thin
   * line and a pool where every thread is busy fills the row.
   */
  function drawGroupBand(members, y, rowH) {
    var t0 = S.t0, t1 = S.t1, span = t1 - t0;
    var cols = Math.max(1, Math.round(plotW));
    var ns = stateNames.length;
    if (!bandBuf || bandBuf.length < cols * ns) { bandBuf = new Float64Array(cols * ns); }
    bandBuf.fill(0, 0, cols * ns);

    var colUs = span / cols;
    for (var i = 0; i < members.length; i++) {
      var t = members[i];
      for (var k = firstSegmentAfter(t, t0); k < t.n; k++) {
        var s = t.start[k];
        if (s >= t1) { break; }
        var st = t.state[k];
        if (S.hidden.has(st)) { continue; }
        var a = s < t0 ? t0 : s;
        var b = t.end[k] > t1 ? t1 : t.end[k];
        if (b <= a) { continue; }
        var c0 = (a - t0) / colUs | 0;
        var c1 = (b - t0) / colUs | 0;
        if (c0 < 0) { c0 = 0; }
        if (c1 >= cols) { c1 = cols - 1; }
        if (c0 === c1) {
          bandBuf[c0 * ns + st] += b - a;
        } else {
          for (var c = c0; c <= c1; c++) {
            var lo = Math.max(a, t0 + c * colUs), hi = Math.min(b, t0 + (c + 1) * colUs);
            if (hi > lo) { bandBuf[c * ns + st] += hi - lo; }
          }
        }
      }
    }

    var full = members.length * colUs;
    var barTop = y + 1, barH = rowH - 2;
    for (var col = 0; col < cols; col++) {
      var base = col * ns;
      var total = 0;
      for (var q = 0; q < ns; q++) { total += bandBuf[base + q]; }
      if (total <= 0) { continue; }
      var height = Math.min(1, total / full) * barH;
      var yy = barTop + barH - height;
      for (var sIdx = 0; sIdx < ns; sIdx++) {
        var v = bandBuf[base + sIdx];
        if (v <= 0) { continue; }
        var hh = v / total * height;
        cctx.fillStyle = stateColors[sIdx];
        cctx.fillRect(S.gutterW + col, yy, 1, hh);
        yy += hh;
      }
    }
  }

  // -------------------------------------------------------------- overview

  function buildOverview(cols) {
    var ns = stateNames.length;
    var acc = new Float64Array(cols * ns);
    var colUs = S.span / cols;
    for (var r = 0; r < S.visibleThreads.length; r++) {
      var t = S.visibleThreads[r];
      for (var k = 0; k < t.n; k++) {
        var st = t.state[k];
        if (S.hidden.has(st)) { continue; }
        var a = t.start[k], b = t.end[k];
        var c0 = Math.max(0, Math.min(cols - 1, (a / colUs) | 0));
        var c1 = Math.max(0, Math.min(cols - 1, (b / colUs) | 0));
        if (c0 === c1) {
          acc[c0 * ns + st] += b - a;
        } else {
          for (var c = c0; c <= c1; c++) {
            var lo = Math.max(a, c * colUs), hi = Math.min(b, (c + 1) * colUs);
            if (hi > lo) { acc[c * ns + st] += hi - lo; }
          }
        }
      }
    }
    var max = 0;
    for (var i = 0; i < cols; i++) {
      var tot = 0;
      for (var j = 0; j < ns; j++) { tot += acc[i * ns + j]; }
      if (tot > max) { max = tot; }
    }
    return { acc: acc, cols: cols, ns: ns, max: max || 1 };
  }

  function drawOverview() {
    var w = overview.width / dpr, h = 42;
    octx.clearRect(0, 0, w, h);
    octx.fillStyle = theme.bgAlt;
    octx.fillRect(0, 0, w, h);

    var cols = Math.max(1, Math.round(w));
    if (!overviewCache || overviewCache.cols !== cols) { overviewCache = buildOverview(cols); }
    var o = overviewCache, ns = o.ns;

    for (var c = 0; c < cols; c++) {
      var y = h;
      for (var s = 0; s < ns; s++) {
        var v = o.acc[c * ns + s];
        if (v <= 0) { continue; }
        var hh = v / o.max * (h - 2);
        octx.fillStyle = stateColors[s];
        octx.fillRect(c, y - hh, 1, hh);
        y -= hh;
      }
    }

    var vx0 = S.t0 / S.span * w, vx1 = S.t1 / S.span * w;
    octx.fillStyle = 'rgba(128,128,128,.28)';
    octx.fillRect(0, 0, vx0, h);
    octx.fillRect(vx1, 0, w - vx1, h);
    octx.strokeStyle = theme.accent;
    octx.lineWidth = 1;
    octx.strokeRect(vx0 + 0.5, 0.5, Math.max(1, vx1 - vx0 - 1), h - 1);
  }

  // ---------------------------------------------------------------- legend

  /** Repaints the on/off state of the chips without rebuilding them. */
  function syncLegend() {
    var chips = $('legend').querySelectorAll('.chip');
    for (var i = 0; i < chips.length; i++) {
      chips[i].classList.toggle('off', S.hidden.has(+chips[i].dataset.s));
    }
  }

  function setAllHidden(hidden) {
    S.hidden.clear();
    if (hidden) {
      for (var i = 0; i < stateNames.length; i++) { S.hidden.add(i); }
    }
    syncLegend();
    overviewCache = null;
    requestDraw();
  }

  /** Shows only one state; running it again on the same state brings everything back. */
  function soloState(idx) {
    var alreadySolo = S.hidden.size === stateNames.length - 1 && !S.hidden.has(idx);
    setAllHidden(!alreadySolo);
    if (!alreadySolo) { S.hidden.delete(idx); syncLegend(); requestDraw(); }
  }

  function renderLegend() {
    var totals = new Float64Array(stateNames.length);
    var grand = 0;
    for (var r = 0; r < S.visibleThreads.length; r++) {
      var st = S.visibleThreads[r].stateTime;
      for (var i = 0; i < st.length; i++) { totals[i] += st[i]; grand += st[i]; }
    }
    var order = [];
    for (var j = 0; j < stateNames.length; j++) { order.push(j); }
    order.sort(function (a, b) { return totals[b] - totals[a]; });

    var html = '';
    for (var q = 0; q < order.length; q++) {
      var idx = order[q];
      if (totals[idx] === 0 && D.states[idx].k !== 'rule') { continue; }
      var pct = grand ? (100 * totals[idx] / grand) : 0;
      html += '<span class="chip' + (S.hidden.has(idx) ? ' off' : '') + '" data-s="' + idx + '"' +
        ' title="' + esc((D.states[idx].d || D.states[idx].n) + ' — ' + fmtDur(totals[idx])
          + ' of thread time\nclick to hide, alt/shift-click to show only this one') + '">' +
        '<span class="sw" style="background:' + esc(stateColors[idx]) + '"></span>' +
        esc(stateNames[idx]) + ' <span class="pc">' + pct.toFixed(1) + '%</span></span>';
    }
    $('legend').innerHTML = html;
  }

  // ------------------------------------------------------------- hit testing

  function rowAt(clientY) {
    var rect = chart.getBoundingClientRect();
    return rowIndexAt(clientY - rect.top + S.scrollTop);
  }

  /** Longest segment overlapping the one-pixel time slice under x. */
  function segmentAt(t, x) {
    var ta = xToTime(x - 0.5), tb = xToTime(x + 0.5);
    var k = firstSegmentAfter(t, ta);
    var best = -1, bestLen = 0;
    for (; k < t.n && t.start[k] < tb; k++) {
      if (S.hidden.has(t.state[k])) { continue; }
      var len = Math.min(t.end[k], tb) - Math.max(t.start[k], ta);
      if (len > bestLen) { bestLen = len; best = k; }
    }
    if (best < 0) {
      // nothing in the slice: fall back to a segment containing the exact time
      var mid = xToTime(x);
      var i = firstSegmentAfter(t, mid);
      if (i < t.n && t.start[i] <= mid && t.end[i] > mid) { best = i; }
    }
    return best;
  }

  // ---------------------------------------------------------------- tooltip

  var tip = $('tooltip');

  /** Frame indices the matched sequence landed on, as a lookup. */
  function hitsOf(stackIdx) {
    var m = D.stackMatch[stackIdx];
    var set = Object.create(null);
    if (m) { for (var i = 0; i < m.length; i++) { set[m[i]] = true; } }
    return set;
  }

  function stackHtml(stackIdx, limit) {
    var frames = D.stacks[stackIdx] || [];
    var m = D.stackMatch[stackIdx] || [];
    var hits = hitsOf(stackIdx);
    var out = '';
    // always show far enough down to reveal the deepest frame of a matched sequence
    var n = Math.min(frames.length, m.length ? Math.max(limit, m[m.length - 1] + 1) : limit);
    for (var i = 0; i < n; i++) {
      out += '<div' + (hits[i] ? ' class="hit"' : '') + '>' + esc(D.frames[frames[i]]) + '</div>';
    }
    if (frames.length > n) {
      out += '<div class="more">… ' + (frames.length - n) + ' more frames (click to pin)</div>';
    }
    if (!frames.length) { out += '<div class="more">no stack recorded</div>'; }
    return out;
  }

  function showTip(evt, t, k) {
    var st = t.state[k];
    var html = '<div class="tth">' + esc(t.name) + '</div>' +
      '<div class="ttstate"><span class="sw" style="background:' + esc(stateColors[st]) + '"></span>' +
      esc(stateNames[st]) + '</div>' +
      '<div class="ttmeta">' + fmtTime(t.start[k]) + ' → ' + fmtTime(t.end[k]) +
      '  ·  ' + fmtDur(t.end[k] - t.start[k]) +
      '  ·  ' + fmtCount(t.samples[k]) + (t.samples[k] === 1 ? ' sample' : ' samples') +
      (D.meta.startEpochMs ? '  ·  ' + fmtWall(t.start[k]) : '') + '</div>' +
      '<div class="stack">' + stackHtml(t.stack[k], 24) + '</div>';
    tip.innerHTML = html;
    tip.hidden = false;
    var pad = 14;
    var w = tip.offsetWidth, h = tip.offsetHeight;
    var x = evt.clientX + pad, y = evt.clientY + pad;
    if (x + w > window.innerWidth - 4) { x = evt.clientX - w - pad; }
    if (y + h > window.innerHeight - 4) { y = Math.max(4, evt.clientY - h - pad); }
    tip.style.left = x + 'px';
    tip.style.top = y + 'px';
  }

  function hideTip() { tip.hidden = true; }

  /** Group header tooltip: how the group's total thread time splits across states. */
  function showGroupTip(evt, row) {
    var totals = new Float64Array(stateNames.length);
    var grand = 0, samples = 0;
    for (var i = 0; i < row.members.length; i++) {
      var m = row.members[i];
      samples += m.sampleCount;
      for (var s = 0; s < totals.length; s++) { totals[s] += m.stateTime[s]; grand += m.stateTime[s]; }
    }
    var order = [];
    for (var q = 0; q < totals.length; q++) { order.push(q); }
    order.sort(function (a, b) { return totals[b] - totals[a]; });

    var rows = '';
    for (var k = 0; k < order.length && k < 8; k++) {
      var idx = order[k];
      if (totals[idx] <= 0) { break; }
      rows += '<div><span class="sw" style="background:' + esc(stateColors[idx]) + '"></span> ' +
        esc(stateNames[idx]) + ' — ' + (100 * totals[idx] / grand).toFixed(1) + '%  ' +
        fmtDur(totals[idx]) + '</div>';
    }
    tip.innerHTML = '<div class="tth">' + esc(D.groups[row.group]) + '</div>' +
      '<div class="ttmeta">' + row.members.length + ' threads · ' + fmtCount(samples) + ' samples · ' +
      fmtDur(grand) + ' of thread time<br>' +
      'click to ' + (S.collapsed.has(row.group) ? 'expand' : 'collapse') +
      ', alt-click for all groups</div>' +
      '<div class="grouptotals">' + rows + '</div>';
    tip.hidden = false;
    var pad = 14;
    var x = evt.clientX + pad, y = evt.clientY + pad;
    if (x + tip.offsetWidth > window.innerWidth - 4) { x = evt.clientX - tip.offsetWidth - pad; }
    if (y + tip.offsetHeight > window.innerHeight - 4) { y = Math.max(4, evt.clientY - tip.offsetHeight - pad); }
    tip.style.left = x + 'px';
    tip.style.top = y + 'px';
  }

  // ---------------------------------------------------------- details panel

  function showDetails(t, k) {
    S.pinned = { t: t, k: k };
    var st = t.state[k];
    var frames = D.stacks[t.stack[k]] || [];
    var hits = hitsOf(t.stack[k]);
    $('dtitle').textContent = t.name + ' — ' + stateNames[st];
    var meta = '<div class="meta">' +
      'thread: ' + esc(t.name) +
      (t.javaId ? ' · javaId ' + t.javaId : '') + (t.osId ? ' · osId ' + t.osId : '') +
      (t.group >= 0 && D.groups && D.groups[t.group] ? ' · group ' + esc(D.groups[t.group]) : '') + '<br>' +
      'state: ' + esc(stateNames[st]) + (D.states[st].d ? ' — ' + esc(D.states[st].d) : '') + '<br>' +
      'window: ' + fmtTime(t.start[k]) + ' → ' + fmtTime(t.end[k]) +
      ' (' + fmtDur(t.end[k] - t.start[k]) + ')' +
      (D.meta.startEpochMs ? ' · wall ' + fmtWall(t.start[k]) : '') + '<br>' +
      'samples in segment: ' + fmtCount(t.samples[k]) +
      '</div>';
    var body = '';
    for (var i = 0; i < frames.length; i++) {
      body += '<div class="frame' + (hits[i] ? ' hit' : '') + '"><span class="idx">' +
        String(i).padStart(3, ' ') + '  </span>' + esc(D.frames[frames[i]]) + '</div>';
    }
    if (!frames.length) { body = '<div class="meta">no stack recorded for this sample</div>'; }
    $('dbody').innerHTML = meta + body;
    $('details').hidden = false;
  }

  function closeDetails() {
    $('details').hidden = true;
    S.pinned = null;
  }

  // ---------------------------------------------------------- config panel

  function openConfig() {
    $('ctitle').textContent = 'Configuration — ' + (D.meta.configSource || 'built-in default');
    $('cbody').textContent = D.meta.configText || '(not recorded)';
    $('config').hidden = false;
  }

  function closeConfig() {
    $('config').hidden = true;
  }

  function pinnedStackText() {
    if (!S.pinned) { return ''; }
    var t = S.pinned.t, k = S.pinned.k;
    var frames = D.stacks[t.stack[k]] || [];
    var lines = [t.name + '  [' + stateNames[t.state[k]] + ']  ' +
      fmtTime(t.start[k]) + ' → ' + fmtTime(t.end[k])];
    for (var i = 0; i < frames.length; i++) { lines.push('  at ' + D.frames[frames[i]]); }
    return lines.join('\n');
  }

  // ------------------------------------------------------------------- zoom

  function clampView() {
    var minSpan = Math.max(1, S.span / 5e6);
    if (S.t1 - S.t0 < minSpan) {
      var mid = (S.t0 + S.t1) / 2;
      S.t0 = mid - minSpan / 2;
      S.t1 = mid + minSpan / 2;
    }
    if (S.t1 - S.t0 > S.span) { S.t0 = 0; S.t1 = S.span; }
    if (S.t0 < 0) { S.t1 -= S.t0; S.t0 = 0; }
    if (S.t1 > S.span) { S.t0 -= (S.t1 - S.span); S.t1 = S.span; }
    if (S.t0 < 0) { S.t0 = 0; }
  }

  function zoomAt(anchorTime, factor) {
    var left = anchorTime - S.t0, right = S.t1 - anchorTime;
    S.t0 = anchorTime - left * factor;
    S.t1 = anchorTime + right * factor;
    clampView();
    afterViewChange();
  }

  function panBy(dt) {
    S.t0 += dt;
    S.t1 += dt;
    clampView();
    afterViewChange();
  }

  function resetZoom() {
    S.t0 = 0;
    S.t1 = S.span;
    afterViewChange();
  }

  function afterViewChange() {
    if (S.activeOnly) { rebuildRows(); } else { updateStatus(); }
    requestDraw();
  }

  function updateStatus() {
    var visible = S.visibleThreads.length;
    $('statusLeft').textContent =
      'view ' + fmtTime(S.t0) + ' → ' + fmtTime(S.t1) + ' (' + fmtDur(S.t1 - S.t0) + ')' +
      '  ·  ' + fmtCount(visible) + ' of ' + fmtCount(threads.length) + ' threads' +
      '  ·  zoom ' + (S.span / (S.t1 - S.t0)).toFixed(1) + '×';
  }

  // ------------------------------------------------------------------ events

  function bind() {
    window.addEventListener('resize', function () {
      if (relayout()) { rebuildRows(); requestDraw(); }
    });

    // The legend and header can change height after the first layout pass (and the user may
    // resize panes), so keep the canvas in step with its container.
    if (window.ResizeObserver) {
      new ResizeObserver(function () {
        if (relayout()) { rebuildRows(); requestDraw(); }
      }).observe(scroller);
    }

    if (window.matchMedia) {
      var mq = window.matchMedia('(prefers-color-scheme: dark)');
      var onTheme = function () { readTheme(); requestDraw(); };
      if (mq.addEventListener) { mq.addEventListener('change', onTheme); }
      else if (mq.addListener) { mq.addListener(onTheme); }
    }

    scroller.addEventListener('scroll', function () {
      S.scrollTop = scroller.scrollTop;
      requestDraw();
    }, { passive: true });

    chart.addEventListener('wheel', function (e) {
      if (e.ctrlKey || e.metaKey || e.altKey) {
        e.preventDefault();
        var rect = chart.getBoundingClientRect();
        var x = e.clientX - rect.left;
        if (x < S.gutterW) { x = S.gutterW; }
        zoomAt(xToTime(x), Math.exp(e.deltaY * 0.002));
      } else if (e.shiftKey) {
        e.preventDefault();
        panBy((e.deltaY + e.deltaX) * (S.t1 - S.t0) / plotW);
      }
      // plain wheel keeps its default meaning: scroll the thread list
    }, { passive: false });

    chart.addEventListener('mousedown', function (e) {
      if (e.button !== 0) { return; }
      var rect = chart.getBoundingClientRect();
      var x = e.clientX - rect.left;
      if (x < S.gutterW) { return; }
      // Dragging across the rows selects a time range, the same gesture as on the overview
      // strip. Panning is the modified gesture, since the wheel, the arrow keys and the
      // overview all pan already.
      S.drag = {
        select: !e.shiftKey, x0: x, x1: x,
        startClientX: e.clientX, startClientY: e.clientY,
        t0: S.t0, t1: S.t1, scrollTop: scroller.scrollTop, moved: false
      };
      if (e.shiftKey) { chart.classList.add('panning'); }
      hideTip();
      e.preventDefault();
    });

    window.addEventListener('mousemove', function (e) {
      if (!S.drag) { return; }
      var rect = chart.getBoundingClientRect();
      var x = e.clientX - rect.left;
      if (Math.abs(e.clientX - S.drag.startClientX) > 2 || Math.abs(e.clientY - S.drag.startClientY) > 2) {
        S.drag.moved = true;
      }
      if (S.drag.select) {
        S.drag.x1 = Math.max(S.gutterW, Math.min(rect.width, x));
      } else {
        var dt = (S.drag.startClientX - e.clientX) * (S.drag.t1 - S.drag.t0) / plotW;
        S.t0 = S.drag.t0 + dt;
        S.t1 = S.drag.t1 + dt;
        clampView();
        scroller.scrollTop = S.drag.scrollTop - (e.clientY - S.drag.startClientY);
        updateStatus();
      }
      requestDraw();
    });

    window.addEventListener('mouseup', function () {
      if (!S.drag) { return; }
      var d = S.drag;
      S.drag = null;
      chart.classList.remove('panning');
      if (d.select && Math.abs(d.x1 - d.x0) > 3) {
        var a = xToTime(Math.min(d.x0, d.x1)), b = xToTime(Math.max(d.x0, d.x1));
        S.t0 = a; S.t1 = b;
        clampView();
        afterViewChange();
      } else if (d.moved && S.activeOnly) {
        rebuildRows();
      }
      requestDraw();
    });

    chart.addEventListener('mousemove', function (e) {
      if (S.drag) { return; }
      var rect = chart.getBoundingClientRect();
      var x = e.clientX - rect.left;
      var r = rowAt(e.clientY);
      if (r !== S.hoverRow) { S.hoverRow = r; requestDraw(); }
      // signal that dragging here selects a range
      var wantCursor = x >= S.gutterW ? 'crosshair' : 'default';
      if (chart.style.cursor !== wantCursor) { chart.style.cursor = wantCursor; }
      if (r < 0) { hideTip(); return; }
      var row = S.rows[r];
      if (row.group !== undefined) {
        showGroupTip(e, row);
        return;
      }
      if (x < S.gutterW) { hideTip(); return; }
      var k = segmentAt(row.thread, x);
      if (k < 0) { hideTip(); return; }
      showTip(e, row.thread, k);
    });

    chart.addEventListener('mouseleave', function () {
      hideTip();
      if (S.hoverRow !== -1) { S.hoverRow = -1; requestDraw(); }
    });

    chart.addEventListener('click', function (e) {
      var rect = chart.getBoundingClientRect();
      var x = e.clientX - rect.left;
      var r = rowAt(e.clientY);
      if (r < 0) { return; }
      var row = S.rows[r];
      if (row.group !== undefined) {
        if (e.altKey || e.shiftKey) {
          // alt-click a header to collapse or expand every group at once
          var expandAll = S.collapsed.size > 0;
          S.collapsed.clear();
          if (!expandAll) {
            for (var g = 0; g < D.groups.length; g++) { S.collapsed.add(g); }
          }
        } else if (S.collapsed.has(row.group)) {
          S.collapsed.delete(row.group);
        } else {
          S.collapsed.add(row.group);
        }
        hideTip();
        rebuildRows();
        requestDraw();
        return;
      }
      if (x < S.gutterW) { return; }
      var k = segmentAt(row.thread, x);
      if (k >= 0) { showDetails(row.thread, k); }
    });

    chart.addEventListener('dblclick', function (e) { e.preventDefault(); resetZoom(); });

    // overview: click to centre, drag to select a range
    overview.addEventListener('mousedown', function (e) {
      var rect = overview.getBoundingClientRect();
      S.ovDrag = { x0: e.clientX - rect.left, x1: e.clientX - rect.left };
      e.preventDefault();
    });

    window.addEventListener('mousemove', function (e) {
      if (!S.ovDrag) { return; }
      var rect = overview.getBoundingClientRect();
      S.ovDrag.x1 = Math.max(0, Math.min(rect.width, e.clientX - rect.left));
      var w = rect.width;
      var a = Math.min(S.ovDrag.x0, S.ovDrag.x1) / w * S.span;
      var b = Math.max(S.ovDrag.x0, S.ovDrag.x1) / w * S.span;
      if (b - a > S.span / 5000) {
        S.t0 = a; S.t1 = b;
        clampView();
        updateStatus();
        requestDraw();
      }
    });

    window.addEventListener('mouseup', function (e) {
      if (!S.ovDrag) { return; }
      var d = S.ovDrag;
      S.ovDrag = null;
      if (Math.abs(d.x1 - d.x0) <= 3) {
        var rect = overview.getBoundingClientRect();
        var centre = d.x0 / rect.width * S.span;
        var half = (S.t1 - S.t0) / 2;
        S.t0 = centre - half;
        S.t1 = centre + half;
        clampView();
      }
      afterViewChange();
    });

    $('legend').addEventListener('click', function (e) {
      var chip = e.target.closest('.chip');
      if (!chip) { return; }
      var idx = +chip.dataset.s;
      if (e.altKey || e.shiftKey) {
        soloState(idx);
        return;
      }
      if (S.hidden.has(idx)) { S.hidden.delete(idx); } else { S.hidden.add(idx); }
      // toggle in place - re-rendering the legend would replace the element under the cursor
      chip.classList.toggle('off', S.hidden.has(idx));
      overviewCache = null;
      requestDraw();
    });

    $('legendAll').addEventListener('click', function () { setAllHidden(false); });
    $('legendNone').addEventListener('click', function () { setAllHidden(true); });

    var filterTimer = null;
    $('filter').addEventListener('input', function (e) {
      clearTimeout(filterTimer);
      var v = e.target.value;
      filterTimer = setTimeout(function () {
        S.filter = v;
        rebuildRows();
        requestDraw();
      }, 120);
    });

    $('sort').addEventListener('change', function (e) {
      S.sort = e.target.value;
      rebuildRows();
      requestDraw();
    });

    $('activeOnly').addEventListener('change', function (e) {
      S.activeOnly = e.target.checked;
      rebuildRows();
      requestDraw();
    });

    $('grouped').addEventListener('change', function (e) {
      S.grouped = e.target.checked;
      rebuildRows();
      requestDraw();
    });


    $('zoomIn').addEventListener('click', function () { zoomAt((S.t0 + S.t1) / 2, 1 / 1.6); });
    $('zoomOut').addEventListener('click', function () { zoomAt((S.t0 + S.t1) / 2, 1.6); });
    $('reset').addEventListener('click', resetZoom);
    $('showConfig').addEventListener('click', openConfig);
    $('cclose').addEventListener('click', closeConfig);
    $('ccopy').addEventListener('click', function () {
      copyText(D.meta.configText || '', $('ccopy'), 'Copy config');
    });
    $('dclose').addEventListener('click', closeDetails);
    $('dcopy').addEventListener('click', function () {
      copyText(pinnedStackText(), $('dcopy'), 'Copy stack');
    });

    document.addEventListener('keydown', function (e) {
      var tag = (e.target.tagName || '').toLowerCase();
      if (tag === 'input' || tag === 'select' || tag === 'textarea') {
        if (e.key === 'Escape') { e.target.blur(); }
        return;
      }
      var step = (S.t1 - S.t0) * 0.15;
      switch (e.key) {
        case 'w': case 'W': case '+': case '=': zoomAt((S.t0 + S.t1) / 2, 1 / 1.5); break;
        case 's': case 'S': case '-': case '_': zoomAt((S.t0 + S.t1) / 2, 1.5); break;
        case 'a': case 'A': case 'ArrowLeft': panBy(-step); break;
        case 'd': case 'D': case 'ArrowRight': panBy(step); break;
        case '0': resetZoom(); break;
        case 'f': case 'F': e.preventDefault(); $('filter').focus(); $('filter').select(); break;
        case 'n': case 'N': setAllHidden(S.hidden.size !== stateNames.length); break;
        case 'g': case 'G':
          if (hasGroups()) {
            S.grouped = !S.grouped;
            $('grouped').checked = S.grouped;
            rebuildRows();
            requestDraw();
          }
          break;
        case 'c': case 'C': if ($('config').hidden) { openConfig(); } else { closeConfig(); } break;
        case 'Escape': closeConfig(); closeDetails(); break;
        default: return;
      }
      e.preventDefault();
    });
  }

  function copyText(text, button, label) {
    var done = function () {
      button.textContent = 'Copied';
      setTimeout(function () { button.textContent = label; }, 1200);
    };
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(text).then(done, function () { fallbackCopy(text); done(); });
    } else {
      fallbackCopy(text);
      done();
    }
  }

  function fallbackCopy(text) {
    var ta = document.createElement('textarea');
    ta.value = text;
    ta.style.position = 'fixed';
    ta.style.opacity = '0';
    document.body.appendChild(ta);
    ta.select();
    try { document.execCommand('copy'); } catch (e) { /* ignore */ }
    document.body.removeChild(ta);
  }

  // -------------------------------------------------------------------- init

  function renderHeader() {
    var m = D.meta;
    $('meta').innerHTML =
      '<span><b>' + esc(m.source) + '</b></span>' +
      '<span>' + fmtDur(m.durationUs) + '</span>' +
      '<span>' + fmtCount(m.threadCount) + ' threads</span>' +
      '<span>' + fmtCount(m.sampleCount) + ' samples</span>' +
      '<span>' + fmtCount(m.segmentCount) + ' segments</span>' +
      '<span>' + esc(m.eventTypes.join(', ')) + '</span>' +
      '<span>~' + fmtDur(m.sampleIntervalUs) + '/sample</span>' +
      '<span>match: ' + esc(m.matchStrategy) + '</span>';

    var sel = $('sort');
    var opts = '<option value="name">sort: name</option>' +
      '<option value="samples">sort: samples</option>' +
      '<option value="first">sort: first sample</option>';
    var order = D.states.map(function (s, i) { return i; })
      .sort(function (a, b) { return D.states[b].t - D.states[a].t; });
    for (var i = 0; i < order.length; i++) {
      if (D.states[order[i]].t === 0) { continue; }
      opts += '<option value="state:' + order[i] + '">sort: % ' + esc(D.states[order[i]].n) + '</option>';
    }
    sel.innerHTML = opts;
    sel.value = S.sort;
  }

  function boot() {
    readTheme();
    renderHeader();
    if (!hasGroups()) {
      S.grouped = false;
      $('groupWrap').hidden = true;
    } else {
      // Start folded. A few hundred threads is unreadable as rows, whereas the summary bands
      // show the shape of the whole node at once; expand the group you care about from there.
      for (var g = 0; g < D.groups.length; g++) {
        S.collapsed.add(g);
      }
    }
    $('app').hidden = false;
    relayout();
    rebuildRows();
    relayout();          // the legend has real height now, so the canvas needs re-fitting
    rebuildRows();
    requestDraw();
    $('boot').style.display = 'none';
    bind();
  }

  loadData().then(function (model) {
    try {
      decode(model);
      boot();
    } catch (err) {
      fail(err);
    }
  }, fail);

  function fail(err) {
    var el = $('bootmsg');
    el.className = 'err';
    el.textContent = 'Could not render the timeline:\n\n' + (err && err.message ? err.message : String(err));
    var sp = document.querySelector('.spinner');
    if (sp) { sp.style.display = 'none'; }
    if (window.console) { console.error(err); }
  }
})();
