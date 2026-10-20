// Galaxy: the map renderer shared by the desktop modes and the phone app (three.js / WebGL).
// Tracks are glowing points; positions morph between embedding spaces and between the flat map and a 3D view.
// Drag pans, pinch / wheel zooms; in 3D, a two-finger twist (or right-drag / shift-drag) turns and two fingers up / down tilt. Tap / double-tap / long-press pick the nearest track.
// Paths, rings and page drawings sit on a 2D canvas overlay; labels and the now-playing mark are HTML on top.
import * as THREE from '/web/vendor/three.module.min.js';

const FOV = 35, TAN = Math.tan((FOV / 2) * Math.PI / 180);
const ease = t => 1 - Math.pow(1 - t, 3);

// Two styles (the app's looks): Classic draws round glowing stars; Pixel snaps square stars to a grid of pixel units
// (uPx device px) with a stepped glow, over a night sky with wind and blown snow (the phone's GalaxyRenderer shaders).
const VERT = /* glsl */`
  attribute vec3 pFrom; attribute vec3 p2; attribute vec3 p3; attribute vec3 color; attribute float alpha; attribute float size;
  uniform float uTrans, uMorph, uSize, uPR, uZoom, uRef, uHalo, uPixel, uPx;
  uniform vec2 uRes;
  varying vec3 vColor; varying float vAlpha; varying float vCells;
  void main() {
    vec3 p = mix(pFrom, mix(p2, p3, uMorph), uTrans);
    vec4 mv = modelViewMatrix * vec4(p, 1.0);
    float persp = mix(1.0, clamp(uRef / -mv.z, 0.35, 2.6), uMorph);
    float sz = uSize * size * uPR * uZoom * persp * (uHalo > 0.5 ? 3.2 : 1.0);
    vec4 clip = projectionMatrix * mv;
    vCells = 0.0;
    if (uPixel > 0.5) {  // whole pixel units, centred on the grid
      vCells = max(1.0, floor(sz / uPx + 0.5));
      sz = vCells * uPx;
      vec2 px = (clip.xy / clip.w * 0.5 + 0.5) * uRes;
      px = (floor(px / uPx) + (mod(vCells, 2.0) == 1.0 ? 0.5 : 0.0)) * uPx;
      clip = vec4((px / uRes * 2.0 - 1.0) * clip.w, clip.z, clip.w);
    }
    gl_PointSize = sz;
    gl_Position = clip;
    vColor = color; vAlpha = alpha;
  }`;
const FRAG_CORE = /* glsl */`
  varying vec3 vColor; varying float vAlpha; varying float vCells;
  void main() {
    if (vCells > 0.0) {  // a square star; bigger ones get a pale centre pixel
      vec2 q = floor(gl_PointCoord * vCells);
      float centre = (vCells >= 3.0 && q.x == floor(vCells / 2.0) && q.y == floor(vCells / 2.0)) ? 1.0 : 0.0;
      gl_FragColor = vec4(mix(mix(vColor, vec3(1.0), 0.15) * 0.82, vec3(0.92, 0.97, 1.0), centre * 0.7), vAlpha * 0.9);
      return;
    }
    float r = length(gl_PointCoord - 0.5);
    float a = smoothstep(0.5, 0.36, r);
    if (a <= 0.0) discard;
    vec3 c = mix(vColor, vec3(1.0), smoothstep(0.26, 0.0, r) * 0.35);  // hot centre
    gl_FragColor = vec4(c, a * vAlpha);
  }`;
const FRAG_HALO = /* glsl */`
  varying vec3 vColor; varying float vAlpha; varying float vCells;
  uniform float uGlow;
  void main() {
    float a;
    if (vCells > 0.0) {  // glow in two hard steps, on the pixel grid
      vec2 q = (floor(gl_PointCoord * vCells) + 0.5) / vCells - 0.5;
      float r = max(abs(q.x), abs(q.y)) * 2.0;
      a = (r < 0.45 ? 1.0 : r < 0.8 ? 0.4 : 0.0) * uGlow * vAlpha * vAlpha;
    } else {
      float r = length(gl_PointCoord - 0.5) * 2.0;
      a = exp(-r * r * 5.0) * uGlow * vAlpha * vAlpha;
    }
    if (a < 0.004) discard;
    gl_FragColor = vec4(vColor * a, a);
  }`;
