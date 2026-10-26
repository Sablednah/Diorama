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

  // Mouse → world, assuming sea level: a screen point is a whole line through the world, so the
  // readout picks the height most of the map sits at and says so.
  const coords = document.getElementById('coords');
  map.on('mousemove', (e) => {
    const y = world().seaLevel;
    const [x, z] = unproject(e.latlng.lng * scale, -e.latlng.lat * scale, y);
    coords.textContent = `x ${Math.round(x)}  z ${Math.round(z)}  (at y ${y})`;
  });

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

  // Markers: one toggleable layer per set another mod (or Diorama) registered. Labels are put in
  // with textContent, never as HTML — they come from other mods and, through them, from players.
  const markerLayers = new Map();
  const toggles = L.control.layers(null, null, { collapsed: true, position: 'topright' });
  let togglesShown = false;
  const tip = (m) => {
    const el = document.createElement('div');
    const b = document.createElement('strong'); b.textContent = m.label; el.appendChild(b);
    if (m.detail) { const d = document.createElement('div'); d.textContent = m.detail; el.appendChild(d); }
    return el;
  };
  async function refreshMarkers() {
    let data;
    try { data = await (await fetch('/api/markers')).json(); } catch { return; }
    for (const set of data.layers) {
      let entry = markerLayers.get(set.id);
      if (!entry) {
        entry = { group: L.layerGroup() };
        markerLayers.set(set.id, entry);
        toggles.addOverlay(entry.group, set.label);
        if (set.shown) entry.group.addTo(map);
      }
      entry.group.clearLayers();
      for (const m of set.markers) {
        if (m.world !== select.value) continue;
        const icon = L.divIcon({ className: 'marker', html: '<i></i>', iconSize: [12, 12] });
        const mk = L.marker(toLatLng(m.x, m.y, m.z), { icon, keyboard: false }).bindTooltip(tip(m), { direction: 'top', offset: [0, -6] });
        mk.on('add', () => { const e = mk.getElement(); if (e) e.style.setProperty('--c', m.colour); });
        mk.addTo(entry.group);
      }
    }
    if (!togglesShown && markerLayers.size > 0) { toggles.addTo(map); togglesShown = true; }
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
})();
