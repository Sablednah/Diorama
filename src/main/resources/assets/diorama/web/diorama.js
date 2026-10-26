// Diorama's page. Tiles are plain PNGs in Leaflet's simple CRS: at the most detailed native zoom one
// map pixel is one tile pixel, so a world position converts with the same formula the renderer uses.
(async function () {
  const config = await (await fetch('/api/config')).json();
  const W = config.pixelsPerBlock;
  const native = config.zoomOutLevels;            // leaflet zoom of level-0 tiles
  const scale = Math.pow(2, native);

  // The camera's corner turns the world a quarter at a time before projecting: the same
  // [a b; c d] as View.java, so a position lands on the same pixel the renderer drew it at.
  const VIEWS = {
    se: { m: [1, 0, 0, 1], looking: 'north-west' },
    sw: { m: [0, 1, -1, 0], looking: 'north-east' },
    nw: { m: [-1, 0, 0, -1], looking: 'south-east' },
    ne: { m: [0, -1, 1, 0], looking: 'south-west' },
  };
  const ORDER = ['se', 'sw', 'nw', 'ne'].filter(v => (config.views || ['se']).includes(v));
  const savedView = (() => { try { return localStorage.getItem('diorama.view'); } catch { return null; } })();
  // A link names its place — #world/view/x/z/zoom — and wins over what this browser remembered.
  let linked = (() => {
    const p = decodeURIComponent(location.hash.slice(1)).split('/');
    return p.length === 5 && p.slice(2).every(n => n !== '' && !isNaN(+n))
      ? { world: p[0], view: p[1], x: +p[2], z: +p[3], zoom: +p[4] } : null;
  })();
  // Night rides in the view segment of a link (nw-night), so links keep one shape.
  const savedNight = (() => { try { return localStorage.getItem('diorama.night') === '1'; } catch { return false; } })();
  let night = !!config.night && (linked ? linked.view.endsWith('-night') : savedNight);
  if (linked) linked.view = linked.view.replace(/-night$/, '');
  let view = linked && ORDER.includes(linked.view) ? linked.view : ORDER.includes(savedView) ? savedView : ORDER[0];

  // World → screen pixels at level 0, exactly as IsoRenderer projects.
  const project = (x, y, z) => {
    const [a, b, c, d] = VIEWS[view].m;
    const vx = a * x + b * z, vz = c * x + d * z;
    return [(vx - vz) * W / 2, (vx + vz) * W / 4 - y * W / 2];
  };
  // Screen → world, on the horizontal plane at height y.
  const unproject = (sx, sy, y) => {
    const u = 2 * sx / W, s = 4 * sy / W + 2 * y;
    const vx = (s + u) / 2, vz = (s - u) / 2;
    const [a, b, c, d] = VIEWS[view].m;
    return [a * vx + c * vz, b * vx + d * vz];
  };
  const toLatLng = (x, y, z) => { const [sx, sy] = project(x, y, z); return L.latLng(-sy / scale, sx / scale); };

  const map = L.map('map', {
    crs: L.CRS.Simple, minZoom: 0, maxZoom: native + 2, zoomSnap: 1, attributionControl: false,
  });

  const select = document.getElementById('world');
  for (const w of config.worlds) {
    const o = document.createElement('option');
    o.value = w.id; o.textContent = w.name;
    select.appendChild(o);
  }
  const remembered = (() => { try { return localStorage.getItem('diorama.world'); } catch { return null; } })();
  if (remembered && config.worlds.some(w => w.id === remembered)) select.value = remembered;
  if (linked && config.worlds.some(w => w.id === linked.world)) select.value = linked.world;

  let layer = null;
  const players = L.layerGroup().addTo(map);

  function world() { return config.worlds.find(w => w.id === select.value) || config.worlds[0]; }

  function show(first) {
    const w = world();
    if (layer) map.removeLayer(layer);
    layer = L.tileLayer('', {
      tileSize: config.tileSize, minZoom: 0, maxZoom: native + 2, maxNativeZoom: native, noWrap: true,
    });
    // Our levels count outward from the detailed tiles; Leaflet's zoom counts inward.
    const v = view + (night ? '-night' : '');
    layer.getTileUrl = (c) => '/tiles/' + w.id + '/' + v + '/' + (native - c.z) + '/' + c.x + '_' + c.y + '.png';
    layer.addTo(map);
    if (first && linked && linked.world === w.id) {
      map.setView(toLatLng(linked.x, w.seaLevel, linked.z), Math.max(0, Math.min(native + 2, linked.zoom)));
    } else if (first) {
      const sx = w.spawnX ?? 0, sy = w.spawnY ?? w.seaLevel, sz = w.spawnZ ?? 0;
      map.setView(toLatLng(sx, sy, sz), native);
    }
    linked = null;
    try { localStorage.setItem('diorama.world', w.id); } catch { /* private window */ }
    refreshPlayers();
    refreshMarkers();
  }

  select.addEventListener('change', () => show(true));

  // Keep the address naming what is on screen, so it can be copied and sent to someone.
  function remember() {
    const c = map.getCenter(), y = world().seaLevel;
    const [x, z] = unproject(c.lng * scale, -c.lat * scale, y);
    history.replaceState(null, '', '#' + [select.value, view + (night ? '-night' : ''), Math.round(x), Math.round(z), map.getZoom()].join('/'));
  }
  map.on('moveend', remember);

  // Turning keeps the spot in the middle of the screen in the middle, so a turn reads as walking
  // round the model rather than jumping somewhere else.
  const facing = document.getElementById('facing');
  function turn(step) {
    if (ORDER.length < 2) return;
    const centre = map.getCenter(), y = world().seaLevel;
    const [x, z] = unproject(centre.lng * scale, -centre.lat * scale, y);
    view = ORDER[(ORDER.indexOf(view) + step + ORDER.length) % ORDER.length];
    try { localStorage.setItem('diorama.view', view); } catch { /* private window */ }
    show(false);
    map.setView(toLatLng(x, y, z), map.getZoom(), { animate: false });
    label();
  }
  function label() {
    facing.textContent = 'looking ' + VIEWS[view].looking;
  }
  const nightButton = document.getElementById('night');
  function nightLabel() {
    nightButton.textContent = night ? '☾' : '☀';
    nightButton.title = night ? 'Night — switch to day (N)' : 'Day — switch to night (N)';
    nightButton.setAttribute('aria-pressed', String(night));
  }
  function toggleNight() {
    if (!config.night) return;
    night = !night;
    try { localStorage.setItem('diorama.night', night ? '1' : '0'); } catch { /* private window */ }
    nightLabel();
    show(false);
    remember();
  }
  nightButton.hidden = !config.night;
  nightButton.addEventListener('click', toggleNight);
  nightLabel();
  document.getElementById('turn-left').addEventListener('click', () => turn(-1));
  document.getElementById('turn-right').addEventListener('click', () => turn(1));
  document.addEventListener('keydown', (e) => {
    if (e.target instanceof HTMLSelectElement) return;
    if (e.key === 'q' || e.key === 'Q') turn(-1);
    if (e.key === 'e' || e.key === 'E') turn(1);
    if (e.key === 'n' || e.key === 'N') toggleNight();
  });
  if (ORDER.length < 2) {
    document.getElementById('turn-left').hidden = true;
    document.getElementById('turn-right').hidden = true;
  }
  label();

  // The info bar: what is under the cursor. A screen point is a whole diagonal line through the
  // world, so the page cannot know the block — the server casts the renderer's own ray and says.
  // One request in flight at a time; a faster mouse just skips the points in between.
  const info = {
    x: document.getElementById('ix'), y: document.getElementById('iy'), z: document.getElementById('iz'),
    block: document.getElementById('iblock'), place: document.getElementById('iplace'), biome: document.getElementById('ibiome'), region: document.getElementById('iregion'),
  };
  let wanted = null, asking = false;
  map.on('mousemove', (e) => { wanted = [e.latlng.lng * scale, -e.latlng.lat * scale]; ask(); });
  async function ask() {
    if (asking || !wanted) return;
    const [sx, sy] = wanted;
    wanted = null;
    asking = true;
    try {
      const q = new URLSearchParams({ world: select.value, view, sx: sx.toFixed(1), sy: sy.toFixed(1) });
      const at = await (await fetch('/api/at?' + q)).json();
      showAt(at);
    } catch { /* a dropped request is replaced by the next mouse move */ }
    asking = false;
    if (wanted) ask();
  }
  function showAt(at) {
    const has = at && at.x !== undefined;
    info.x.textContent = has ? at.x : '—';
    info.y.textContent = has ? at.y : '—';
    info.z.textContent = has ? at.z : '—';
    info.place.textContent = has && at.place ? at.place : '';
    info.block.textContent = has ? at.blockName : '';
    info.block.title = has ? at.block : '';
    info.biome.textContent = has && at.biomeName ? at.biomeName : '';
    info.biome.title = has && at.biome ? at.biome : '';
    info.region.textContent = has ? regionsAt(at.x, at.z).join(' · ') : '';
  }

  // Hide every control for a clean view or a screenshot; H, or the corner button, brings them back.
  const hideButton = document.getElementById('hide-gui'), showButton = document.getElementById('show-gui');
  function setGui(shown) {
    document.body.classList.toggle('gui-hidden', !shown);
    showButton.hidden = shown;
    try { localStorage.setItem('diorama.gui', shown ? '1' : '0'); } catch { /* private window */ }
  }
  hideButton.addEventListener('click', () => setGui(false));
  showButton.addEventListener('click', () => setGui(true));
  document.addEventListener('keydown', (e) => {
    if (e.target instanceof HTMLSelectElement || e.target instanceof HTMLInputElement) return;
    if (e.key === 'h' || e.key === 'H') setGui(document.body.classList.contains('gui-hidden'));
  });
  setGui((() => { try { return localStorage.getItem('diorama.gui') !== '0'; } catch { return true; } })());

  // Region labels only once the land is big enough on screen to read them against.
  const farCheck = () => map.getContainer().classList.toggle('far', map.getZoom() < native - 1);
  map.on('zoomend', farCheck);

  async function refreshPlayers() {
    let data;
    try { data = await (await fetch('/api/players')).json(); } catch { return; }
    players.clearLayers();
    for (const p of data.players) {
      if (p.world !== select.value) continue;
      L.circleMarker(toLatLng(p.x, p.y, p.z), { radius: 6, color: '#fff', weight: 2, fillColor: '#6cc4ff', fillOpacity: 1 })
        .bindTooltip(p.name, { permanent: true, direction: 'right', className: 'player-label', offset: [8, 0] })
        .addTo(players);
    }
  }
  setInterval(refreshPlayers, 2000);

  // Markers and regions: one layer per set another mod (or Diorama) registered, each switchable
  // in the Layers list. Labels go in with textContent, never as HTML — they come from other mods
  // and, through them, from whatever players typed.
  const markerLayers = new Map();
  const layersButton = document.getElementById('layers'), layerList = document.getElementById('layer-list');
  layersButton.addEventListener('click', () => {
    layerList.hidden = !layerList.hidden;
    layersButton.setAttribute('aria-expanded', String(!layerList.hidden));
  });
  document.addEventListener('click', (e) => {
    if (!layerList.hidden && !e.target.closest('.layers-wrap')) { layerList.hidden = true; layersButton.setAttribute('aria-expanded', 'false'); }
  });
  const offLayers = (() => { try { return new Set(JSON.parse(localStorage.getItem('diorama.layersOff') || '[]')); } catch { return new Set(); } })();
  const onLayers = (() => { try { return new Set(JSON.parse(localStorage.getItem('diorama.layersOn') || '[]')); } catch { return new Set(); } })();
  function saveLayers() {
    try {
      localStorage.setItem('diorama.layersOff', JSON.stringify([...offLayers]));
      localStorage.setItem('diorama.layersOn', JSON.stringify([...onLayers]));
    } catch { /* private window */ }
  }
  const tip = (m) => {
    const el = document.createElement('div');
    const b = document.createElement('strong'); b.textContent = m.label; el.appendChild(b);
    if (m.detail) { const d = document.createElement('div'); d.textContent = m.detail; el.appendChild(d); }
    return el;
  };
  // What the bar asks: every shown region in this world containing x, z.
  let areas = [];
  function regionsAt(x, z) {
    const out = [];
    for (const a of areas) {
      if (!a.entry.on) continue;
      if (a.chunks ? a.chunks.has((x >> 4) + ',' + (z >> 4)) : inside(a.corners, x + 0.5, z + 0.5)) out.push(a.label);
    }
    return out;
  }
  function inside(poly, x, z) {
    let hit = false;
    for (let i = 0, j = poly.length - 1; i < poly.length; j = i++) {
      const [xi, zi] = poly[i], [xj, zj] = poly[j];
      if ((zi > z) !== (zj > z) && x < (xj - xi) * (z - zi) / (zj - zi) + xi) hit = !hit;
    }
    return hit;
  }
  function entryFor(set) {
    let entry = markerLayers.get(set.id);
    if (entry) return entry;
    entry = { group: L.layerGroup(), on: onLayers.has(set.id) || (set.shown && !offLayers.has(set.id)) };
    markerLayers.set(set.id, entry);
    if (entry.on) entry.group.addTo(map);
    const row = document.createElement('label');
    const box = document.createElement('input'); box.type = 'checkbox'; box.checked = entry.on;
    const sw = document.createElement('span'); sw.className = 'swatch'; entry.swatch = sw;
    const name = document.createElement('span'); name.textContent = set.label;
    row.append(box, sw, name);
    box.addEventListener('change', () => {
      entry.on = box.checked;
      if (entry.on) { entry.group.addTo(map); offLayers.delete(set.id); onLayers.add(set.id); }
      else { map.removeLayer(entry.group); onLayers.delete(set.id); offLayers.add(set.id); }
      saveLayers();
    });
    layerList.appendChild(row);
    layersButton.hidden = false;
    return entry;
  }
  async function refreshMarkers() {
    let data;
    try { data = await (await fetch('/api/markers')).json(); } catch { return; }
    const nextAreas = [];
    for (const set of data.layers) {
      const entry = entryFor(set);
      entry.group.clearLayers();
      let colour = null;
      for (const m of set.markers) {
        if (m.world !== select.value) continue;
        colour = colour || m.colour;
        const icon = L.divIcon({ className: 'marker', html: '<i></i>', iconSize: [12, 12] });
        const mk = L.marker(toLatLng(m.x, m.y, m.z), { icon, keyboard: false }).bindTooltip(tip(m), { direction: 'top', offset: [0, -6] });
        mk.on('add', () => { const e = mk.getElement(); if (e) e.style.setProperty('--c', m.colour); });
        mk.addTo(entry.group);
      }
      for (const r of set.regions || []) {
        if (r.world !== select.value) continue;
        colour = colour || r.colour;
        // The land itself, faintly tinted, so hovering anywhere inside says whose it is — not only
        // the two-pixel border. All of a region's quads are one shape: where neighbouring chunks at
        // different heights overlap on screen, a single fill is not painted twice.
        if (r.fills && r.fills.length) {
          const rings = r.fills.map(f => { const pts = []; for (let i = 0; i + 2 < f.length; i += 3) pts.push(toLatLng(f[i], f[i + 1], f[i + 2])); return pts; });
          L.polygon(rings, { stroke: false, fillColor: r.colour, fillOpacity: 0.18, fillRule: 'nonzero' })
            .bindTooltip(tip(r), { sticky: true }).addTo(entry.group);
        }
        // A dark under-stroke keeps a pale border (white = a faction with no banner yet) visible
        // on snow and sand.
        for (const line of r.lines) {
          const pts = [];
          for (let i = 0; i + 2 < line.length; i += 3) pts.push(toLatLng(line[i], line[i + 1], line[i + 2]));
          L.polyline(pts, { color: '#000', opacity: 0.45, weight: 4, interactive: false }).addTo(entry.group);
          L.polyline(pts, { color: r.colour, weight: 2 }).bindTooltip(tip(r), { sticky: true }).addTo(entry.group);
        }
        const [ax, ay, az] = r.anchor;
        L.marker(toLatLng(ax, ay, az), { icon: L.divIcon({ className: 'region-anchor', html: '', iconSize: [0, 0] }), interactive: false, keyboard: false })
          .bindTooltip(r.label, { permanent: true, direction: 'center', className: 'region-label' })
          .addTo(entry.group);
        const area = { entry, label: r.label };
        if (r.chunks) {
          area.chunks = new Set();
          for (let i = 0; i + 1 < r.chunks.length; i += 2) area.chunks.add(r.chunks[i] + ',' + r.chunks[i + 1]);
        } else if (r.corners) {
          area.corners = r.corners;
        }
        nextAreas.push(area);
      }
      if (entry.swatch && colour) entry.swatch.style.setProperty('--c', colour);
    }
    areas = nextAreas;
  }
  setInterval(refreshMarkers, 5000);

  // Say why the map is empty, rather than letting an empty map look broken.
  const notice = document.getElementById('notice');
  async function refreshState() {
    let c;
    try { c = await (await fetch('/api/config')).json(); } catch { return; }
    const text = c.state === 'NO_ASSETS' ? c.detail
      : c.state === 'STARTING' ? 'Diorama is loading block models…'
      : c.progress ? 'Drawing the map — ' + c.progress + '. Tiles appear as they finish.'
      : '';
    notice.textContent = text;
    notice.hidden = !text;
  }
  setInterval(refreshState, 5000);
  refreshState();

  show(true);
  farCheck();
})();