// Pixel style's sky: a smooth, very dark gradient; gusts streak across and snow blows sideways in two layers.
const SKY_VERT = /* glsl */`void main() { gl_Position = vec4(position.xy, 0.0, 1.0); }`;
const SKY_FRAG = /* glsl */`
  uniform vec2 uRes; uniform float uTime, uPx; uniform vec3 uTop, uBottom;
  float hash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
  void main() {
    float y = gl_FragCoord.y / uRes.y;
    vec3 c = mix(uBottom, uTop, y * y) + (hash(gl_FragCoord.xy) - 0.5) / 255.0;
    vec2 cell = floor(vec2(gl_FragCoord.x, uRes.y - gl_FragCoord.y) / uPx);
    float t = uTime, cols = uRes.x / uPx;
    float gust = 0.55 + 0.45 * sin(t * 0.21) * sin(t * 0.13 + 1.3);
    float lane = floor(cell.y / 11.0), hl = hash(vec2(lane, 1.7));
    float wob = floor(sin(cell.x * 0.035 + t * 0.6 + lane) * 1.5);
    if (mod(cell.y + wob, 11.0) == 0.0 && hl > 0.5) {
      float speed = 22.0 + hl * 40.0, len = 18.0 + hash(vec2(lane, 4.2)) * 40.0, span = cols + len * 2.0 + 60.0;
      float head = mod(t * speed * (0.6 + gust * 0.6) + hash(vec2(lane, 9.1)) * span, span) - len;
      float d = head - cell.x;
      if (d >= 0.0 && d < len) c = mix(c, vec3(0.70, 0.88, 1.0), (1.0 - d / len) * 0.13 * gust);
    }
    if (hash(floor(vec2(cell.x - t * (26.0 + 18.0 * gust), cell.y - t * 6.0)) + 0.37) > 0.9986) c = mix(c, vec3(0.92, 0.97, 1.0), 0.5);
    if (hash(floor(vec2(cell.x - t * (11.0 + 8.0 * gust) + floor(sin(cell.y * 0.07 + t) * 2.0), cell.y - t * 3.0)) + 7.1) > 0.9982) c = mix(c, vec3(0.75, 0.87, 1.0), 0.28);
    gl_FragColor = vec4(c, 1.0);
  }`;

