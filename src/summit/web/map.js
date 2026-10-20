// Shared map + list helpers for mode pages (iframes). Data, player and shared state live in the host (core.js).
// Maps are drawn by galaxy.js (three.js), the same renderer the phone app uses; pages load it as a module next to this.
(function () {
  const H = window.parent.Summit;
  const DOT = { fade: 0.13 };
  const d = () => H.data;
  const col = c => H.leafColor(c);  // family hue, sub-cluster shade
  const galaxyReady = window.Galaxy ? Promise.resolve() : new Promise(r => window.addEventListener('galaxy', r, { once: true }));

  // ---- data views ----
  const layout = space => d().spaces[space];
  const BPM_SCALE = [[0, '#5aa9ff'], [0.45, '#3ee6ff'], [0.75, '#ffd84d'], [1, '#ff4d8d']];
  const TAG_SCALE = [[0, '#1c2433'], [0.55, '#14806a'], [1, H.UI.confirmed]];
  const hex2 = h => [1, 3, 5].map(k => parseInt(h.slice(k, k + 2), 16));
  function scale(stops, v) {  // colour at v (0..1) along a colour scale
    v = Math.max(0, Math.min(1, v));
    let k = 1; while (k < stops.length - 1 && stops[k][0] < v) k++;
    const [a, ca] = stops[k - 1], [b, cb] = stops[k], t = (v - a) / (b - a || 1), A = hex2(ca), B = hex2(cb);
    return '#' + A.map((x, j) => Math.round(x + (B[j] - x) * t).toString(16).padStart(2, '0')).join('');
  }
  function colors(mode) {  // one CSS colour per track
    const T = d().tracks;
    if (mode === 'bpm') return T.map(t => scale(BPM_SCALE, ((t.bpm || 120) - 100) / 40));
    if (mode === 'tagged') return T.map(t => (t.confirmed.length ? H.UI.confirmed : H.UI.untagged));
    if (mode && mode.startsWith('tag:')) {
      const tag = mode.slice(4), s = d().scores[tag] || [];
      return T.map(t => scale(TAG_SCALE, t.confirmed.includes(tag) ? 1 : s[t.i] || 0));
    }
    if (mode === 'family') return T.map(t => H.familyHue(t.family));
    return T.map(t => col(t.cluster));
  }
  // Labels follow zoom: family names on the whole map; inside split families, sub-cluster names once zoomed in.
  // Genres = family labels, subgenres = sub-cluster labels; with only one switched on, that level shows at every zoom.
  const LEAF_ZOOM = 2;
  function labels(space) {
    const L = layout(space), show = H.labelShow, fc = f => (H.color === 'family' ? H.familyHue(f) : H.familyColor(f));
    if (!L || (!show.fam && !show.sub)) return [];
    const members = key => { const m = {}; L[key].forEach((g, j) => (m[g] = m[g] || []).push(j)); return m; };
    const fams = members('f'), leaves = members('c'), out = [];
    const both = show.fam && show.sub;
    for (const [f, idxs] of Object.entries(fams)) {
      const split = H.isSplit(+f);
      if (show.fam || !split) out.push({ text: H.familyName(+f), color: fc(+f), idxs, size: show.fam ? 'family' : 'leaf', min: 0, max: both && split ? LEAF_ZOOM : Infinity });
    }
    if (show.sub) for (const [c, idxs] of Object.entries(leaves)) {
      if (!H.isSplit(H.familyOf(+c))) continue;  // unsplit families are their own subgenre (labelled above)
      out.push({ text: H.leafName(+c), color: col(+c), idxs, size: 'leaf', min: both ? LEAF_ZOOM : 0 });
    }
    return out.sort((a, b) => (a.size === b.size ? b.idxs.length - a.idxs.length : a.size === 'family' ? -1 : 1));
  }
  function nearest(x, y, space) {
    const L = layout(space); let best = 0, bd = Infinity;
    for (let j = 0; j < L.x.length; j++) { const e = (L.x[j] - x) ** 2 + (L.y[j] - y) ** 2; if (e < bd) { bd = e; best = j; } }
    return best;
  }
  // Pixel look: a few bright stars per sub-cluster get a glint (spread out over its core by farthest-point sampling).
  function glints(space) {
    const L = layout(space); if (!L) return [];
    const groups = {}; L.c.forEach((c, j) => (groups[c] = groups[c] || []).push(j));
    const out = [];
    for (const m of Object.values(groups)) {
      if (m.length < 4) continue;
      const cx = m.reduce((a, j) => a + L.x[j], 0) / m.length, cy = m.reduce((a, j) => a + L.y[j], 0) / m.length;
      const core = m.slice().sort((a, b) => ((L.x[a] - cx) ** 2 + (L.y[a] - cy) ** 2) - ((L.x[b] - cx) ** 2 + (L.y[b] - cy) ** 2)).slice(0, Math.max(4, Math.floor(m.length * 0.8)));
      const k = Math.max(3, Math.min(11, Math.floor(m.length / 14))), chosen = [core[0]], dmin = core.map(() => Infinity);
      for (let s = 1; s < k; s++) {
        const last = chosen[chosen.length - 1]; let best = 0;
        core.forEach((j, q) => { dmin[q] = Math.min(dmin[q], (L.x[j] - L.x[last]) ** 2 + (L.y[j] - L.y[last]) ** 2); if (dmin[q] > dmin[best]) best = q; });
        if (dmin[best] > 0) chosen.push(core[best]);
      }
      out.push(...chosen);
    }
    return out;
  }
  const tooltip = i => { const t = d().tracks[i]; return `<b>${H.esc(t.title)}</b><br><span>${H.esc(t.artist)} · ${t.bpm ?? '—'} BPM · ${t.key ?? '—'}</span>`; };

  // ---- maps ----
  const maps = [];
  const NOOP = new Proxy({}, { get: () => () => null });
  const g = div => div._g || NOOP;  // calls before the map exists (plot is async) do nothing
  // opts: space, colorMode, flat (never 3D), onTap(i, info), onDoubleTap, onLongPress. Double-tap defaults to: a track →
  // go to it on the Map; empty space → show the whole map. ⌥+hover auditions.
  function plot(div, { space, colorMode, flat = false, onTap, onDoubleTap, onLongPress } = {}) {
    return galaxyReady.then(() => {
      const L = layout(space);
      const gx = window.Galaxy.create(div, {
        tooltip, onTap, onLongPress,
        onDoubleTap: onDoubleTap || ((i, info) => { if (i != null) H.dblGoTo(info.event, i); else gx.fit(); }),
        onHover: (i, info) => { if (i != null && info.event && info.event.altKey) H.player.audition(i); },
      });
      div._g = gx; div._flat = flat;
      gx.setStyle(H.lookStyle());
      gx.setGlints(glints(space));
      gx.setPositions(L.x, L.y, L.xyz);
      gx.setColors(colors(colorMode));
      gx.setLabels(labels(space));
      if (!flat && H.view3d) gx.set3D(true, false);
      gx.fit(false);
      maps.push(div);
      markPlaying();
      return gx;
    });
  }
  function setSpace(div, space) {
    const L = layout(space);
    g(div).setPositions(L.x, L.y, L.xyz);
    g(div).setGlints(glints(space));
    g(div).setLabels(labels(space));
    g(div).fit();
  }
  function setColor(div, mode) {
    g(div).setColors(colors(mode));
    relabel(div);  // family label colours follow the mode
  }
  const highlight = (div, idxs) => g(div).setRings(idxs.map((i, k) => ({ i, r: k ? 7 : 9, color: '#fff', width: k ? 1.2 : 2 })));
  const drawPath = (div, idxs) => g(div).setPaths(idxs.length > 1 ? [{ idxs, color: '#fff', width: 2, glow: 'rgba(255,255,255,.1)' }] : []);
  function markPlaying() { for (const m of maps) if (m._g) m._g.setPlaying(H.player.current, H.player.playing); }
  const setOpacity = (div, op) => g(div).setAlpha(op);
  function focus(div, idxs) {
    if (!idxs) { g(div).setAlpha(null); return; }
    const op = new Array(d().tracks.length).fill(DOT.fade); idxs.forEach(j => (op[j] = 1));
    g(div).setAlpha(op);
  }
  // View state for Back / Forward, and centring on a track.
  const getRange = div => g(div).getView();
  const setRange = (div, v) => g(div).setView(v);
  const centerOn = (div, i) => g(div).centerOn(i);
  const relabel = div => g(div).setLabels(labels(H.space));
  const toPx = (div, x, y) => g(div).toPxXYZ(x, y, 0);
  const toData = (div, px, py) => g(div).toData(px, py);

  H.on('player', markPlaying);
  H.on('playstate', markPlaying);
  H.on('labels', () => maps.forEach(m => m._g && relabel(m)));
  // A new look: map style, cluster colours and labels follow; the page itself restyles through CSS.
  H.on('look', () => maps.forEach(m => { if (!m._g) return; m._g.setStyle(H.lookStyle()); m._g.setColors(colors(H.color)); relabel(m); }));
  H.on('3d', on => maps.forEach(m => m._g && !m._flat && m._g.set3D(on)));

  // Ctrl-drag lasso on our own SVG overlay (macOS turns ctrl-click into a context menu). Tests screen positions, so it
  // selects what you see, in 3D too.
  function ctrlLasso(div, onSelect, onClear) {
    const ns = 'http://www.w3.org/2000/svg', svg = document.createElementNS(ns, 'svg'), poly = document.createElementNS(ns, 'polygon');
    svg.setAttribute('class', 'lasso'); svg.appendChild(poly);
    if (getComputedStyle(div).position === 'static') div.style.position = 'relative';
    div.appendChild(svg);
    div.addEventListener('contextmenu', e => { if (e.ctrlKey) e.preventDefault(); });
    div.addEventListener('pointerdown', e => {
      if (!e.ctrlKey || e.button !== 0) return;
      e.preventDefault(); e.stopPropagation();
      const r = div.getBoundingClientRect(), pts = [[e.clientX - r.left, e.clientY - r.top]];
      svg.classList.add('on');
      const move = ev => { pts.push([ev.clientX - r.left, ev.clientY - r.top]); poly.setAttribute('points', pts.map(p => p.join(',')).join(' ')); };
      const up = ev => {
        document.removeEventListener('pointermove', move, true); document.removeEventListener('pointerup', up, true);
        svg.classList.remove('on'); poly.setAttribute('points', '');
        if (pts.length > 3) onSelect(inside(div, pts), ev.shiftKey);
      };
      document.addEventListener('pointermove', move, true); document.addEventListener('pointerup', up, true);
    }, true);
    window.addEventListener('keydown', e => { if (e.key === 'Escape' && onClear) onClear(); });
  }
  function inside(div, P) {
    const out = [], n = d().tracks.length;
    for (let j = 0; j < n; j++) {
      const [x, y] = g(div).toPx(j); let inPoly = false;
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
  document.addEventListener('mouseup', e => H.mouse(e));
  document.addEventListener('mousedown', () => H.emit('mousedown', document), true);
  document.addEventListener('click', e => H.likeClick(e), true);
  H.on('likes', i => document.querySelectorAll(`[data-like="${i}"]`).forEach(b => b.classList.toggle('on', d().tracks[i].liked)));
  H.applyLook(document);
  document.addEventListener('DOMContentLoaded', () => { H.applyHelp(document); H.applyLang(document); H.applyLook(document); });

  window.S = {
    H, DOT, col, layout, colors, labels, nearest, plot, setSpace, setColor, highlight, drawPath, markPlaying, setOpacity, focus, relabel,
    getRange, setRange, centerOn,
    toPx, toData, ctrlLasso, splitter, chip, heart: i => H.heart(i), esc: H.esc, fmtDur: H.fmtDur, toast: m => H.toast(m), player: H.player,
    get tracks() { return d().tracks; }, get data() { return d(); }, ready: H.ready,  // plot() itself waits for the renderer
  };
})();
