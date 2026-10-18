// Shared map + list helpers for mode pages (iframes). Data, player and shared state live in the host (core.js).
(function () {
  const H = window.parent.Summit;
  const DOT = { size: 6, fade: 0.2 };
  const HALO = { size: 13, opacity: 0.14 };
  const d = () => H.data;
  const col = c => H.leafColor(c);  // family hue, sub-cluster shade

  // ---- data views ----
  const layout = space => d().spaces[space];
  const BPM_SCALE = [[0, '#5aa9ff'], [0.45, '#00f0ff'], [0.75, '#fcee0a'], [1, '#ff2a6d']];
  const TAG_SCALE = [[0, '#1a2a3a'], [0.55, '#0f7a5a'], [1, H.UI.confirmed]];
  function colors(mode) {
    const T = d().tracks;
    if (mode === 'bpm') return { color: T.map(t => t.bpm || 120), colorscale: BPM_SCALE, cmin: 100, cmax: 140 };
    if (mode === 'tagged') return { color: T.map(t => (t.confirmed.length ? H.UI.confirmed : H.UI.untagged)) };
    if (mode && mode.startsWith('tag:')) {
      const tag = mode.slice(4), s = d().scores[tag] || [];
      return { color: T.map(t => (t.confirmed.includes(tag) ? 1 : s[t.i] || 0)), colorscale: TAG_SCALE, cmin: 0, cmax: 1 };
    }
    if (mode === 'family') return { color: T.map(t => H.familyHue(t.family)) };
    return { color: T.map(t => col(t.cluster)) };
  }
  let fontReady = false;
  const fontsReady = Promise.race([document.fonts.load('800 9px Orbitron'), new Promise(r => setTimeout(r, 2000))]).then(() => (fontReady = true));
  // Labels follow zoom: family names when zoomed out; inside split families, sub-cluster names when zoomed in.
  const centroids = (L, key) => {
    const n = {}, sx = {}, sy = {};
    L[key].forEach((g, j) => { sx[g] = (sx[g] || 0) + L.x[j]; sy[g] = (sy[g] || 0) + L.y[j]; n[g] = (n[g] || 0) + 1; });
    return Object.keys(n).map(Number).map(g => ({ g, x: sx[g] / n[g], y: sy[g] / n[g] }));
  };
  const tag = (x, y, text, color, size) => ({
    x, y, showarrow: false, text: text.toUpperCase() + (fontReady ? '\u200a' : ''),  // suffix busts Plotly's text-size cache once the font loads
    font: { size, color, family: 'Orbitron, sans-serif' }, bgcolor: 'rgba(5,4,10,.85)', bordercolor: color, borderwidth: 1, borderpad: size > 9 ? 5 : 3,
  });
  // Genres = family labels, subgenres = sub-cluster labels. Both on: follow zoom. One on: that level at every zoom.
  function labels(space, level = 'family') {
    const L = layout(space), fc = f => (H.color === 'family' ? H.familyHue(f) : H.familyColor(f)), show = H.labelShow;
    if (!show.fam && !show.sub) return [];
    if (!show.sub) level = 'family';
    if (level === 'family' && show.fam) return centroids(L, 'f').map(p => tag(p.x, p.y, H.familyName(p.g), fc(p.g), 10));
    const leaves = centroids(L, 'c').filter(p => H.isSplit(H.familyOf(p.g))).map(p => tag(p.x, p.y, H.leafName(p.g), col(p.g), 8));
    // Unsplit families are their own subgenre: label them by family name (at subgenre size when genres are hidden).
    const whole = centroids(L, 'f').filter(p => !H.isSplit(p.g)).map(p => tag(p.x, p.y, H.familyName(p.g), fc(p.g), show.fam ? 10 : 8));
    return whole.concat(leaves);
  }
  // Zoomed in past half the full width → sub-cluster labels.
  const levelOf = div => {
    const xa = div._fullLayout && div._fullLayout.xaxis;
    if (!xa || !div._fullSpan) return 'family';
    return Math.abs(xa.range[1] - xa.range[0]) < div._fullSpan * 0.5 ? 'leaf' : 'family';
  };
  function annotate(div, extra) {
    if (extra !== undefined) div._extra = extra;
    div._level = levelOf(div);
    return { annotations: labels(H.space, div._level).concat(div._extra || []) };
  }
  function nearest(x, y, space) {
    const L = layout(space); let best = 0, bd = Infinity;
    for (let j = 0; j < L.x.length; j++) { const e = (L.x[j] - x) ** 2 + (L.y[j] - y) ** 2; if (e < bd) { bd = e; best = j; } }
    return best;
  }

  // ---- map: traces 0 tracks, 1 rings, 2 route glow, 3 route, 4 now playing, 5 halo ----
  const maps = [];
  let lastInteract = 0;
  function plot(div, { space, colorMode, config = {} }) {
    const L = layout(space), C = colors(colorMode), T = d().tracks;
    const main = {
      type: 'scattergl', mode: 'markers', x: L.x, y: L.y,
      text: T.map(t => `<b>${H.esc(t.title)}</b><br>${H.esc(t.artist)} · ${t.bpm ?? '—'} BPM · ${t.key ?? '—'}`),
      hovertemplate: '%{text}<extra></extra>',
      marker: { symbol: 'diamond', size: DOT.size, color: C.color, colorscale: C.colorscale, cmin: C.cmin, cmax: C.cmax, opacity: 1 },
    };
    const none = { type: 'scattergl', x: [], y: [], hoverinfo: 'skip' };
    const hi = { ...none, mode: 'markers', marker: { symbol: 'diamond-open', size: 14, color: H.UI.focus, line: { width: 1.5, color: H.UI.focus } } };
    const glow = { ...none, mode: 'lines', line: { color: 'rgba(255,255,255,.12)', width: 10 } };
    const path = { ...none, mode: 'lines', line: { color: H.UI.focus, width: 2 } };
    const playing = { ...none, mode: 'markers', marker: { symbol: 'diamond', size: 22, color: 'rgba(255,255,255,.15)', line: { color: H.UI.focus, width: 2 } } };
    const halo = { type: 'scattergl', mode: 'markers', x: L.x, y: L.y, hoverinfo: 'skip',
      marker: { symbol: 'diamond', size: HALO.size, color: C.color, colorscale: C.colorscale, cmin: C.cmin, cmax: C.cmax, opacity: HALO.opacity } };
    const lay = {
      paper_bgcolor: 'rgba(0,0,0,0)', plot_bgcolor: 'rgba(0,0,0,0)', margin: { l: 0, r: 0, t: 0, b: 0 },
      xaxis: { visible: false }, yaxis: { visible: false, scaleanchor: 'x' }, dragmode: 'pan', showlegend: false, hovermode: 'closest',
      annotations: labels(space, 'family'),
      hoverlabel: { bgcolor: '#05040a', bordercolor: '#00f0ff', font: { color: '#d8f8ff', size: 12, family: 'Share Tech Mono, monospace' } },
    };
    const p = Plotly.newPlot(div, [main, hi, glow, path, playing, halo], lay, { displayModeBar: false, scrollZoom: false, responsive: true, ...config });
    maps.push(div);
    wheelZoom(div);
    fontsReady.then(() => div._fullLayout && relabel(div));
    return p.then(() => {
      div._fullSpan = Math.abs(div._fullLayout.xaxis.range[1] - div._fullLayout.xaxis.range[0]);
      div.on('plotly_relayout', () => { if (levelOf(div) !== div._level) Plotly.relayout(div, annotate(div)); });
      markPlaying();
    });
  }
  // One relayout per animation frame, anchored at the cursor (Plotly redraws on every wheel event otherwise).
  function wheelZoom(div) {
    let factor = 1, anchor = null, raf = 0;
    div.addEventListener('wheel', e => {
      if (!div._fullLayout) return;
      e.preventDefault();
      lastInteract = performance.now();
      factor *= Math.exp(Math.max(-60, Math.min(60, e.deltaY)) * 0.003);
      const r = div.getBoundingClientRect();
      anchor = toData(div, e.clientX - r.left, e.clientY - r.top);
      if (raf) return;
      raf = requestAnimationFrame(() => {
        raf = 0;
        const { xaxis: xa, yaxis: ya } = div._fullLayout, f = Math.max(0.6, Math.min(1.6, factor)), [ax, ay] = anchor;
        factor = 1;
        Plotly.relayout(div, { 'xaxis.range': xa.range.map(v => ax + (v - ax) * f), 'yaxis.range': ya.range.map(v => ay + (v - ay) * f) });
      });
    }, { passive: false });
    div.addEventListener('mousedown', () => (lastInteract = performance.now()));
    div.addEventListener('mousemove', e => { if (e.buttons) lastInteract = performance.now(); });
  }
  function setSpace(div, space) {
    const L = layout(space);
    Plotly.restyle(div, { x: [L.x, L.x], y: [L.y, L.y] }, [0, 5]);
    Plotly.relayout(div, { 'xaxis.autorange': true, 'yaxis.autorange': true }).then(() => {
      div._fullSpan = Math.abs(div._fullLayout.xaxis.range[1] - div._fullLayout.xaxis.range[0]);
      Plotly.relayout(div, annotate(div));
    });
    markPlaying();
  }
  function setColor(div, mode) {
    const C = colors(mode), two = v => [v, v];
    Plotly.restyle(div, { 'marker.color': two(C.color), 'marker.colorscale': two(C.colorscale || null), 'marker.cmin': two(C.cmin), 'marker.cmax': two(C.cmax) }, [0, 5]);
    relabel(div);  // family label colours follow the mode
  }
  const xy = (idxs, space) => { const L = layout(space); return { x: [idxs.map(j => L.x[j])], y: [idxs.map(j => L.y[j])] }; };
  const highlight = (div, idxs, space) => Plotly.restyle(div, xy(idxs, space), [1]);
  const drawPath = (div, idxs, space) => { const p = xy(idxs, space); Plotly.restyle(div, { x: [p.x[0], p.x[0]], y: [p.y[0], p.y[0]] }, [2, 3]); };
  function markPlaying() {
    const i = H.player.current;
    for (const m of maps) if (m._fullLayout) Plotly.restyle(m, xy(i == null ? [] : [i], H.space), [4]);
  }
  function setOpacity(div, op) {
    const halo = Array.isArray(op) ? op.map(o => (o >= 1 ? HALO.opacity : o * HALO.opacity * 0.4)) : op * HALO.opacity;
    Plotly.restyle(div, { 'marker.opacity': [op, halo] }, [0, 5]);
  }
  function focus(div, idxs) {
    if (!idxs) { setOpacity(div, 1); return; }
    const op = new Array(d().tracks.length).fill(DOT.fade); idxs.forEach(j => (op[j] = 1));
    setOpacity(div, op);
  }
  const relabel = div => Plotly.relayout(div, annotate(div));
  // Pages add their own annotations (e.g. flow stage markers) on top of the labels; extra layout keys ride along.
  const setAnnotations = (div, extra, more = {}) => Plotly.relayout(div, { ...annotate(div, extra), ...more });
  const toPx = (div, x, y) => { const { xaxis: xa, yaxis: ya } = div._fullLayout; return [xa.l2p(x) + xa._offset, ya.l2p(y) + ya._offset]; };
  const toData = (div, px, py) => { const { xaxis: xa, yaxis: ya } = div._fullLayout; return [xa.p2l(px - xa._offset), ya.p2l(py - ya._offset)]; };

  H.on('player', markPlaying);
  H.on('breathe', s => {
    if (performance.now() - lastInteract < 600) return;
    for (const m of maps) if (m._fullLayout) Plotly.restyle(m, { 'marker.size': [s ? 28 : 20], 'marker.color': [`rgba(255,255,255,${s ? 0.3 : 0.12})`] }, [4]);
  });
  H.on('mode', () => maps.forEach(m => m._fullLayout && m.offsetParent !== null && Plotly.Plots.resize(m)));
  H.on('labels', () => maps.forEach(m => m._fullLayout && relabel(m)));

  // Ctrl-drag lasso on our own SVG overlay (Plotly treats ctrl-drag as a right click; macOS turns ctrl-click into a context menu).
  function ctrlLasso(div, onSelect, onClear) {
    const ns = 'http://www.w3.org/2000/svg', svg = document.createElementNS(ns, 'svg'), poly = document.createElementNS(ns, 'polygon');
    svg.setAttribute('class', 'lasso'); svg.appendChild(poly);
    if (getComputedStyle(div).position === 'static') div.style.position = 'relative';
    div.appendChild(svg);
    div.addEventListener('contextmenu', e => { if (e.ctrlKey) e.preventDefault(); });
    div.addEventListener('mousedown', e => {
      if (!e.ctrlKey) return;
      e.preventDefault(); e.stopPropagation();
      const r = div.getBoundingClientRect(), pts = [[e.clientX - r.left, e.clientY - r.top]];
      svg.classList.add('on');
      const move = ev => { ev.stopPropagation(); pts.push([ev.clientX - r.left, ev.clientY - r.top]); poly.setAttribute('points', pts.map(p => p.join(',')).join(' ')); };
      const up = ev => {
        ev.stopPropagation();
        document.removeEventListener('mousemove', move, true); document.removeEventListener('mouseup', up, true);
        svg.classList.remove('on'); poly.setAttribute('points', '');
        if (pts.length > 3) onSelect(inside(div, pts, H.space), ev.shiftKey);
      };
      document.addEventListener('mousemove', move, true); document.addEventListener('mouseup', up, true);
    }, true);
    window.addEventListener('keydown', e => { if (e.key === 'Escape' && onClear) onClear(); });
  }
  function inside(div, pts, space) {
    const L = layout(space), P = pts.map(([px, py]) => toData(div, px, py)), out = [];
    for (let j = 0; j < L.x.length; j++) {
      const x = L.x[j], y = L.y[j]; let inPoly = false;
      for (let a = 0, b = P.length - 1; a < P.length; b = a++) {
        if ((P[a][1] > y) !== (P[b][1] > y) && x < ((P[b][0] - P[a][0]) * (y - P[a][1])) / (P[b][1] - P[a][1]) + P[a][0]) inPoly = !inPoly;
      }
      if (inPoly) out.push(j);
    }
    return out;
  }

  // Drag handle that sets a CSS size variable; sign = -1 when dragging toward the target grows it.
  function splitter(handle, { target, cssVar, axis = 'x', sign = 1, min = 160, max = 1200, key, onResize }) {
    const root = document.documentElement;
    try { const v = localStorage.getItem(key); if (v) root.style.setProperty(cssVar, v + 'px'); } catch (e) {}
    handle.addEventListener('pointerdown', e => {
      e.preventDefault(); handle.setPointerCapture(e.pointerId); handle.classList.add('drag');
      const pos = ev => (axis === 'x' ? ev.clientX : ev.clientY), start = pos(e);
      const r = target.getBoundingClientRect(), size0 = axis === 'x' ? r.width : r.height;
      let size = size0, raf = 0;
      const move = ev => {
        size = Math.max(min, Math.min(max, size0 + sign * (pos(ev) - start)));
        root.style.setProperty(cssVar, size + 'px');
        if (!raf) raf = requestAnimationFrame(() => { raf = 0; onResize && onResize(); });
      };
      const up = () => {
        handle.removeEventListener('pointermove', move); handle.removeEventListener('pointerup', up); handle.classList.remove('drag');
        try { localStorage.setItem(key, Math.round(size)); } catch (e) {}
        onResize && onResize();
      };
      handle.addEventListener('pointermove', move); handle.addEventListener('pointerup', up);
    });
  }

  function chip(tag, opts = {}) {
    const c = opts.suggested ? H.UI.suggested : H.UI.confirmed;
    const score = opts.score != null ? `<span class="score">${Math.round(opts.score * 100)}</span>` : '';
    const acts = opts.suggested
      ? `<button data-act="accept" data-tag="${H.esc(tag)}" data-i="${opts.i}" title="Confirm">✓</button><button data-act="reject" data-tag="${H.esc(tag)}" data-i="${opts.i}" title="Reject">✕</button>`
      : opts.removable ? `<button data-act="remove" data-tag="${H.esc(tag)}" data-i="${opts.i}" title="Remove">×</button>` : '';
    return `<span class="chip${opts.suggested ? ' sugg' : ''}" style="--c:${c}"><i></i>${H.esc(tag)}${score}${acts}</span>`;
  }

  // Frames forward global shortcuts to the host; help layer follows the host; hearts toggle likes everywhere.
  document.addEventListener('keydown', e => H.key(e));
  document.addEventListener('click', e => H.likeClick(e), true);
  H.on('likes', i => document.querySelectorAll(`[data-like="${i}"]`).forEach(b => b.classList.toggle('on', d().tracks[i].liked)));
  document.addEventListener('DOMContentLoaded', () => { H.applyHelp(document); H.applyLang(document); });

  window.S = {
    H, DOT, col, layout, colors, labels, nearest, plot, setSpace, setColor, highlight, drawPath, markPlaying, setOpacity, focus, relabel, setAnnotations,
    toPx, toData, ctrlLasso, splitter, chip, heart: i => H.heart(i), esc: H.esc, fmtDur: H.fmtDur, toast: m => H.toast(m), player: H.player,
    get tracks() { return d().tracks; }, get data() { return d(); }, ready: H.ready,
  };
})();