export function createGalaxy(container, opts = {}) {
  const o = { dot: 5, glow: 0.22, hitPx: 14, touchHitPx: 24, tooltip: null, onTap: null, onDoubleTap: null, onLongPress: null, onHover: null, onView: null, ...opts };
  if (getComputedStyle(container).position === 'static') container.style.position = 'relative';
  const root = document.createElement('div');
  root.className = 'galaxy';
  root.style.cssText = 'position:absolute;inset:0;overflow:hidden;touch-action:none;user-select:none;-webkit-user-select:none;cursor:grab';
  container.appendChild(root);

  const renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true, powerPreference: 'high-performance' });
  renderer.setPixelRatio(Math.min(devicePixelRatio || 1, 2));
  renderer.setClearColor(0x000000, 0);
  const gl = renderer.domElement;
  gl.style.cssText = 'position:absolute;inset:0;width:100%;height:100%;display:block';
  root.appendChild(gl);
  const over = document.createElement('canvas');
  over.style.cssText = 'position:absolute;inset:0;width:100%;height:100%;pointer-events:none';
  root.appendChild(over);
  const ctx = over.getContext('2d');
  const labelLayer = document.createElement('div');
  labelLayer.className = 'g-labels';
  labelLayer.style.cssText = 'position:absolute;inset:0;pointer-events:none';
  root.appendChild(labelLayer);
  const markLayer = document.createElement('div');
  markLayer.style.cssText = 'position:absolute;inset:0;pointer-events:none';
  root.appendChild(markLayer);
  const tip = document.createElement('div');
  tip.className = 'g-tip';
  tip.style.cssText = 'position:absolute;pointer-events:none;display:none;z-index:3';
  root.appendChild(tip);

  const scene = new THREE.Scene(), camera = new THREE.PerspectiveCamera(FOV, 1, 0.01, 2000);
  const geo = new THREE.BufferGeometry();
  const uni = { uTrans: { value: 1 }, uMorph: { value: 0 }, uSize: { value: o.dot }, uPR: { value: renderer.getPixelRatio() }, uZoom: { value: 1 }, uRef: { value: 30 }, uHalo: { value: 0 }, uGlow: { value: o.glow },
    uPixel: { value: 0 }, uPx: { value: 2 * renderer.getPixelRatio() }, uRes: { value: new THREE.Vector2(1, 1) } };
  const haloMat = new THREE.ShaderMaterial({ vertexShader: VERT, fragmentShader: FRAG_HALO, uniforms: { ...uni, uHalo: { value: 1 } }, transparent: true, depthTest: false, depthWrite: false, blending: THREE.AdditiveBlending });
  const coreMat = new THREE.ShaderMaterial({ vertexShader: VERT, fragmentShader: FRAG_CORE, uniforms: uni, transparent: true, depthTest: false, depthWrite: false });
  for (const k of Object.keys(uni)) if (k !== 'uHalo') haloMat.uniforms[k] = uni[k];  // shared uniform objects
  const halo = new THREE.Points(geo, haloMat), core = new THREE.Points(geo, coreMat);
  halo.frustumCulled = core.frustumCulled = false;
  scene.add(halo, core);
  // Pixel style: the sky, drawn behind everything as one full-screen quad.
  const skyUni = { uRes: uni.uRes, uPx: uni.uPx, uTime: { value: 0 }, uTop: { value: new THREE.Color('#071019') }, uBottom: { value: new THREE.Color('#03070d') } };
  const skyScene = new THREE.Scene(), skyCam = new THREE.Camera();
  const skyMesh = new THREE.Mesh(new THREE.PlaneGeometry(2, 2), new THREE.ShaderMaterial({ vertexShader: SKY_VERT, fragmentShader: SKY_FRAG, uniforms: skyUni, depthTest: false, depthWrite: false }));
  skyMesh.frustumCulled = false;
  skyScene.add(skyMesh);
  const style = { pixel: false, wind: false };
  const born = performance.now();
  /** Switch looks: {pixel, wind, top, bottom} (sky colours as CSS colours). */
  function setStyle(s) {
    Object.assign(style, s);
    uni.uPixel.value = style.pixel ? 1 : 0;
    uni.uSize.value = style.pixel ? o.dot * 0.62 : o.dot;
    uni.uGlow.value = style.pixel ? 0.09 : o.glow;
    if (s.top) skyUni.uTop.value.set(s.top);
    if (s.bottom) skyUni.uBottom.value.set(s.bottom);
    renderer.setClearColor(0x000000, 0);
    kick();
  }

  // ---- data ----
  let n = 0, P2 = null, P3 = null, PF = null;   // Float32Array(n*3): flat map, 3D, transition start
  const D = { trans: 1, morph: 0, transAnim: null, morphAnim: null };
  const posOf = (i, out = new THREE.Vector3()) => {  // CPU mirror of the vertex shader
    const k = i * 3, m = D.morph, t = D.trans;
    const tx = P2[k] + (P3[k] - P2[k]) * m, ty = P2[k + 1] + (P3[k + 1] - P2[k + 1]) * m, tz = P2[k + 2] + (P3[k + 2] - P2[k + 2]) * m;
    return out.set(PF[k] + (tx - PF[k]) * t, PF[k + 1] + (ty - PF[k + 1]) * t, PF[k + 2] + (tz - PF[k + 2]) * t);
  };
  function setPositions(x, y, xyz, animate = true) {
    const first = !P2 || x.length !== n;
    const from = new Float32Array(x.length * 3);
    if (!first) { const v = new THREE.Vector3(); for (let i = 0; i < n; i++) { posOf(i, v); from.set([v.x, v.y, v.z], i * 3); } }
    n = x.length;
    P2 = new Float32Array(n * 3); P3 = new Float32Array(n * 3);
    for (let i = 0; i < n; i++) {
      P2[i * 3] = x[i]; P2[i * 3 + 1] = y[i];
      if (xyz) { P3[i * 3] = xyz[0][i]; P3[i * 3 + 1] = xyz[1][i]; P3[i * 3 + 2] = xyz[2][i]; } else { P3[i * 3] = x[i]; P3[i * 3 + 1] = y[i]; }
    }
    has3D = !!xyz;
    PF = first ? P2.slice() : from;
    geo.setAttribute('pFrom', new THREE.BufferAttribute(PF, 3));
    geo.setAttribute('p2', new THREE.BufferAttribute(P2, 3));
    geo.setAttribute('p3', new THREE.BufferAttribute(P3, 3));
    geo.setAttribute('position', geo.getAttribute('p2'));
    if (!geo.getAttribute('color') || geo.getAttribute('color').count !== n) {
      geo.setAttribute('color', new THREE.BufferAttribute(new Float32Array(n * 3).fill(0.7), 3));
      geo.setAttribute('alpha', new THREE.BufferAttribute(new Float32Array(n).fill(1), 1));
      geo.setAttribute('size', new THREE.BufferAttribute(new Float32Array(n).fill(1), 1));
    }
    if (first || !animate) { D.trans = 1; D.transAnim = null; } else { D.trans = 0; D.transAnim = { t0: performance.now(), dur: 900 }; }
    uni.uTrans.value = D.trans;
    projDirty = true; kick();
  }
  let has3D = false;
  const tmpC = new THREE.Color();
  function setColors(cols) {  // CSS colours, one per point
    const a = geo.getAttribute('color');
    cols.forEach((c, i) => { tmpC.set(c); a.setXYZ(i, tmpC.r, tmpC.g, tmpC.b); });
    a.needsUpdate = true; kick();
  }
  function setAlpha(vals) {  // null = all visible; a number or an array
    const a = geo.getAttribute('alpha');
    for (let i = 0; i < n; i++) a.setX(i, vals == null ? 1 : typeof vals === 'number' ? vals : vals[i]);
    a.needsUpdate = true; kick();
  }
  function setSizes(vals) {
    const a = geo.getAttribute('size');
    for (let i = 0; i < n; i++) a.setX(i, vals == null ? 1 : typeof vals === 'number' ? vals : vals[i]);
    a.needsUpdate = true; kick();
  }

  // ---- camera: target T, distance d, azimuth th, tilt ph (0 = straight down). cur eases toward goal. ----
  const cur = { tx: 0, ty: 0, tz: 0, d: 30, th: 0, ph: 0 }, goal = { ...cur };
  let W = 0, Hh = 0, fitD = 30, vel = null;
  const worldPerPx = () => (2 * cur.d * TAN) / Hh;
  function applyCamera() {
    const { tx, ty, tz, d, th, ph } = cur;
    camera.position.set(tx + d * Math.sin(ph) * Math.sin(th), ty - d * Math.sin(ph) * Math.cos(th), tz + d * Math.cos(ph));
    camera.up.set(-Math.sin(th), Math.cos(th), 0);
    camera.lookAt(tx, ty, tz);
    camera.updateMatrixWorld();
    uni.uZoom.value = Math.max(0.85, Math.min(2.4, Math.pow(fitD / d, 0.33)));
    uni.uRef.value = d;
    projDirty = true;
  }
  function resize() {
    const r = root.getBoundingClientRect();
    if (!r.width || !r.height) return;
    W = r.width; Hh = r.height;
    renderer.setSize(W, Hh, false);
    uni.uRes.value.set(W * renderer.getPixelRatio(), Hh * renderer.getPixelRatio());
    over.width = W * renderer.getPixelRatio(); over.height = Hh * renderer.getPixelRatio();
    camera.aspect = W / Hh; camera.updateProjectionMatrix();
    if (!fitted && n) fit(false);
    kick();
  }
  let fitted = false;
  function bounds(idxs) {
    let x0 = Infinity, y0 = Infinity, z0 = Infinity, x1 = -Infinity, y1 = -Infinity, z1 = -Infinity;
    const v = new THREE.Vector3(), m = D.morph;
    const each = i => {  // where the point is heading, not where it is mid-animation
      const k = i * 3; v.set(P2[k] + (P3[k] - P2[k]) * m, P2[k + 1] + (P3[k + 1] - P2[k + 1]) * m, P2[k + 2] + (P3[k + 2] - P2[k + 2]) * m);
      x0 = Math.min(x0, v.x); x1 = Math.max(x1, v.x); y0 = Math.min(y0, v.y); y1 = Math.max(y1, v.y); z0 = Math.min(z0, v.z); z1 = Math.max(z1, v.z);
    };
    if (idxs) idxs.forEach(each); else for (let i = 0; i < n; i++) each(i);
    return { x0, y0, z0, x1, y1, z1 };
  }
  function distFor(b, pad = 1.12) {
    const w = Math.max(b.x1 - b.x0, 0.5), h = Math.max(b.y1 - b.y0, 0.5), s = Math.max(h, w / (W / Hh)) * pad;
    return s / (2 * TAN) + (D.morph ? (b.z1 - b.z0) / 2 : 0);
  }
  function fit(animate = true, idxs = null) {
    if (!n) return;
    if (W <= 1 || Hh <= 1) { fitted = false; return; }  // hidden (a background tab): fit once it has a size
    const b = bounds(idxs);
    if (!idxs) fitD = distFor(b);
    Object.assign(goal, { tx: (b.x0 + b.x1) / 2, ty: (b.y0 + b.y1) / 2, tz: (b.z0 + b.z1) / 2, d: idxs ? Math.max(distFor(b, 1.4), fitD * 0.08) : fitD });
    if (!animate) Object.assign(cur, goal);
    fitted = true; vel = null; applyCamera(); kick();
  }
  // Centre on a track, zooming in to at most `zoom` times the full view.
  function centerOn(i, zoom = 3.3) {
    const v = posOf(i);
    Object.assign(goal, { tx: v.x, ty: v.y, tz: v.z, d: Math.min(cur.d, fitD / zoom) });
    vel = null; kick();
  }
  const getView = () => ({ ...goal, morph: D.morph });
  function setView(v, animate = true) {
    if (!v) return;
    Object.assign(goal, { tx: v.tx, ty: v.ty, tz: v.tz || 0, d: v.d, th: v.th || 0, ph: v.ph || 0 });
    if (!animate) Object.assign(cur, goal);
    vel = null; kick();
  }
  const zoomLevel = () => fitD / cur.d;  // 1 = whole map

  // 2D ↔ 3D: points morph, the camera tilts.
  function set3D(on, animate = true) {
    if (on && !has3D) return false;
    const to = on ? 1 : 0;
    if (to === D.morph && !D.morphAnim) return true;
    D.morphAnim = { from: D.morph, to, t0: performance.now(), dur: animate ? 1100 : 1 };
    goal.ph = on ? 0.95 : 0; goal.th = on ? 0.5 : 0;
    goal.tz = 0;
    if (on) goal.d = Math.max(goal.d, fitD * 0.9);
    vel = null; kick();
    return true;
  }
  const is3D = () => D.morph > 0.5 || (D.morphAnim && D.morphAnim.to === 1);

  // ---- projection cache: screen px of every point, for picking and overlays ----
  let proj = new Float32Array(0), projZ = new Float32Array(0), projDirty = true;
  const v3 = new THREE.Vector3();
  function project() {
    if (!projDirty) return;
    if (proj.length !== n * 2) { proj = new Float32Array(n * 2); projZ = new Float32Array(n); }
    for (let i = 0; i < n; i++) {
      posOf(i, v3).project(camera);
      proj[i * 2] = (v3.x + 1) * W / 2; proj[i * 2 + 1] = (1 - v3.y) * Hh / 2; projZ[i] = v3.z;
    }
    projDirty = false;
  }
  const toPx = i => { project(); return [proj[i * 2], proj[i * 2 + 1]]; };
  function toPxXYZ(x, y, z = 0) { v3.set(x, y, z).project(camera); return [(v3.x + 1) * W / 2, (1 - v3.y) * Hh / 2, v3.z]; }
  // Screen px → the point on the z=0 plane (or the target's depth in 3D) under it.
  const ray = new THREE.Raycaster(), plane = new THREE.Plane(new THREE.Vector3(0, 0, 1), 0), hit = new THREE.Vector3();
  function toData(px, py) {
    ray.setFromCamera(new THREE.Vector2((px / W) * 2 - 1, -(py / Hh) * 2 + 1), camera);
    plane.constant = -cur.tz;
    return ray.ray.intersectPlane(plane, hit) ? [hit.x, hit.y, hit.z] : [cur.tx, cur.ty, cur.tz];
  }
  function pick(px, py, radius = o.hitPx) {
    project();
    const a = geo.getAttribute('alpha'); let best = -1, bd = radius * radius, bz = Infinity;
    for (let i = 0; i < n; i++) {
      if (a.getX(i) < 0.05) continue;
      const dx = proj[i * 2] - px, dy = proj[i * 2 + 1] - py, e = dx * dx + dy * dy;
      if (e < bd || (e === bd && projZ[i] < bz)) { bd = e; best = i; bz = projZ[i]; }
    }
    return best < 0 ? null : best;
  }

  // ---- overlays ----
  let rings = [], paths = [], drawFns = [], labels = [], playing = null, selected = null;
  const setRings = r => { rings = r || []; kick(); };            // [{i, color, r, width, fill}]
  const setPaths = p => { paths = p || []; kick(); };            // [{idxs | pts:[[x,y,z]], color, width, glow, dash, arrows}]
  const addDraw = fn => { drawFns.push(fn); kick(); return () => { drawFns = drawFns.filter(f => f !== fn); kick(); }; };
  // Pixel style draws lines and rings in whole 2 px units.
  const U = 2, snapU = v => Math.floor(v / U) * U;
  function pxLine(pts, color, w = 1) {
    ctx.fillStyle = color;
    for (let k = 1; k < pts.length; k++) {
      const [ax, ay] = pts[k - 1], [bx, by] = pts[k], n = Math.min(800, Math.max(1, Math.round(Math.max(Math.abs(bx - ax), Math.abs(by - ay)) / U)));
      for (let s = 0; s <= n; s++) ctx.fillRect(snapU(ax + (bx - ax) * s / n) - (w - 1) * U / 2, snapU(ay + (by - ay) * s / n) - (w - 1) * U / 2, U * w, U * w);
    }
  }
  function pxRing(x, y, r, color, w = 1) {  // a square ring with stepped corners
    const h = snapU(r), x0 = snapU(x) - h, y0 = snapU(y) - h, s = 2 * h;
    ctx.fillStyle = color;
    ctx.fillRect(x0 + 2 * U, y0, s - 4 * U, U * w); ctx.fillRect(x0 + 2 * U, y0 + s - U * w, s - 4 * U, U * w);
    ctx.fillRect(x0, y0 + 2 * U, U * w, s - 4 * U); ctx.fillRect(x0 + s - U * w, y0 + 2 * U, U * w, s - 4 * U);
    ctx.fillRect(x0 + U, y0 + U, U, U); ctx.fillRect(x0 + s - 2 * U, y0 + U, U, U); ctx.fillRect(x0 + U, y0 + s - 2 * U, U, U); ctx.fillRect(x0 + s - 2 * U, y0 + s - 2 * U, U, U);
  }
  let glints = [];
  const setGlints = g => { glints = g || []; kick(); };   // a few bright stars per group (pixel style)
  function drawOverlay() {
    const pr = renderer.getPixelRatio();
    ctx.setTransform(pr, 0, 0, pr, 0, 0);
    ctx.clearRect(0, 0, W, Hh);
    project();
    if (style.pixel) {
      const a = geo.getAttribute('alpha');
      ctx.fillStyle = 'rgba(191,239,255,.4)';
      for (const i of glints) {
        if (a.getX(i) < 0.5) continue;
        const [x0, y0] = toPx(i), x = snapU(x0), y = snapU(y0);
        ctx.fillRect(x - 2 * U, y, 5 * U, U); ctx.fillRect(x, y - 2 * U, U, 5 * U);
      }
    }
    for (const p of paths) {
      const pts = p.idxs ? p.idxs.map(i => toPx(i)) : p.pts.map(q => toPxXYZ(...q));
      if (pts.length < 2) continue;
      if (style.pixel) { if (p.glow) pxLine(pts, p.glow, 3); pxLine(pts, p.color || '#fff'); continue; }
      ctx.save();
      ctx.lineJoin = 'round'; ctx.lineCap = 'round';
      ctx.beginPath(); pts.forEach(([x, y], k) => (k ? ctx.lineTo(x, y) : ctx.moveTo(x, y)));
      if (p.glow) { ctx.strokeStyle = p.glow; ctx.lineWidth = (p.width || 2) * 5; ctx.stroke(); }
      ctx.strokeStyle = p.color || '#fff'; ctx.lineWidth = p.width || 2;
      if (p.dash) ctx.setLineDash(p.dash);
      ctx.stroke();
      ctx.restore();
    }
    for (const r of rings) {
      const [x, y] = toPx(r.i);
      if (style.pixel) { pxRing(x, y, (r.r || 8) + 1, r.color || '#fff', (r.width || 1.5) > 1.6 ? 2 : 1); continue; }
      ctx.beginPath(); ctx.arc(x, y, r.r || 8, 0, Math.PI * 2);
      if (r.fill) { ctx.fillStyle = r.fill; ctx.fill(); }
      ctx.strokeStyle = r.color || '#fff'; ctx.lineWidth = r.width || 1.5; ctx.stroke();
    }
    for (const f of drawFns) { ctx.save(); f(ctx, { toPx, toPxXYZ, worldPerPx: worldPerPx(), W, H: Hh }); ctx.restore(); }
    placeMarks();
    placeLabels();
  }
  // Now-playing and selected marks: HTML, so they can pulse with CSS without redrawing the map.
  const mk = cls => { const el = document.createElement('div'); el.className = cls; el.style.display = 'none'; markLayer.appendChild(el); return el; };
  const playEl = mk('g-playing'), selEl = mk('g-selected');
  function placeMark(el, i) {
    if (i == null || !n) { el.style.display = 'none'; return; }
    const [x, y] = toPx(i);
    el.style.display = ''; el.style.transform = `translate(${x}px, ${y}px)`;
  }
  function placeMarks() { placeMark(playEl, playing); placeMark(selEl, selected); }
  function setPlaying(i, on) { playing = i; playEl.classList.toggle('on', !!on); kick(); }
  const setSelected = i => { selected = i; kick(); };

  // Labels: [{text, color, x, y, z?, idxs?, size: 'family' | 'leaf', min, max}] (zoom range, 1 = whole map).
  // Positioned at the mean of their tracks' current positions when idxs is given; overlapping lower-priority ones hide.
  let labelEls = [];
  function setLabels(list) {
    labels = list || [];
    labelLayer.innerHTML = '';
    labelEls = labels.map(l => {
      const el = document.createElement('span');
      el.className = 'g-label ' + (l.size || 'family');
      el.textContent = l.text; el.style.setProperty('--c', l.color || '#fff');
      labelLayer.appendChild(el);
      return { el, l, w: 0, h: 0 };
    });
    kick();
  }
  function placeLabels() {
    if (!labelEls.length) return;
    const z = zoomLevel(), taken = [];
    for (const e of labelEls) {
      const l = e.l;
      if (z < (l.min ?? 0) || z >= (l.max ?? Infinity)) { e.el.style.display = 'none'; continue; }
      let x, y, depth;
      if (l.idxs) { let sx = 0, sy = 0; for (const i of l.idxs) { const p = toPx(i); sx += p[0]; sy += p[1]; } x = sx / l.idxs.length; y = sy / l.idxs.length; depth = 0; }
      else [x, y, depth] = toPxXYZ(l.x, l.y, l.z || 0);
      if (depth > 1 || x < -100 || y < -40 || x > W + 100 || y > Hh + 40) { e.el.style.display = 'none'; continue; }
      e.el.style.display = '';
      if (!e.w) { e.w = e.el.offsetWidth; e.h = e.el.offsetHeight; }
      const box = [x - e.w / 2 - 4, y - e.h / 2 - 2, x + e.w / 2 + 4, y + e.h / 2 + 2];
      if (taken.some(b => box[0] < b[2] && box[2] > b[0] && box[1] < b[3] && box[3] > b[1])) { e.el.style.display = 'none'; continue; }
      taken.push(box);
      e.el.style.transform = `translate(${x - e.w / 2}px, ${y - e.h / 2}px)`;
    }
  }

  // ---- render loop: runs only while something moves ----
  let raf = 0, last = 0;
  function kick() { if (!raf) raf = requestAnimationFrame(frame); }
  function frame(now) {
    raf = 0;
    if (!W) return;  // hidden; resize() kicks again
    const dt = Math.min(64, now - (last || now)); last = now;
    let moving = false;
    if (D.transAnim) {
      const t = Math.min(1, (now - D.transAnim.t0) / D.transAnim.dur);
      D.trans = ease(t); uni.uTrans.value = D.trans; projDirty = true;
      if (t >= 1) D.transAnim = null; else moving = true;
    }
    if (D.morphAnim) {
      const a = D.morphAnim, t = Math.min(1, (now - a.t0) / a.dur);
      D.morph = a.from + (a.to - a.from) * ease(t); uni.uMorph.value = D.morph; projDirty = true;
      if (t >= 1) D.morphAnim = null; else moving = true;
    }
    if (vel) {  // momentum after a flick
      const f = Math.exp(-dt / 260);
      goal.tx += vel.x * dt; goal.ty += vel.y * dt; cur.tx = goal.tx; cur.ty = goal.ty;
      vel.x *= f; vel.y *= f;
      if (Math.hypot(vel.x, vel.y) / worldPerPx() < 0.02) vel = null; else moving = true;
    }
    const k = 1 - Math.exp(-dt / 70);
    for (const key of ['tx', 'ty', 'tz', 'd', 'th', 'ph']) {
      const diff = goal[key] - cur[key];
      if (Math.abs(diff) > (key === 'd' ? cur.d * 1e-4 : 1e-4)) { cur[key] += diff * k; moving = true; } else cur[key] = goal[key];
    }
    applyCamera();
    if (style.pixel) {
      skyUni.uTime.value = ((performance.now() - born) / 1000) % 3600;
      renderer.autoClear = true; renderer.render(skyScene, skyCam);
      renderer.autoClear = false; renderer.render(scene, camera); renderer.autoClear = true;
    } else renderer.render(scene, camera);
    drawOverlay();
    if (o.onView) o.onView();
    if (moving) kick();
    else if (style.wind && root.offsetWidth) setTimeout(kick, 33);  // the wind keeps the sky moving while it's on screen
  }

  // ---- input ----
  const ptrs = new Map();
  let gesture = null, lastTap = null, hoverI = null;
  const rel = e => { const r = root.getBoundingClientRect(); return [e.clientX - r.left, e.clientY - r.top]; };
  const info = (e, px, py) => ({ px, py, data: toData(px, py), event: e });
  root.addEventListener('pointerdown', e => {
    if ((e.button > 0 && e.button !== 2) || (e.pointerType === 'mouse' && e.ctrlKey)) return;  // ctrl-drag belongs to the lasso
    root.setPointerCapture(e.pointerId);
    const [px, py] = rel(e);
    ptrs.set(e.pointerId, { px, py });
    vel = null;
    tip.style.display = 'none';
    if (ptrs.size === 1) {
      // In 3D (like the phone): drag pans; right-drag or shift-drag rotates.
      gesture = { kind: 'one', sx: px, sy: py, lx: px, ly: py, t0: performance.now(), moved: false, touch: e.pointerType !== 'mouse', samples: [], orbit: e.shiftKey || e.button === 2 };
      clearTimeout(gesture.lp);
      if (o.onLongPress) gesture.lp = setTimeout(() => {
        if (!gesture || gesture.moved || ptrs.size !== 1) return;
        const i = pick(px, py, gesture.touch ? o.touchHitPx : o.hitPx);
        if (i != null) { gesture.long = true; o.onLongPress(i, info(e, px, py)); }
      }, 480);
    } else if (ptrs.size === 2) {
      if (gesture) clearTimeout(gesture.lp);
      const [a, b] = [...ptrs.values()];
      const d0 = Math.hypot(a.px - b.px, a.py - b.py), a0 = Math.atan2(b.py - a.py, b.px - a.px);
      gesture = { kind: 'two', dist: d0, mx: (a.px + b.px) / 2, my: (a.py + b.py) / 2, ang: a0, moved: true, lock: null, a0, d0, y0: (a.py + b.py) / 2 };
    }
    root.style.cursor = 'grabbing';
  });
  root.addEventListener('pointermove', e => {
    const [px, py] = rel(e);
    if (!ptrs.has(e.pointerId)) {  // hover
      if (e.pointerType !== 'mouse' || !n) return;
      const i = pick(px, py);
      if (i !== hoverI) { hoverI = i; if (o.onHover) o.onHover(i, info(e, px, py)); }
      if (i != null && o.tooltip) {
        tip.innerHTML = o.tooltip(i); tip.style.display = '';
        const tw = tip.offsetWidth;
        tip.style.transform = `translate(${Math.min(px + 14, W - tw - 6)}px, ${py + 14}px)`;
      } else tip.style.display = 'none';
      root.style.cursor = i != null ? 'pointer' : 'grab';
      return;
    }
    ptrs.set(e.pointerId, { px, py });
    if (!gesture) return;
    if (gesture.kind === 'one') {
      const dx = px - gesture.lx, dy = py - gesture.ly;
      if (!gesture.moved && Math.hypot(px - gesture.sx, py - gesture.sy) > (gesture.touch ? 8 : 4)) { gesture.moved = true; clearTimeout(gesture.lp); }
      if (!gesture.moved) return;
      gesture.lx = px; gesture.ly = py;
      if (is3D() && gesture.orbit) {  // orbit
        goal.th -= dx * 0.006; goal.ph = Math.max(0, Math.min(1.45, goal.ph - dy * 0.006));
        cur.th = goal.th; cur.ph = goal.ph;
      } else panBy(dx, dy);
      gesture.samples.push({ t: performance.now(), dx, dy });
      if (gesture.samples.length > 6) gesture.samples.shift();
      kick();
    } else if (gesture.kind === 'two' && ptrs.size === 2) {
      const [a, b] = [...ptrs.values()];
      const dist = Math.hypot(a.px - b.px, a.py - b.py), mx = (a.px + b.px) / 2, my = (a.py + b.py) / 2;
      const ang = Math.atan2(b.py - a.py, b.px - a.px), wrap = x => (x > Math.PI ? x - 2 * Math.PI : x < -Math.PI ? x + 2 * Math.PI : x);
      if (is3D()) {  // as on the phone: each gesture locks to a twist (turn), an up / down drag (tilt) or a pinch (zoom)
        if (!gesture.lock) {
          const turned = Math.abs(wrap(ang - gesture.a0)), scaled = Math.abs(dist / gesture.d0 - 1), lifted = Math.abs(my - gesture.y0);
          gesture.lock = turned > 0.2 ? 'twist' : lifted > 22 && scaled < 0.12 ? 'tilt' : scaled > 0.1 ? 'zoom' : null;
        }
        if (gesture.lock === 'twist') { goal.th += wrap(ang - gesture.ang); cur.th = goal.th; }
        if (gesture.lock === 'tilt') { goal.ph = Math.max(0, Math.min(1.45, goal.ph - (my - gesture.my) * 0.006)); cur.ph = goal.ph; }
      } else panBy(mx - gesture.mx, my - gesture.my);
      gesture.ang = ang;
      if (gesture.lock !== 'tilt') zoomAt(gesture.dist / dist, mx, my, true);
      Object.assign(gesture, { dist, mx, my });
      kick();
    }
  });
  function endPointer(e) {
    if (!ptrs.has(e.pointerId)) return;
    ptrs.delete(e.pointerId);
    root.style.cursor = 'grab';
    const g = gesture;
    if (!g) return;
    clearTimeout(g.lp);
    if (g.kind === 'two') { if (ptrs.size === 0) gesture = null; else gesture = { kind: 'rest' }; return; }
    if (g.kind !== 'one') { if (!ptrs.size) gesture = null; return; }
    gesture = null;
    if (e.type === 'pointercancel') return;
    if (g.moved) {  // flick → momentum (flat map only)
      const s = g.samples.filter(q => performance.now() - q.t < 90);
      if (s.length >= 2 && !(is3D() && g.orbit)) {
        const span = Math.max(16, s[s.length - 1].t - s[0].t), wpp = worldPerPx();
        const sx = s.reduce((a, q) => a + q.dx, 0), sy = s.reduce((a, q) => a + q.dy, 0);
        const r = rightUp();
        vel = { x: (-sx * r.rx + sy * r.ux) * wpp / span, y: (-sx * r.ry + sy * r.uy) * wpp / span };
        kick();
      }
      return;
    }
    if (g.long) return;
    const [px, py] = rel(e), i = pick(px, py, g.touch ? o.touchHitPx : o.hitPx), now = performance.now();
    const dbl = lastTap && now - lastTap.t < 320 && Math.hypot(px - lastTap.px, py - lastTap.py) < 24;
    if (dbl) { lastTap = null; if (o.onDoubleTap) o.onDoubleTap(i, info(e, px, py)); else zoomAt(0.5, px, py); return; }
    lastTap = { t: now, px, py, i };
    if (o.onTap) o.onTap(i, info(e, px, py));
  }
  root.addEventListener('pointerup', endPointer);
  root.addEventListener('pointercancel', endPointer);
  root.addEventListener('pointerleave', e => { if (e.pointerType === 'mouse' && !ptrs.size) { tip.style.display = 'none'; if (hoverI != null) { hoverI = null; if (o.onHover) o.onHover(null, {}); } } });
  root.addEventListener('wheel', e => {
    e.preventDefault();
    vel = null;
    const [px, py] = rel(e);
    const dy = e.deltaMode === 1 ? e.deltaY * 16 : e.deltaY;
    zoomAt(Math.exp(Math.max(-60, Math.min(60, dy)) * (e.ctrlKey ? 0.012 : 0.0035)), px, py);
  }, { passive: false });
  root.addEventListener('contextmenu', e => { if (e.pointerType !== 'mouse' || is3D()) e.preventDefault(); });  // right-drag rotates in 3D

  function rightUp() {  // world-space screen right / up (on the ground plane) for the current camera
    const th = cur.th;
    return { rx: Math.cos(th), ry: Math.sin(th), ux: -Math.sin(th), uy: Math.cos(th) };
  }
  function panBy(dx, dy) {
    const wpp = worldPerPx() * (is3D() ? 1 / Math.max(0.35, Math.cos(cur.ph)) ** 0.5 : 1), r = rightUp();
    const mx = (-dx * r.rx + dy * r.ux) * wpp, my = (-dx * r.ry + dy * r.uy) * wpp;
    goal.tx += mx; goal.ty += my; cur.tx += mx; cur.ty += my;
    applyCamera(); kick();
  }
  // f < 1 zooms in. Anchored at the pointer on the flat map, at the centre in 3D.
  function zoomAt(f, px, py, immediate = false) {
    const d0 = goal.d, d1 = Math.max(fitD * 0.02, Math.min(fitD * 3, d0 * f)), k = d1 / d0;
    if (!is3D() && px != null) {
      const [ax, ay] = toData(px, py);
      goal.tx = ax + (goal.tx - ax) * k; goal.ty = ay + (goal.ty - ay) * k;
    }
    goal.d = d1;
    if (immediate) { cur.d = goal.d; cur.tx = goal.tx; cur.ty = goal.ty; applyCamera(); }
    kick();
  }

  new ResizeObserver(resize).observe(root);
  resize();

  return {
    setPositions, setColors, setAlpha, setSizes, set3D, is3D, has3D: () => has3D,
    fit, centerOn, getView, setView, zoomAt, zoomLevel,
    setRings, setPaths, addDraw, setLabels, setPlaying, setSelected, setStyle, setGlints,
    pick, toPx, toPxXYZ, toData, posOf: i => { const v = posOf(i); return [v.x, v.y, v.z]; },
    redraw: kick, resize, root, get count() { return n; },
  };
}

window.Galaxy = { create: createGalaxy };
window.dispatchEvent(new Event('galaxy'));
