// Diorama's page. Tiles are plain PNGs in Leaflet's simple CRS: at the most detailed native zoom one
// map pixel is one tile pixel, so a world position converts with the same formula the renderer uses.
(async function () {
  const config = await (await fetch('/api/config')).json();
  const W = config.pixelsPerBlock;
  const native = config.zoomOutLevels;            // leaflet zoom of level-0 tiles
  const scale = Math.pow(2, native);

  // World → screen pixels at level 0, exactly as IsoRenderer projects.
  const project = (x, y, z) => [(x - z) * W / 2, (x + z) * W / 4 - y * W / 2];
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
    layer.getTileUrl = (c) => '/tiles/' + w.id + '/se/' + (native - c.z) + '/' + c.x + '_' + c.y + '.png';
    layer.addTo(map);
    if (first) {
      const sx = w.spawnX ?? 0, sy = w.spawnY ?? w.seaLevel, sz = w.spawnZ ?? 0;
      map.setView(toLatLng(sx, sy, sz), native);
    }
    try { localStorage.setItem('diorama.world', w.id); } catch { /* private window */ }
    refreshPlayers();
  }

  select.addEventListener('change', () => show(true));

  // Mouse → world, assuming sea level: a screen point is a whole line through the world, so the
  // readout picks the height most of the map sits at and says so.
  const coords = document.getElementById('coords');
  map.on('mousemove', (e) => {
    const sx = e.latlng.lng * scale, sy = -e.latlng.lat * scale, y = world().seaLevel;
    const u = 2 * sx / W, s = 4 * sy / W + 2 * y;
    coords.textContent = `x ${Math.round((s + u) / 2)}  z ${Math.round((s - u) / 2)}  (at y ${y})`;
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
