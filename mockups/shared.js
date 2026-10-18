// Summit UI mockups: shared library model, map helpers and player.
// Tracks are your real library (library.json via app.py); clusters, tags and layouts are fake.
(function () {
  let seed = 7;
  const rand = () => (seed = (seed * 1664525 + 1013904223) >>> 0) / 4294967296;
  const gauss = () => { let u = 0; while (!u) u = rand(); return Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * rand()); };
  const pick = a => a[Math.floor(rand() * a.length)];
  const cap = s => s[0].toUpperCase() + s.slice(1);

  // Mirrors rekordbox My Tag categories.
  const TAGS = {
    Mood: ['dark', 'warm', 'euphoric', 'melancholic', 'hypnotic', 'playful'],
    Energy: ['warm-up', 'builder', 'peak', 'closer'],
    Texture: ['dubby', 'acid', 'vocal', 'percussive', 'piano', 'lush pads', 'rolling bass'],
    Situation: ['late night', 'afterhours', 'daytime', 'sunrise'],
  };
  const TAG_CAT = {};
  for (const [c, ts] of Object.entries(TAGS)) ts.forEach(t => (TAG_CAT[t] = c));
  const ALL_TAGS = Object.keys(TAG_CAT);

  const CLUSTERS = [
    { name: 'Dub techno', c: [-6, 4], w: 7, bpm: 122, tags: ['dubby', 'hypnotic', 'late night', 'afterhours'], styles: ['Dub Techno', 'Minimal', 'Deep Techno'] },
    { name: 'Deep house', c: [-1, 6], w: 12, bpm: 121, tags: ['warm', 'lush pads', 'warm-up', 'daytime'], styles: ['Deep House', 'House', 'Soulful House'] },
    { name: 'Rominimal', c: [-4, -1], w: 9, bpm: 126, tags: ['hypnotic', 'percussive', 'rolling bass', 'afterhours'], styles: ['Minimal', 'Tech House', 'Microhouse'] },
    { name: 'Lo-fi house', c: [3, 7], w: 6, bpm: 119, tags: ['warm', 'melancholic', 'daytime', 'piano'], styles: ['Lo-Fi House', 'Deep House', 'Downtempo'] },
    { name: 'Breaks', c: [6, 2], w: 7, bpm: 132, tags: ['playful', 'percussive', 'builder'], styles: ['Breaks', 'Breakbeat', 'UK Garage'] },
    { name: 'Peak techno', c: [-8, -5], w: 9, bpm: 134, tags: ['dark', 'peak', 'rolling bass'], styles: ['Techno', 'Hard Techno', 'Industrial'] },
    { name: 'Melodic / progressive', c: [1, -4], w: 10, bpm: 123, tags: ['euphoric', 'builder', 'sunrise', 'lush pads'], styles: ['Progressive House', 'Melodic Techno', 'Trance'] },
    { name: 'Disco & edits', c: [5, 9], w: 8, bpm: 118, tags: ['playful', 'vocal', 'daytime', 'warm-up'], styles: ['Disco', 'Nu-Disco', 'Edit'] },
    { name: 'Ambient / downtempo', c: [-3, 10], w: 5, bpm: 100, tags: ['melancholic', 'closer', 'lush pads', 'sunrise'], styles: ['Ambient', 'Downtempo', 'Electronica'] },
    { name: 'Electro', c: [8, -3], w: 6, bpm: 128, tags: ['dark', 'playful', 'builder'], styles: ['Electro', 'Synth-pop', 'EBM'] },
    { name: 'UK garage', c: [9, 5], w: 6, bpm: 133, tags: ['vocal', 'rolling bass', 'percussive', 'playful'], styles: ['UK Garage', 'Speed Garage', '2-Step'] },
    { name: 'Acid', c: [-2, -8], w: 5, bpm: 130, tags: ['acid', 'hypnotic', 'peak', 'dark'], styles: ['Acid House', 'Acid', 'Techno'] },
  ];
  // Colour system, identical in every view:
  //   cluster palette -> data only: track dots, timeline blocks, list colour bars, stage fills, legend
  //   white           -> focus/structure: highlight rings, routes, corridors, the now-playing ring
  //   pink            -> selection: lasso, selected stage, checked rows
  //   lime            -> confirmed tags: tag chips, "tagged" colour mode, tag-score scale
  //   cyan            -> UI controls only: buttons, toggles, sliders, play button
  const UI = { focus: '#ffffff', selected: '#ff3d8b', confirmed: '#d4ff3a', suggested: '#8fb5bf', untagged: '#3f6670' };
  const DOT = { size: 5, fade: 0.2 };
  const HALO = { size: 11, opacity: 0.16 };
  // Neon set pulled from future-city.jpeg (cyan tubes, pink signage, car underglow), widened to 12 hues.
  const PALETTE = ['#19e3ff', '#ff3d8b', '#d4ff3a', '#a66bff', '#ff9a3d', '#3dffc5', '#ff2e4d', '#5aa9ff', '#f45bff', '#ffd23d', '#8affd1', '#ff8fb1'];

  // Real library if served by app.py; otherwise synthetic names.
  let LIB = [];
  try { const x = new XMLHttpRequest(); x.open('GET', 'library.json', false); x.send(); if (x.status === 200) LIB = JSON.parse(x.responseText); } catch (e) {}
  const HAS_AUDIO = LIB.length > 0;
  const SYL = ['ka', 'lo', 'mi', 'ren', 'tor', 'va', 'sel', 'ix', 'du', 'an', 'mor', 'bel', 'zen', 'qu', 'ra', 'ost', 'fen', 'li'];
  const WORDS = ['Night', 'Signal', 'Drift', 'Low', 'Tide', 'Glass', 'Motion', 'Echo', 'Velvet', 'Static', 'Orbit', 'Pulse', 'Haze', 'Ritual', 'Mirror', 'Fade'];
  const fake = () => ({ artist: cap(pick(SYL) + pick(SYL)), title: pick(WORDS) + ' ' + pick(WORDS), album: '', dur: Math.round(330 + gauss() * 70) });

  const TOTAL = HAS_AUDIO ? LIB.length : 3900, sumW = CLUSTERS.reduce((a, c) => a + c.w, 0);
  const order = Array.from({ length: TOTAL }, (_, k) => k);
  for (let k = TOTAL - 1; k > 0; k--) { const j = Math.floor(rand() * (k + 1)); [order[k], order[j]] = [order[j], order[k]]; }
  const tracks = [];
  let cursor = 0;
  CLUSTERS.forEach((cl, ci) => {
    const n = ci === CLUSTERS.length - 1 ? TOTAL - cursor : Math.round((TOTAL * cl.w) / sumW);
    for (let k = 0; k < n; k++, cursor++) {
      const file = order[cursor], meta = HAS_AUDIO ? LIB[file] : fake();
      const scores = {};
      ALL_TAGS.forEach(t => (scores[t] = Math.max(0, Math.min(1, (cl.tags.includes(t) ? 0.62 : 0.12) + gauss() * 0.16))));
      const confirmed = rand() < 0.12 ? ALL_TAGS.filter(t => scores[t] > 0.7).slice(0, 2) : [];
      tracks.push({
        i: tracks.length, file: HAS_AUDIO ? file : null, cluster: ci, artist: meta.artist, title: meta.title, album: meta.album, dur: meta.dur, ext: meta.ext || '',
        bpm: Math.round(cl.bpm + gauss() * 3), key: 1 + Math.floor(rand() * 12) + (rand() < 0.5 ? 'A' : 'B'),
        g: [gauss(), gauss()], scores, confirmed, rejected: [],
        styles: cl.styles.map((s, j) => [s, Math.max(0.05, 0.7 - j * 0.22 + gauss() * 0.08)]),
      });
    }
  });

  // CLAP blurs electronic subgenres together; EffNet separates them. The fake layouts mimic that.
  const SPACES = { clap: { sep: 0.55, spread: 1.5, rot: 0.4 }, effnet: { sep: 1, spread: 0.85, rot: 0 }, combined: { sep: 0.8, spread: 1.1, rot: -0.3 } };
  const cache = {};
  function layout(space) {
    if (cache[space]) return cache[space];
    const p = SPACES[space], cs = Math.cos(p.rot), sn = Math.sin(p.rot), x = [], y = [];
    for (const t of tracks) {
      const c = CLUSTERS[t.cluster].c;
      const px = c[0] * p.sep + t.g[0] * p.spread, py = c[1] * p.sep + t.g[1] * p.spread;
      x.push(px * cs - py * sn); y.push(px * sn + py * cs);
    }
    return (cache[space] = { x, y });
  }

  const BPM_SCALE = [[0, '#5aa9ff'], [0.45, '#19e3ff'], [0.75, '#ffd23d'], [1, '#ff9a3d']];
  const TAG_SCALE = [[0, '#1f3540'], [0.5, '#5f7f35'], [1, UI.confirmed]];
  function colors(mode) {
    if (mode === 'cluster') return { color: tracks.map(t => PALETTE[t.cluster]) };
    if (mode === 'bpm') return { color: tracks.map(t => t.bpm), colorscale: BPM_SCALE, cmin: 100, cmax: 136 };
    if (mode === 'tagged') return { color: tracks.map(t => (t.confirmed.length ? UI.confirmed : UI.untagged)) };
    if (mode.startsWith('tag:')) {
      const tag = mode.slice(4);
      return { color: tracks.map(t => (t.confirmed.includes(tag) ? 1 : t.scores[tag])), colorscale: TAG_SCALE, cmin: 0, cmax: 1 };
    }
  }

  let fontReady = false;
  const fontsReady = Promise.race([document.fonts.load('9px Silkscreen'), new Promise(r => setTimeout(r, 2000))]).then(() => (fontReady = true));
  function labels(space) {
    const L = layout(space), sx = CLUSTERS.map(() => 0), sy = CLUSTERS.map(() => 0), n = CLUSTERS.map(() => 0);
    tracks.forEach((t, j) => { sx[t.cluster] += L.x[j]; sy[t.cluster] += L.y[j]; n[t.cluster]++; });
    return CLUSTERS.map((c, ci) => ({
      x: sx[ci] / n[ci], y: sy[ci] / n[ci], text: c.name.toUpperCase() + (fontReady ? '\u200a' : ''), showarrow: false,
      font: { size: 9, color: PALETTE[ci], family: 'Silkscreen, monospace' }, bgcolor: '#05090d', bordercolor: PALETTE[ci], borderwidth: 1, borderpad: 4,
    }));
  }

  function neighbors(i, k, space) {
    const L = layout(space);
    const d = tracks.map((t, j) => [j, (L.x[j] - L.x[i]) ** 2 + (L.y[j] - L.y[i]) ** 2]);
    d.sort((a, b) => a[1] - b[1]);
    return d.slice(1, k + 1).map(e => e[0]);
  }
  function nearest(x, y, space) {
    const L = layout(space); let best = 0, bd = Infinity;
    for (let j = 0; j < tracks.length; j++) { const d = (L.x[j] - x) ** 2 + (L.y[j] - y) ** 2; if (d < bd) { bd = d; best = j; } }
    return best;
  }

  // Stand-in for CLAP text search: real artist/title matches first, then keyword overlap with tags/clusters.
  function search(q, n = 50) {
    const ql = q.toLowerCase(), words = ql.split(/[^a-z0-9-]+/).filter(w => w.length > 2);
    const hitTags = ALL_TAGS.filter(t => words.some(w => t.includes(w)));
    const hitClusters = CLUSTERS.map((c, ci) => (words.some(w => c.name.toLowerCase().includes(w) || c.styles.some(s => s.toLowerCase().includes(w))) ? ci : -1)).filter(ci => ci >= 0);
    let h = 0; for (const ch of q) h = (h * 31 + ch.charCodeAt(0)) >>> 0;
    const fb = h % CLUSTERS.length;
    const scored = tracks.map(t => {
      const name = (t.artist + ' ' + t.title).toLowerCase();
      let s = hitTags.reduce((a, tg) => a + t.scores[tg], 0) + (hitClusters.includes(t.cluster) ? 1 : 0);
      if (name.includes(ql)) s += 5;
      if (!s) s = (t.cluster === fb ? 1 : 0) + t.scores[CLUSTERS[fb].tags[0]];
      return [t.i, s + rand() * 0.15];
    });
    scored.sort((a, b) => b[1] - a[1]);
    const top = scored.slice(0, n), max = top[0][1];
    return top.map(([i, s]) => ({ i, score: s / max }));
  }

  const suggested = t => ALL_TAGS.filter(tg => t.scores[tg] > 0.6 && !t.confirmed.includes(tg) && !t.rejected.includes(tg)).sort((a, b) => t.scores[b] - t.scores[a]);
  const fmtDur = s => { s = Math.max(0, Math.round(s || 0)); const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), r = String(s % 60).padStart(2, '0'); return h ? `${h}:${String(m).padStart(2, '0')}:${r}` : `${m}:${r}`; };
  const esc = s => String(s ?? '').replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]);

  function chip(tag, opts = {}) {
    const col = opts.suggested ? UI.suggested : UI.confirmed;
    const cls = opts.suggested ? 'chip sugg' : 'chip';
    const score = opts.score != null ? `<span class="score">${Math.round(opts.score * 100)}</span>` : '';
    const acts = opts.suggested
      ? `<button data-act="accept" data-tag="${tag}" data-i="${opts.i}" title="Confirm">✓</button><button data-act="reject" data-tag="${tag}" data-i="${opts.i}" title="Reject">✕</button>`
      : opts.removable ? `<button data-act="remove" data-tag="${tag}" data-i="${opts.i}" title="Remove">×</button>` : '';
    return `<span class="${cls}" style="--c:${col}"><i></i>${esc(tag)}${score}${acts}</span>`;
  }

  // Map traces: 0 tracks, 1 highlight rings, 2 path glow, 3 path, 4 now playing, 5 halo.
  // WebGL points can't glow, so trace 5 redraws every dot large and faint to fake neon bloom.
  const maps = [];
  function plot(div, { space, colorMode, labels: showLabels = true, config = {}, getSpace }) {
    const L = layout(space), C = colors(colorMode);
    const main = {
      type: 'scattergl', mode: 'markers', x: L.x, y: L.y,
      text: tracks.map(t => `<b>${esc(t.title)}</b><br>${esc(t.artist)} · ${t.bpm} BPM · ${t.key}`),
      hovertemplate: '%{text}<extra></extra>',
      marker: { symbol: 'square', size: DOT.size, color: C.color, colorscale: C.colorscale, cmin: C.cmin, cmax: C.cmax, opacity: 1 },
    };
    const none = { type: 'scattergl', x: [], y: [], hoverinfo: 'skip' };
    const hi = { ...none, mode: 'markers', marker: { symbol: 'square-open', size: 12, color: UI.focus, line: { color: UI.focus, width: 1.5 } } };
    const glow = { ...none, mode: 'lines', line: { color: 'rgba(255,255,255,.12)', width: 10 } };
    const path = { ...none, mode: 'lines+markers', line: { color: UI.focus, width: 2 }, marker: { symbol: 'square', size: 9, color: UI.focus } };
    const playing = { ...none, mode: 'markers', marker: { symbol: 'square', size: 22, color: 'rgba(255,255,255,.15)', line: { color: UI.focus, width: 2 } } };
    const halo = { type: 'scattergl', mode: 'markers', x: L.x, y: L.y, hoverinfo: 'skip', marker: { symbol: 'square', size: HALO.size, color: C.color, colorscale: C.colorscale, cmin: C.cmin, cmax: C.cmax, opacity: HALO.opacity } };
    const lay = {
      paper_bgcolor: 'rgba(0,0,0,0)', plot_bgcolor: 'rgba(0,0,0,0)', margin: { l: 0, r: 0, t: 0, b: 0 },
      xaxis: { visible: false }, yaxis: { visible: false, scaleanchor: 'x' },
      dragmode: 'pan', showlegend: false, hovermode: 'closest', annotations: showLabels ? labels(space) : [],
      hoverlabel: { bgcolor: '#05090d', bordercolor: '#ffffff', font: { color: '#ece4ec', size: 11, family: 'JetBrains Mono, monospace' } },
    };
    maps.push({ div, getSpace: getSpace || (() => space) });
    const p = Plotly.newPlot(div, [main, hi, glow, path, playing, halo], lay, { displayModeBar: false, scrollZoom: false, responsive: true, ...config });
    wheelZoom(div);
    // Plotly measures (and caches) label boxes at draw time; once the pixel font is in, redraw labels with a
    // cache-busting suffix so the boxes fit the real glyphs.
    fontsReady.then(() => { if (showLabels && div._fullLayout) Plotly.relayout(div, { annotations: labels((getSpace || (() => space))()) }); });
    return p.then(() => markPlaying());
  }
  // Scroll zoom: Plotly redraws on every wheel event, which stutters with many points + labels.
  // Accumulate wheel deltas and apply one relayout per animation frame, anchored at the cursor.
  let lastInteract = 0;
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
        const { xaxis: xa, yaxis: ya } = div._fullLayout, f = Math.max(0.6, Math.min(1.6, factor)), [ax, ay] = anchor;   // cap per-frame jump
        factor = 1;
        Plotly.relayout(div, { 'xaxis.range': xa.range.map(v => ax + (v - ax) * f), 'yaxis.range': ya.range.map(v => ay + (v - ay) * f) });
      });
    }, { passive: false });
    div.addEventListener('mousedown', () => (lastInteract = performance.now()));
    div.addEventListener('mousemove', e => { if (e.buttons) lastInteract = performance.now(); });
  }

  function setSpace(div, space, showLabels = true) {
    const L = layout(space);
    Plotly.restyle(div, { x: [L.x, L.x], y: [L.y, L.y] }, [0, 5]);
    Plotly.relayout(div, { annotations: showLabels ? labels(space) : [], 'xaxis.autorange': true, 'yaxis.autorange': true });
    markPlaying();
  }
  function setColor(div, mode) {
    const C = colors(mode);
    const two = v => [v, v];
    Plotly.restyle(div, { 'marker.color': two(C.color), 'marker.colorscale': two(C.colorscale || null), 'marker.cmin': two(C.cmin), 'marker.cmax': two(C.cmax) }, [0, 5]);
  }

  const xy = (idxs, space) => { const L = layout(space); return { x: [idxs.map(j => L.x[j])], y: [idxs.map(j => L.y[j])] }; };
  function highlight(div, idxs, space) { Plotly.restyle(div, xy(idxs, space), [1]); }
  function drawPath(div, idxs, space) { const d = xy(idxs, space); Plotly.restyle(div, { x: [d.x[0], d.x[0]], y: [d.y[0], d.y[0]] }, [2, 3]); }
  function markPlaying() {
    const i = player.current;
    for (const m of maps) if (m.div._fullLayout) Plotly.restyle(m.div, xy(i == null ? [] : [i], m.getSpace()), [4]);
  }
  // Per-dot opacity (number or array), mirrored onto the halo so faded dots lose their glow too.
  function setOpacity(div, op) {
    const halo = Array.isArray(op) ? op.map(o => (o >= 1 ? HALO.opacity : o * HALO.opacity * 0.4)) : op * HALO.opacity;
    Plotly.restyle(div, { 'marker.opacity': [op, halo] }, [0, 5]);
  }
  // Fade everything except idxs; pass null to restore.
  function focus(div, idxs) {
    if (!idxs) { setOpacity(div, 1); return; }
    const op = new Array(tracks.length).fill(DOT.fade); idxs.forEach(j => (op[j] = 1));
    setOpacity(div, op);
  }
  function relabel(div, space) { Plotly.relayout(div, { annotations: labels(space) }); }
  const toPx = (div, x, y) => { const { xaxis: xa, yaxis: ya } = div._fullLayout; return [xa.l2p(x) + xa._offset, ya.l2p(y) + ya._offset]; };
  const toData = (div, px, py) => { const { xaxis: xa, yaxis: ya } = div._fullLayout; return [xa.p2l(px - xa._offset), ya.p2l(py - ya._offset)]; };

  // Ctrl-drag lasso drawn on our own SVG overlay. Plotly treats ctrl-drag as a right click and
  // macOS turns ctrl-click into a context menu, so we intercept in the capture phase.
  function ctrlLasso(div, getSpace, onSelect, onClear) {
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
      const move = ev => {
        ev.stopPropagation();
        pts.push([ev.clientX - r.left, ev.clientY - r.top]);
        poly.setAttribute('points', pts.map(p => p.join(',')).join(' '));
      };
      const up = ev => {
        ev.stopPropagation();
        document.removeEventListener('mousemove', move, true); document.removeEventListener('mouseup', up, true);
        svg.classList.remove('on'); poly.setAttribute('points', '');
        if (pts.length > 3) onSelect(inside(div, pts, getSpace()), ev.shiftKey);
      };
      document.addEventListener('mousemove', move, true); document.addEventListener('mouseup', up, true);
    }, true);
    window.addEventListener('keydown', e => { if (e.key === 'Escape' && onClear) onClear(); });
  }
  function inside(div, pts, space) {
    const L = layout(space), P = pts.map(([px, py]) => toData(div, px, py)), out = [];
    for (let j = 0; j < tracks.length; j++) {
      const x = L.x[j], y = L.y[j]; let inPoly = false;
      for (let a = 0, b = P.length - 1; a < P.length; b = a++) {
        if ((P[a][1] > y) !== (P[b][1] > y) && x < ((P[b][0] - P[a][0]) * (y - P[a][1])) / (P[b][1] - P[a][1]) + P[a][0]) inPoly = !inPoly;
      }
      if (inPoly) out.push(j);
    }
    return out;
  }

  // Drag handle that sets a CSS size variable. sign = -1 when dragging toward the target grows it.
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

  // Player with a queue. "Next" follows whatever list the track was started from.
  const audio = new Audio();
  audio.preload = 'auto';
  const player = {
    queue: [], pos: -1, label: '', current: null, listeners: [],
    onChange(fn) { this.listeners.push(fn); },
    play(i, { queue, label, at } = {}) {
      if (queue) { this.queue = queue; this.label = label || ''; }
      this.pos = this.queue.indexOf(i);
      if (this.pos < 0) { this.queue = [i]; this.pos = 0; this.label = label || ''; }
      this.current = i;
      const t = tracks[i];
      if (HAS_AUDIO) {
        audio.src = `audio/${t.file}`;
        if (at) audio.addEventListener('loadedmetadata', () => (audio.currentTime = audio.duration * at), { once: true });
        audio.play().catch(() => {});
      }
      this.changed();
    },
    audition(i) { if (this.current !== i) this.play(i, { queue: [i], label: 'Audition (⌥ hover)', at: 0.4 }); },
    toggle() { if (this.current == null) return; audio.paused ? audio.play().catch(() => {}) : audio.pause(); },
    next() { if (this.pos < this.queue.length - 1) this.play(this.queue[this.pos + 1]); },
    prev() { if (audio.currentTime > 3 || this.pos <= 0) { audio.currentTime = 0; return; } this.play(this.queue[this.pos - 1]); },
    seek(f) { if (audio.duration) audio.currentTime = audio.duration * f; },
    changed() { renderPlayer(); markPlaying(); this.listeners.forEach(fn => fn(this.current)); },
  };
  audio.addEventListener('ended', () => player.next());
  audio.addEventListener('play', () => { renderPlayer(true); breathe(true); });
  audio.addEventListener('pause', () => { renderPlayer(true); breathe(false); });
  // The now-playing ring breathes on every map while audio plays (~12 fps restyle of one trace).
  let breathT = null;
  function breathe(on) {
    if (on && !breathT) {
      const t0 = performance.now();
      let state = -1;
      breathT = setInterval(() => {
        const s = Math.floor((performance.now() - t0) / 450) % 2;
        if (s === state || performance.now() - lastInteract < 600) return;   // nothing changed, or user is zooming/panning
        state = s;
        for (const m of maps) if (m.div._fullLayout && !m.div._dragging)
          Plotly.restyle(m.div, { 'marker.size': [s ? 26 : 18], 'marker.color': [`rgba(255,255,255,${s ? 0.28 : 0.1})`] }, [4]);
      }, 150);
    } else if (!on && breathT) { clearInterval(breathT); breathT = null; }
  }
  audio.addEventListener('timeupdate', () => {
    const el = document.getElementById('player'); if (!el) return;
    const p = el.querySelector('.prog'), tm = el.querySelector('.ptime');
    const dur = audio.duration || (player.current != null ? tracks[player.current].dur : 0);
    if (p) p.style.width = (dur ? (audio.currentTime / dur) * 100 : 0) + '%';
    if (tm) tm.textContent = `${fmtDur(audio.currentTime)} / ${fmtDur(dur)}`;
  });
  if ('mediaSession' in navigator) {
    try {
      navigator.mediaSession.setActionHandler('play', () => player.toggle());
      navigator.mediaSession.setActionHandler('pause', () => player.toggle());
      navigator.mediaSession.setActionHandler('nexttrack', () => player.next());
      navigator.mediaSession.setActionHandler('previoustrack', () => player.prev());
    } catch (e) {}
  }

  function renderPlayer(stateOnly) {
    const el = document.getElementById('player'); if (!el) return;
    const btn = el.querySelector('[data-p="toggle"]');
    el.classList.toggle('is-playing', !audio.paused);
    if (stateOnly && btn) { btn.textContent = audio.paused ? '▶' : '❚❚'; return; }
    const i = player.current;
    if (i == null) {
      el.innerHTML = `<div class="ptransport"><button disabled>⏮</button><button disabled class="pplay">▶</button><button disabled>⏭</button></div>
        <span class="muted">${HAS_AUDIO ? 'Click any track to play it' : 'No audio: run <code>uv run mockups/app.py</code>'}</span>`;
      return;
    }
    const t = tracks[i];
    let s = (t.file ?? i) * 9301 + 49297;
    const bars = Array.from({ length: 90 }, () => { s = (s * 9301 + 49297) % 233280; return 18 + (s / 233280) * 82; });
    el.innerHTML = `<div class="ptransport">
        <button data-p="prev" title="Previous (⇧←)">⏮</button>
        <button data-p="toggle" class="pplay" title="Play / pause (space)">${audio.paused && HAS_AUDIO ? '▶' : '❚❚'}</button>
        <button data-p="next" title="Next (⇧→)" ${player.pos >= player.queue.length - 1 ? 'disabled' : ''}>⏭</button></div>
      <span class="eq"><i></i><i></i><i></i><i></i></span>
      <span class="pdot dot" style="--c:${PALETTE[t.cluster]}"></span>
      <div class="pmeta"><b>${esc(t.title)}</b><span>${esc(t.artist)}</span></div>
      <span class="pfacts mono">${t.bpm} <small>BPM</small> · ${t.key}${t.ext ? ' · ' + esc(t.ext.toUpperCase()) : ''}</span>
      <div class="wave" data-p="seek" title="Click to seek">${bars.map(h => `<i style="height:${h}%"></i>`).join('')}<div class="prog"></div></div>
      <span class="ptime mono">0:00 / ${fmtDur(t.dur)}</span>
      <span class="pqueue">${esc(player.label || 'Queue')}<br><span class="mono">${player.pos + 1} / ${player.queue.length}</span></span>`;
  }
  document.addEventListener('click', e => {
    const b = e.target.closest('#player [data-p]'); if (!b || b.disabled) return;
    const a = b.dataset.p;
    if (a === 'seek') { const r = b.getBoundingClientRect(); player.seek((e.clientX - r.left) / r.width); }
    else player[a]();
  });
  document.addEventListener('keydown', e => {
    if (e.target.closest('input, select, textarea, [contenteditable]')) return;
    if (e.code === 'Space') { e.preventDefault(); player.toggle(); }
    if (e.shiftKey && e.key === 'ArrowRight') player.next();
    if (e.shiftKey && e.key === 'ArrowLeft') player.prev();
  });
  document.addEventListener('DOMContentLoaded', () => renderPlayer());

  // Help layer: every hint, tip and explainer carries class "help" and stays hidden until Ctrl+Alt+Shift+D.
  const setHelp = on => { document.body.classList.toggle('show-help', on); try { localStorage.setItem('summit.help', on ? '1' : ''); } catch (e) {} };
  document.addEventListener('DOMContentLoaded', () => { let on = false; try { on = !!localStorage.getItem('summit.help'); } catch (e) {} setHelp(on); });
  document.addEventListener('keydown', e => {
    if (e.ctrlKey && e.altKey && e.shiftKey && e.code === 'KeyD') { e.preventDefault(); const on = !document.body.classList.contains('show-help'); setHelp(on); toast(on ? 'Help on' : 'Help off'); }
  });

  function toast(msg) {
    let el = document.getElementById('toast');
    if (!el) { el = document.createElement('div'); el.id = 'toast'; document.body.appendChild(el); }
    el.textContent = msg; el.classList.add('on');
    clearTimeout(el._t); el._t = setTimeout(() => el.classList.remove('on'), 2600);
  }

  window.S = { tracks, CLUSTERS, PALETTE, UI, DOT, TAGS, TAG_CAT, ALL_TAGS, HAS_AUDIO, layout, colors, neighbors, nearest, search, suggested, fmtDur, esc, chip, plot, setSpace, setColor, labels, highlight, drawPath, focus, setOpacity, ctrlLasso, splitter, relabel, toPx, toData, player, audio, toast };
})();
