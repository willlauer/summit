// Summit host (index.html): library data, player, shared state (space / colour / search), API calls,
// global shortcuts and the help layer. Mode pages run in iframes and reach this as window.parent.Summit.
(function () {
  // Looks: the whole app's style, switchable (the phone has the same two: android/…/ui/Theme.kt). Each sets the
  // cluster palette and how maps draw; the rest is CSS (:root[data-look] in shared.css).
  const LOOKS = {
    classic: { label: 'Classic', pixel: false, wind: false,
      palette: ['#00f0ff', '#ff2a6d', '#05ffa1', '#b026ff', '#ff9a3d', '#5aa9ff', '#fcee0a', '#f45bff',
                '#3dffc5', '#ff5c5c', '#a8ff3d', '#ffb3d9', '#7d7dff', '#e2b45a', '#40c4aa', '#c9a6ff'] },
    pixel: { label: 'Pixel', pixel: true, wind: true, top: '#071019', bottom: '#03070d',
      palette: ['#7fdbff', '#4fa8ff', '#a9b8ff', '#7af2dc', '#c5a8ff', '#3fd0e0', '#e3f6ff', '#6f8cff',
                '#9ffff0', '#5cc8ff', '#b4e2ff', '#8f7dff', '#49e3b8', '#d6c8ff', '#66b2d9', '#2fb8ff'] },
  };
  let look = 'classic';
  try { look = LOOKS[localStorage.getItem('summit.look')] ? localStorage.getItem('summit.look') : 'classic'; } catch (e) {}
  const PALETTE = LOOKS[look].palette.slice();  // cluster data only; swapped in place when the look changes
  const UI = { focus: '#ffffff', selected: '#ffd84d', confirmed: '#3dffb0', suggested: '#8b8fa4', untagged: '#2a3344', danger: '#ff4d8d' };

  const H = {
    PALETTE, UI, LOOKS, look, data: null, space: 'combined', color: 'cluster', results: null, help: false, mode: 'map',
    listeners: {},
    on(ev, fn) { (this.listeners[ev] = this.listeners[ev] || []).push(fn); },
    off(ev, fn) { this.listeners[ev] = (this.listeners[ev] || []).filter(f => f !== fn); },
    emit(ev, ...a) { (this.listeners[ev] || []).forEach(fn => { try { fn(...a); } catch (e) { /* listener from a reloaded frame */ } }); },
  };
  try { H.space = localStorage.getItem('summit.space') || 'combined'; H.help = !!localStorage.getItem('summit.help'); } catch (e) {}
  // Map labels: genres (families) and subgenres (sub-clusters) toggle independently. Both on = follow zoom.
  H.labelShow = { fam: true, sub: true };
  try { Object.assign(H.labelShow, JSON.parse(localStorage.getItem('summit.labels') || '{}')); } catch (e) {}
  // Map in 3D (the galaxy view): every map except Flow's follows it.
  try { H.view3d = localStorage.getItem('summit.3d') === '1'; } catch (e) {}
  H.set3D = on => { H.view3d = on; try { localStorage.setItem('summit.3d', on ? '1' : ''); } catch (e) {} H.emit('3d', on); };
  H.setLabels = show => { H.labelShow = show; try { localStorage.setItem('summit.labels', JSON.stringify(show)); } catch (e) {} H.emit('labels', show); };

  const api = async (path, method = 'GET', body) => {
    const r = await fetch(path, { method, headers: body ? { 'Content-Type': 'application/json' } : {}, body: body ? JSON.stringify(body) : undefined });
    if (r.status === 409) { const e = new Error('conflict'); e.conflict = await r.json(); throw e; }
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
  H.lookStyle = () => { const l = LOOKS[H.look]; return { pixel: l.pixel, wind: l.wind, top: l.top, bottom: l.bottom }; };
  H.applyLook = doc => { if (doc && doc.documentElement) doc.documentElement.dataset.look = H.look; };
  H.setLook = k => {
    if (!LOOKS[k]) return;
    H.look = k;
    try { localStorage.setItem('summit.look', k); } catch (e) {}
    PALETTE.splice(0, PALETTE.length, ...LOOKS[k].palette); shades = null;
    H.applyLook(document);
    document.querySelectorAll('iframe').forEach(f => { try { H.applyLook(f.contentDocument); } catch (e) {} });
    H.emit('look', k);
  };
  H.applyLook(document);
  H.familyHue = f => PALETTE[f % PALETTE.length];  // "Family" colour mode: one distinct colour per family
  H.familyColor = f => palette().family[f] || PALETTE[0];

  // ---- queries ----
  // Search options (the ⚙ by the search box): how many results, and whether names, sound or both count.
  H.searchOpts = { n: 60, mode: 'both' };
  try { Object.assign(H.searchOpts, JSON.parse(localStorage.getItem('summit.search') || '{}')); } catch (e) {}
  H.setSearchOpts = o => {
    Object.assign(H.searchOpts, o);
    try { localStorage.setItem('summit.search', JSON.stringify(H.searchOpts)); } catch (e) {}
    if (H.query) H.search(H.query);  // re-run what's on show with the new options
  };
  let searchSeq = 0;
  H.search = async q => {
    const seq = ++searchSeq;
    H.query = q.trim();
    if (!q.trim()) { H.results = null; H.emit('search', null, ''); return null; }
    const { n, mode } = H.searchOpts;
    const res = await api(`/api/search?q=${encodeURIComponent(q)}&n=${n}&mode=${mode}`);
    if (seq !== searchSeq) return null;
    H.results = res.map(r => r.i);
    H.emit('search', H.results, q);
    return res;
  };
  // How many "Sounds like" tracks the Track panel lists (its picker), remembered in this browser.
  H.similarN = 10;
  try { H.similarN = +localStorage.getItem('summit.similarN') || 10; } catch (e) {}
  H.setSimilarN = n => { H.similarN = n; try { localStorage.setItem('summit.similarN', n); } catch (e) {} };
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
  H.createPlaylist = async (name, idxs, kind = 'playlist', data = null, folder = null) => {
    const ids = idxs.map(i => H.data.tracks[i].id), res = await api('/api/playlists', 'POST', { name, ids, kind, data, folder: folder || 0 });
    H.data.playlists.push({ id: res.id, name, kind, data, ids, version: res.version, folder });
    H.emit('playlists');
    return res;
  };
  H.deletePlaylist = async id => {
    await api(`/api/playlists/${id}`, 'DELETE');
    H.data.playlists = H.data.playlists.filter(p => p.id !== id);
    H.emit('playlists');
  };
  // Track ids, not indexes: a playlist can hold tracks that aren't on the map right now, and those must survive edits.
  // Every write names the version it was based on. If the phone changed the playlist meanwhile, the server refuses
  // (409) and sends the current one: edits (add / remove) are re-applied to it; a whole new order is dropped instead.
  const adopt = p => { const i = H.data.playlists.findIndex(x => x.id === p.id); if (i >= 0) H.data.playlists[i] = p; else H.data.playlists.push(p); };
  async function put(pid, body, edit) {
    const p = H.data.playlists.find(p => p.id === pid);
    try { adopt(await api(`/api/playlists/${pid}`, 'PUT', { ...body, base: p.version })); }
    catch (e) {
      if (!e.conflict) throw e;
      adopt(e.conflict.playlist);
      if (edit) adopt(await api(`/api/playlists/${pid}`, 'PUT', { ids: edit(e.conflict.playlist.ids), base: e.conflict.playlist.version }));
      else H.toast(H.t('plChangedElsewhere'));
    }
    H.emit('playlists');
  }
  H.setPlaylistIds = (pid, ids) => put(pid, { ids });
  H.editPlaylist = (pid, edit) => put(pid, { ids: edit(H.data.playlists.find(p => p.id === pid).ids) }, edit);
  H.renamePlaylist = (pid, name) => put(pid, { name });
  H.movePlaylist = (pid, folder) => put(pid, { folder: folder || 0 });
  // Folders of playlists, nestable; the rekordbox export mirrors them. Deleting one moves its contents up a level.
  const sortFolders = () => H.data.folders.sort((a, b) => a.name.localeCompare(b.name, undefined, { sensitivity: 'base' }));
  H.createFolder = async (name, parent = null) => {
    const f = await api('/api/folders', 'POST', { name, parent: parent || 0 });
    H.data.folders.push(f); sortFolders(); H.emit('playlists');
    return f;
  };
  const putFolder = async (fid, body) => {
    const f = await api(`/api/folders/${fid}`, 'PUT', body);
    H.data.folders = H.data.folders.map(x => (x.id === fid ? f : x)); sortFolders(); H.emit('playlists');
  };
  H.renameFolder = (fid, name) => putFolder(fid, { name });
  H.moveFolder = (fid, parent) => putFolder(fid, { parent: parent || 0 });
  H.deleteFolder = async fid => {
    await api(`/api/folders/${fid}`, 'DELETE');
    [H.data.playlists, H.data.folders] = await Promise.all([api('/api/playlists'), api('/api/folders')]);
    H.emit('playlists');
  };
  H.addToPlaylist = async (pid, idxs) => {
    const p = H.data.playlists.find(p => p.id === pid), have = new Set(p.ids);
    const add = [...new Set(idxs.map(i => H.data.tracks[i].id))].filter(id => !have.has(id));
    if (add.length) await H.editPlaylist(pid, ids => [...ids, ...add.filter(id => !ids.includes(id))]);
    return add.length;
  };
  H.removeFromPlaylist = (pid, id) => H.editPlaylist(pid, ids => ids.filter(x => x !== id));
  // Pick up edits made on the phone when the window comes back into view.
  async function syncPlaylists() {
    if (!H.data || document.hidden) return;
    try {
      const [fresh, folders] = await Promise.all([api('/api/playlists'), api('/api/folders')]), sig = ps => ps.map(p => `${p.id}:${p.version}`).join();
      const fsig = fs => JSON.stringify([...fs].sort((a, b) => a.id - b.id));
      if (sig(fresh) !== sig(H.data.playlists) || fsig(folders) !== fsig(H.data.folders)) { H.data.playlists = fresh; H.data.folders = folders; H.emit('playlists'); }
    } catch (e) {}
  }
  document.addEventListener('visibilitychange', syncPlaylists);
  window.addEventListener('focus', syncPlaylists);
  setInterval(syncPlaylists, 20000);
  H.playlistTracks = p => p.ids.map(id => H.data.byId[id]).filter(i => i != null);
  // "Gig › Warmup": where a playlist is filed, for lists that show playlists from every folder.
  H.folderPath = fid => {
    const out = [], seen = new Set();
    for (let f = H.data.folders.find(x => x.id === fid); f && !seen.has(f.id); f = H.data.folders.find(x => x.id === f.parent)) { seen.add(f.id); out.unshift(f.name); }
    return out.join(' › ');
  };
  H.playlistLabel = p => { const path = p.folder ? H.folderPath(p.folder) : ''; return path ? `${path} › ${p.name}` : p.name; };
  // "Add to playlist" menu, drawn in whichever document the button lives in (the host or a mode frame).
  // Flows are playlists too, but their tracks come from the route, so they aren't offered here.
  H.playlistMenu = (anchor, idxs) => {
    const doc = anchor.ownerDocument, old = doc.getElementById('plmenu');
    if (old) { old.close(); if (old.anchor === anchor) return; }
    if (!idxs.length) return;
    const ids = idxs.map(i => H.data.tracks[i].id), pls = H.data.playlists.filter(p => p.kind === 'playlist');
    const m = doc.createElement('div');
    m.id = 'plmenu'; m.className = 'menu'; m.anchor = anchor;
    m.innerHTML = `<div class="mhead">${H.t('addToN', { n: idxs.length })}</div>` + pls.map(p => {
      const has = ids.every(id => p.ids.includes(id));
      return `<button data-pl="${p.id}" class="${has ? 'has' : ''}"><span class="grow">${has ? '✓ ' : ''}${H.esc(H.playlistLabel(p))}</span><span class="muted small">${p.ids.length}</span></button>`;
    }).join('') + `<button data-pl="new" class="new">${H.t('newPlaylistDots')}</button>`;
    doc.body.appendChild(m);
    const r = anchor.getBoundingClientRect(), w = doc.defaultView;
    m.style.left = Math.max(8, Math.min(r.left, w.innerWidth - m.offsetWidth - 8)) + 'px';
    m.style.top = (r.bottom + 6 + m.offsetHeight < w.innerHeight ? r.bottom + 6 : Math.max(8, r.top - 6 - m.offsetHeight)) + 'px';
    const outside = e => { if (!m.contains(e.target) && !anchor.contains(e.target)) m.close(); };
    const esc = e => { if (e.key === 'Escape') { e.stopPropagation(); m.close(); } };
    const elsewhere = d => { if (d !== doc) m.close(); };  // a click in another frame never reaches this document
    m.close = () => { m.remove(); doc.removeEventListener('mousedown', outside, true); doc.removeEventListener('keydown', esc, true); H.off('mousedown', elsewhere); };
    doc.addEventListener('mousedown', outside, true); doc.addEventListener('keydown', esc, true); H.on('mousedown', elsewhere);
    m.onclick = async e => {
      const b = e.target.closest('[data-pl]'); if (!b) return;
      m.close();
      try {
        if (b.dataset.pl === 'new') {
          const n = w.prompt(H.t('playlistName'), H.t('newPlaylist'));
          if (n) { await H.createPlaylist(n, idxs); H.toast(H.t('saved', { x: n })); }
          return;
        }
        const p = pls.find(p => p.id === +b.dataset.pl), k = await H.addToPlaylist(p.id, idxs);
        H.toast(k ? H.t('addedTo', { n: k, x: p.name }) : H.t('alreadyIn', { x: p.name }));
      } catch (err) { H.toast(err.message); }
    };
  };

  // ---- library updates: files not on the map yet, and the `summit update` run that adds them ----
  H.pending = () => api('/api/pending');
  H.update = { state: () => api('/api/update'), start: () => api('/api/update', 'POST'), stop: () => api('/api/update', 'DELETE') };

  // ---- navigation: Back / Forward across modes and what each mode is showing (open track, selection, zoom) ----
  // Mode pages register a view {save, load} once ready; anything that changes what you're looking at calls
  // H.navPush() first, so Back returns to the state before it.
  const views = {}, viewWaits = {}, nav = { back: [], fwd: [], busy: false };
  H.registerView = (mode, v) => { views[mode] = v; (viewWaits[mode] || []).forEach(r => r(v)); delete viewWaits[mode]; };
  const viewOf = mode => (views[mode] ? Promise.resolve(views[mode]) : new Promise(r => (viewWaits[mode] = viewWaits[mode] || []).push(r)));
  const here = () => ({ mode: H.mode, q: H.query || '', state: views[H.mode] ? views[H.mode].save() : null });
  H.navPush = () => {
    if (nav.busy || !H.data) return;
    const loc = here(), top = nav.back[nav.back.length - 1];
    if (!top || JSON.stringify(top) !== JSON.stringify(loc)) { nav.back.push(loc); if (nav.back.length > 100) nav.back.shift(); }
    nav.fwd = [];
    H.emit('nav');
  };
  async function travel(from, to) {
    if (!from.length || nav.busy) return;
    const loc = from.pop();
    to.push(here());
    nav.busy = true;
    try {
      H.setMode(loc.mode);
      H.query = loc.q; H.emit('query', loc.q);
      const v = await viewOf(loc.mode);
      if (loc.state) await v.load(loc.state);
    } finally { nav.busy = false; H.emit('nav'); }
  }
  H.back = () => travel(nav.back, nav.fwd);
  H.forward = () => travel(nav.fwd, nav.back);
  H.canBack = () => nav.back.length > 0;
  H.canForward = () => nav.fwd.length > 0;
  // Go to a track: switch to the map, centre on it and open it. Back returns to wherever you were.
  H.goTo = async i => {
    if (i == null) return;
    H.navPush();
    nav.busy = true;
    try { H.setMode('map'); await (await viewOf('map')).show(i); } finally { nav.busy = false; H.emit('nav'); }
  };
  // Explore similar: start a tree in Tree mode from this track.
  H.explore = async i => {
    if (i == null) return;
    H.navPush();
    nav.busy = true;
    try { H.setMode('tree'); await (await viewOf('tree')).plant(i); } finally { nav.busy = false; H.emit('nav'); }
  };
  // Double-click selects the word under the cursor; going somewhere shouldn't leave that behind.
  H.dblGoTo = (e, i) => { e.preventDefault(); e.view.getSelection().removeAllRanges(); H.goTo(i); };
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
  audio.addEventListener('play', () => { renderPlayer(true); H.emit('playstate', true); });
  audio.addEventListener('pause', () => { renderPlayer(true); H.emit('playstate', false); });
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
    el.innerHTML = `<div class="ptransport">
        <button data-p="prev" title="${H.t('prev')}">⏮</button>
        <button data-p="toggle" class="pplay" title="${H.t('playPause')}">${audio.paused ? '▶' : '❚❚'}</button>
        <button data-p="next" title="${H.t('next')}" ${player.pos >= player.queue.length - 1 ? 'disabled' : ''}>⏭</button></div>
      <span class="eq"><i></i><i></i><i></i><i></i></span>
      ${H.heart(i)}<button class="addpl" data-p="addpl" title="${H.t('addToPl')}">＋</button>
      <span class="part" style="--c:${col};background-image:url(/art/${t.id}?s=${LOOKS[H.look].pixel ? 21 : 96})"></span>
      <div class="pmeta" title="${H.t('goToTip')}"><b>${H.esc(t.title)}</b><span>${H.esc(t.artist)}</span></div>
      <span class="pfacts">${t.bpm ?? '—'} <small>BPM</small> · ${t.key ?? '—'} · ${H.esc((t.ext || '').toUpperCase())}</span>
      <div class="wave" data-p="seek" title="${H.t('seek')}"><div class="prog"></div></div>
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
    else if (b.dataset.p === 'addpl') H.playlistMenu(b, [player.current]);
    else player[b.dataset.p]();
  });
  document.addEventListener('dblclick', e => { if (e.target.closest('#player .pmeta')) H.dblGoTo(e, player.current); });

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
    if (e.metaKey && (e.key === '[' || e.key === ']')) { e.preventDefault(); e.key === '[' ? H.back() : H.forward(); return true; }
    if (e.target.closest && e.target.closest('input, select, textarea, [contenteditable]')) return false;
    if (e.altKey && (e.key === 'ArrowLeft' || e.key === 'ArrowRight')) { e.preventDefault(); e.key === 'ArrowLeft' ? H.back() : H.forward(); return true; }
    if (e.code === 'Space') { e.preventDefault(); player.toggle(); return true; }
    if (e.shiftKey && e.key === 'ArrowRight') { player.next(); return true; }
    if (e.shiftKey && e.key === 'ArrowLeft') { player.prev(); return true; }
    return false;
  };
  document.addEventListener('keydown', e => H.key(e));
  // Mouse side buttons (frames forward theirs too).
  H.mouse = e => { if (e.button === 3) { e.preventDefault(); H.back(); } if (e.button === 4) { e.preventDefault(); H.forward(); } };
  document.addEventListener('mouseup', e => H.mouse(e));
  // Every document (host and frames) reports its mousedowns, so popups can close on a click in another frame.
  document.addEventListener('mousedown', () => H.emit('mousedown', document), true);

  H.toast = msg => {
    let el = document.getElementById('toast');
    if (!el) { el = document.createElement('div'); el.id = 'toast'; document.body.appendChild(el); }
    el.textContent = msg; el.classList.add('on');
    clearTimeout(el._t); el._t = setTimeout(() => el.classList.remove('on'), 2600);
  };

  window.Summit = H;
})();
