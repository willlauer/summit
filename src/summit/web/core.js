// Summit host (index.html): library data, player, shared state (space / colour / search), API calls,
// global shortcuts and the help layer. Mode pages run in iframes and reach this as window.parent.Summit.
(function () {
  // Colour roles (DESIGN.md). Data palette is for cluster data only.
  const PALETTE = ['#00f0ff', '#ff2a6d', '#05ffa1', '#b026ff', '#ff9a3d', '#5aa9ff', '#fcee0a', '#f45bff',
                   '#3dffc5', '#ff5c5c', '#a8ff3d', '#ffb3d9', '#7d7dff', '#e2b45a', '#40c4aa', '#c9a6ff'];
  const UI = { focus: '#ffffff', selected: '#fcee0a', confirmed: '#05ffa1', suggested: '#6f8ba3', untagged: '#2a3a4a', danger: '#ff2a6d' };

  const H = {
    PALETTE, UI, data: null, space: 'combined', color: 'cluster', results: null, help: false, mode: 'map',
    listeners: {},
    on(ev, fn) { (this.listeners[ev] = this.listeners[ev] || []).push(fn); },
    emit(ev, ...a) { (this.listeners[ev] || []).forEach(fn => { try { fn(...a); } catch (e) { /* listener from a reloaded frame */ } }); },
  };
  try { H.space = localStorage.getItem('summit.space') || 'combined'; H.help = !!localStorage.getItem('summit.help'); } catch (e) {}
  // Map labels: genres (families) and subgenres (sub-clusters) toggle independently. Both on = follow zoom.
  H.labelShow = { fam: true, sub: true };
  try { Object.assign(H.labelShow, JSON.parse(localStorage.getItem('summit.labels') || '{}')); } catch (e) {}
  H.setLabels = show => { H.labelShow = show; try { localStorage.setItem('summit.labels', JSON.stringify(show)); } catch (e) {} H.emit('labels', show); };

  const api = async (path, method = 'GET', body) => {
    const r = await fetch(path, { method, headers: body ? { 'Content-Type': 'application/json' } : {}, body: body ? JSON.stringify(body) : undefined });
    if (!r.ok) throw new Error(`${method} ${path}: ${r.status}`);
    return r.json();
  };
  H.api = api;

  H.ready = api('/api/library').then(d => {
    d.byId = {};
    d.tracks.forEach((t, i) => {
      t.i = i; d.byId[t.id] = i;
      Object.defineProperty(t, 'cluster', { get() { const s = d.spaces[H.space]; return s ? s.c[i] : 0; } });
      Object.defineProperty(t, 'family', { get() { const s = d.spaces[H.space]; return s ? s.f[i] : 0; } });
    });
    let stored = null; try { stored = localStorage.getItem('summit.space'); } catch (e) {}
    H.space = d.spaces[stored] ? stored : d.spaces[d.defaultSpace] ? d.defaultSpace : (Object.keys(d.spaces)[0] || 'combined');
    H.data = d;
    return d;
  });

  // ---- shared state ----
  H.setSpace = s => { H.space = s; shades = null; try { localStorage.setItem('summit.space', s); } catch (e) {} H.emit('space', s); };
  H.setColor = m => { H.color = m; H.emit('color', m); };
  // Two levels: families (density gaps in the embedding) › sub-clusters (structure inside a family).
  H.clusters = () => (H.data.clusters[H.space] || []);
  H.families = () => (H.data.families[H.space] || []);
  H.familyOf = c => (H.clusters()[c] || {}).family ?? 0;
  H.isSplit = f => H.clusters().filter(c => c.family === f).length > 1;
  H.familyName = f => (H.families()[f] || {}).label || `Family ${f + 1}`;
  H.leafName = c => (H.clusters()[c] || {}).label || `Group ${c + 1}`;
  H.clusterName = c => { const f = H.familyOf(c); return H.isSplit(f) ? `${H.familyName(f)} › ${H.leafName(c)}` : H.familyName(f); };
  // Colour: every sub-cluster gets its own hue (siblings never share one, so a family's internal regions are visible);
  // a family's label uses the colour of its largest sub-cluster. Unsplit families are a single colour.
  let shades = null;
  const palette = () => {
    if (!shades) {
      shades = { leaf: {}, family: {} };
      const leaves = [...H.clusters()].sort((a, b) => a.family - b.family || a.id - b.id);
      leaves.forEach((cl, k) => {
        shades.leaf[cl.id] = PALETTE[k % PALETTE.length];
        if (!(cl.family in shades.family)) shades.family[cl.family] = shades.leaf[cl.id];  // ids are largest-first
      });
    }
    return shades;
  };
  H.leafColor = c => palette().leaf[c] || PALETTE[0];
  H.familyHue = f => PALETTE[f % PALETTE.length];  // "Family" colour mode: one distinct colour per family
  H.familyColor = f => palette().family[f] || PALETTE[0];

  // ---- queries ----
  let searchSeq = 0;
  H.search = async q => {
    const seq = ++searchSeq;
    if (!q.trim()) { H.results = null; H.emit('search', null, ''); return null; }
    const res = await api(`/api/search?q=${encodeURIComponent(q)}&n=60`);
    if (seq !== searchSeq) return null;
    H.results = res.map(r => r.i);
    H.emit('search', H.results, q);
    return res;
  };
  H.find = q => api(`/api/search?q=${encodeURIComponent(q)}&n=25`);
  const nbCache = {};
  H.neighbors = async (i, k = 10) => {
    const key = `${H.space}:${i}:${k}`;
    return nbCache[key] || (nbCache[key] = api(`/api/neighbors?i=${i}&k=${k}&space=${H.space}`));
  };

  // ---- mutations ----
  H.setTag = async (idxs, tag, status) => {
    const d = H.data, res = await api('/api/tags', 'POST', { ids: idxs.map(i => d.tracks[i].id), tag, status });
    idxs.forEach(i => {
      const t = d.tracks[i];
      t.confirmed = t.confirmed.filter(g => g !== tag); t.rejected = t.rejected.filter(g => g !== tag);
      if (status === 'confirmed') t.confirmed.push(tag);
      if (status === 'rejected') t.rejected.push(tag);
    });
    d.scores[tag] = res.scores;
    H.emit('tags', tag);
    return res;
  };
  H.newVibe = async (name, idxs) => {
    const d = H.data, res = await api('/api/vibes', 'POST', { name, ids: idxs.map(i => d.tracks[i].id) });
    if (!d.tags.find(t => t.name === res.tag)) d.tags.push({ name: res.tag, category: 'Vibe' });
    idxs.forEach(i => { const t = d.tracks[i]; if (!t.confirmed.includes(res.tag)) t.confirmed.push(res.tag); });
    d.scores[res.tag] = res.scores;
    H.emit('tags', res.tag);
    return res;
  };
  H.renameCluster = async (c, label) => {
    await api('/api/clusters', 'POST', { space: H.space, cluster: c, label, level: 'cluster' });
    H.data.clusters[H.space][c].label = label;
    H.emit('clusters');
  };
  H.renameFamily = async (f, label) => {
    await api('/api/clusters', 'POST', { space: H.space, cluster: f, label, level: 'family' });
    H.data.families[H.space][f].label = label;
    H.emit('clusters');
  };
  H.createPlaylist = async (name, idxs, kind = 'playlist', data = null) => {
    const ids = idxs.map(i => H.data.tracks[i].id), res = await api('/api/playlists', 'POST', { name, ids, kind, data });
    H.data.playlists.push({ id: res.id, name, kind, data, ids });
    H.emit('playlists');
    return res;
  };
  H.deletePlaylist = async id => {
    await api(`/api/playlists/${id}`, 'DELETE');
    H.data.playlists = H.data.playlists.filter(p => p.id !== id);
    H.emit('playlists');
  };
  H.exportAll = () => api('/api/export', 'POST');
  // Likes: most recent first. Any heart toggles; every frame updates its hearts on the 'likes' event.
  H.likedList = () => H.data.liked.map(id => H.data.byId[id]).filter(i => i != null);
  H.toggleLike = async i => {
    const d = H.data, t = d.tracks[i];
    t.liked = !t.liked;
    d.liked = t.liked ? [t.id, ...d.liked.filter(x => x !== t.id)] : d.liked.filter(x => x !== t.id);
    H.emit('likes', i);
    try { await api('/api/likes', 'POST', { id: t.id, liked: t.liked }); }
    catch (e) { t.liked = !t.liked; H.emit('likes', i); H.toast(e.message); }
  };
  H.heart = i => `<button class="like${H.data.tracks[i].liked ? ' on' : ''}" data-like="${i}" title="${H.t('like')}">♥</button>`;
  H.suggested = t => {
    const s = H.data.scores;
    return H.data.tags.map(g => g.name).filter(g => s[g] && s[g][t.i] > 0.6 && !t.confirmed.includes(g) && !t.rejected.includes(g)).sort((a, b) => s[b][t.i] - s[a][t.i]);
  };

  // ---- formatting ----
  H.fmtDur = s => { s = Math.max(0, Math.round(s || 0)); const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), r = String(s % 60).padStart(2, '0'); return h ? `${h}:${String(m).padStart(2, '0')}:${r}` : `${m}:${r}`; };
  H.esc = s => String(s ?? '').replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]);

  // ---- player: queue follows whatever list a track was started from ----
  const audio = new Audio();
  audio.preload = 'auto';
  const player = {
    queue: [], pos: -1, label: '', current: null,
    play(i, { queue, label, at } = {}) {
      if (queue) { this.queue = queue; this.label = label || ''; }
      this.pos = this.queue.indexOf(i);
      if (this.pos < 0) { this.queue = [i]; this.pos = 0; this.label = label || ''; }
      this.current = i;
      audio.src = `/audio/${H.data.tracks[i].id}`;
      if (at) audio.addEventListener('loadedmetadata', () => (audio.currentTime = at), { once: true });
      audio.play().catch(() => {});
      renderPlayer(); H.emit('player', i);
    },
    audition(i) { if (this.current !== i) this.play(i, { queue: [i], label: H.t('audition'), at: H.data.tracks[i].audition }); },
    toggle() { if (this.current == null) return; audio.paused ? audio.play().catch(() => {}) : audio.pause(); },
    next() { if (this.pos < this.queue.length - 1) this.play(this.queue[this.pos + 1]); },
    prev() { if (audio.currentTime > 3 || this.pos <= 0) { audio.currentTime = 0; return; } this.play(this.queue[this.pos - 1]); },
    seek(f) { if (audio.duration) audio.currentTime = audio.duration * f; },
    get playing() { return !audio.paused; },
  };
  H.player = player;
  audio.addEventListener('ended', () => player.next());
  audio.addEventListener('play', () => { renderPlayer(true); breathe(true); H.emit('playstate', true); });
  audio.addEventListener('pause', () => { renderPlayer(true); breathe(false); H.emit('playstate', false); });
  audio.addEventListener('timeupdate', () => {
    const el = document.getElementById('player'), dur = audio.duration || 0;
    const p = el.querySelector('.prog'), tm = el.querySelector('.ptime');
    if (p) p.style.width = (dur ? (audio.currentTime / dur) * 100 : 0) + '%';
    if (tm) tm.textContent = `${H.fmtDur(audio.currentTime)} / ${H.fmtDur(dur)}`;
  });
  if ('mediaSession' in navigator) {
    try {
      for (const [a, f] of [['play', 'toggle'], ['pause', 'toggle'], ['nexttrack', 'next'], ['previoustrack', 'prev']]) navigator.mediaSession.setActionHandler(a, () => player[f]());
    } catch (e) {}
  }
  // The now-playing mark breathes on every map while audio plays: maps restyle only when the state flips.
  let breathT = null;
  function breathe(on) {
    if (on && !breathT) { let s = 0; breathT = setInterval(() => H.emit('breathe', (s = 1 - s)), 450); }
    else if (!on && breathT) { clearInterval(breathT); breathT = null; H.emit('breathe', 0); }
  }

  function renderPlayer(stateOnly) {
    const el = document.getElementById('player');
    el.classList.toggle('is-playing', !audio.paused);
    const btn = el.querySelector('[data-p="toggle"]');
    if (stateOnly && btn) { btn.textContent = audio.paused ? '▶' : '❚❚'; return; }
    const i = player.current;
    if (i == null) {
      el.innerHTML = `<div class="ptransport"><button disabled>⏮</button><button disabled class="pplay">▶</button><button disabled>⏭</button></div><span class="hint">${H.t('clickToPlay')}</span>`;
      return;
    }
    const t = H.data.tracks[i], col = H.leafColor(t.cluster);
    let s = i * 9301 + 49297;
    const bars = Array.from({ length: 90 }, () => { s = (s * 9301 + 49297) % 233280; return 18 + (s / 233280) * 82; });
    el.innerHTML = `<div class="ptransport">
        <button data-p="prev" title="${H.t('prev')}">⏮</button>
        <button data-p="toggle" class="pplay" title="${H.t('playPause')}">${audio.paused ? '▶' : '❚❚'}</button>
        <button data-p="next" title="${H.t('next')}" ${player.pos >= player.queue.length - 1 ? 'disabled' : ''}>⏭</button></div>
      <span class="eq"><i></i><i></i><i></i><i></i></span>
      ${H.heart(i)}
      <span class="pdot dot" style="--c:${col}"></span>
      <div class="pmeta"><b>${H.esc(t.title)}</b><span>${H.esc(t.artist)}</span></div>
      <span class="pfacts">${t.bpm ?? '—'} <small>BPM</small> · ${t.key ?? '—'} · ${H.esc((t.ext || '').toUpperCase())}</span>
      <div class="wave" data-p="seek" title="${H.t('seek')}">${bars.map(h => `<i style="height:${h}%"></i>`).join('')}<div class="prog"></div></div>
      <span class="ptime">0:00 / ${H.fmtDur(t.dur)}</span>
      <span class="pqueue">${H.esc(player.label || H.t('queue'))}<br><span class="n">${player.pos + 1} / ${player.queue.length}</span></span>`;
  }
  H.renderPlayer = renderPlayer;
  // Hearts anywhere (host or frames call this in the capture phase so row click handlers don't also fire).
  H.likeClick = e => {
    const b = e.target.closest('[data-like]'); if (!b) return false;
    e.stopPropagation(); e.preventDefault(); H.toggleLike(+b.dataset.like); return true;
  };
  document.addEventListener('click', e => H.likeClick(e), true);
  H.on('likes', i => document.querySelectorAll(`[data-like="${i}"]`).forEach(b => b.classList.toggle('on', H.data.tracks[i].liked)));
  document.addEventListener('click', e => {
    const b = e.target.closest('#player [data-p]'); if (!b || b.disabled) return;
    if (b.dataset.p === 'seek') { const r = b.getBoundingClientRect(); player.seek((e.clientX - r.left) / r.width); }
    else player[b.dataset.p]();
  });

  // ---- help layer + global keys (frames forward their keydowns here) ----
  H.applyHelp = doc => doc.body && doc.body.classList.toggle('show-help', H.help);
  H.setHelp = on => {
    H.help = on;
    try { localStorage.setItem('summit.help', on ? '1' : ''); } catch (e) {}
    H.applyHelp(document);
    document.querySelectorAll('iframe').forEach(f => { try { H.applyHelp(f.contentDocument); } catch (e) {} });
  };
  H.key = e => {
    if (e.ctrlKey && e.altKey && e.shiftKey && e.code === 'KeyD') { e.preventDefault(); H.setHelp(!H.help); H.toast(H.t(H.help ? 'helpOn' : 'helpOff')); return true; }
    if (e.target.closest && e.target.closest('input, select, textarea, [contenteditable]')) return false;
    if (e.code === 'Space') { e.preventDefault(); player.toggle(); return true; }
    if (e.shiftKey && e.key === 'ArrowRight') { player.next(); return true; }
    if (e.shiftKey && e.key === 'ArrowLeft') { player.prev(); return true; }
    return false;
  };
  document.addEventListener('keydown', e => H.key(e));

  H.toast = msg => {
    let el = document.getElementById('toast');
    if (!el) { el = document.createElement('div'); el.id = 'toast'; document.body.appendChild(el); }
    el.textContent = msg; el.classList.add('on');
    clearTimeout(el._t); el._t = setTimeout(() => el.classList.remove('on'), 2600);
  };

  window.Summit = H;
})();
