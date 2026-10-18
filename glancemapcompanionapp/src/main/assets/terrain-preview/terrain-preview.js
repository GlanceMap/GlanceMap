(function () {
'use strict';
const maplibregl = window.maplibregl;
maplibregl.setWorkerUrl(new URL('maplibre-gl-csp-worker.js', window.location.href).href);

let map;
let snapshot;
let currentBasemap;
let ready = false;
let failed = false;

function status(value) {
  document.title = `GlanceMap terrain ${value}`;
  document.documentElement.dataset.terrainStatus = value;
}

function fail() {
  ready = false;
  failed = true;
  status('error');
}

function addOverlays() {
  if (!map.getSource('preview')) {
    map.addSource('preview', {type: 'geojson', data: snapshot.features});
    map.addLayer({id: 'preview-track', type: 'line', source: 'preview',
      filter: ['==', ['get', 'kind'], 'track'],
      layout: {'line-cap': 'round', 'line-join': 'round'},
      paint: {'line-color': '#e84118', 'line-width': 4}});
    map.addLayer({id: 'preview-pois', type: 'circle', source: 'preview',
      filter: ['==', ['get', 'kind'], 'poi'],
      paint: {'circle-radius': 5, 'circle-color': '#c65805',
        'circle-stroke-color': '#ffffff', 'circle-stroke-width': 2}});
    map.addLayer({id: 'preview-location', type: 'circle', source: 'preview',
      filter: ['==', ['get', 'kind'], 'location'],
      paint: {'circle-radius': 8, 'circle-color': '#1976d2',
        'circle-stroke-color': '#ffffff', 'circle-stroke-width': 3}});
  }
}

function start(data) {
  if (map) return;
  snapshot = data;
  currentBasemap = data.initialBasemap;
  try {
    map = new maplibregl.Map({container: 'map', style: data.styles[currentBasemap],
      center: data.center, zoom: data.zoom, bearing: data.bearing, pitch: 60,
      maxZoom: 18, maxPitch: 80, attributionControl: {compact: false}});
    map.addControl(new maplibregl.NavigationControl({visualizePitch: true}), 'top-right');
    map.on('style.load', () => {
      try {
        // Color-relief shaders need WebGL 2; keep terrain and hillshade on the older fallback.
        if (!map.getCanvas().getContext('webgl2')) {
          for (const layer of map.getStyle().layers) {
            if (layer.type === 'color-relief') map.removeLayer(layer.id);
          }
        }
        addOverlays();
        // Camera controls can work while terrain tiles continue downloading.
        ready = true;
        status('ready');
      } catch { fail(); }
    });
    map.on('idle', () => {
      if (!failed && map.getTerrain() && map.getSource('preview')) {
        ready = true;
        status('ready');
      }
    });
    map.on('error', event => {
      // A failed tile must not discard an otherwise usable map.
      if (!ready || !event.tile) fail();
    });
    map.getCanvas().addEventListener('webglcontextlost', fail);
    map.on('click', 'preview-pois', event => {
      const label = event.features?.[0]?.properties?.label;
      if (label) new maplibregl.Popup().setLngLat(event.lngLat).setText(label).addTo(map);
    });
  } catch {
    ready = false;
    failed = true;
    status('unsupported');
  }
}

function setBasemap(basemap) {
  if (!map || !snapshot.styles[basemap] || basemap === currentBasemap) return;
  ready = false;
  failed = false;
  currentBasemap = basemap;
  status('loading');
  map.setStyle(snapshot.styles[basemap], {diff: false});
}

function fitTracks() {
  if (!ready) return;
  const tracks = snapshot.features.features.filter(feature => feature.properties.kind === 'track');
  const points = tracks.flatMap(feature => feature.geometry.coordinates);
  if (!points.length) return;
  // Unwrap around the first track point so a route crossing 180° does not fit the entire world.
  const anchor = points[0][0];
  const bounds = new maplibregl.LngLatBounds();
  for (const [longitude, latitude] of points) {
    const unwrapped = anchor + ((((longitude - anchor) % 360) + 540) % 360 - 180);
    bounds.extend([unwrapped, latitude]);
  }
  map.fitBounds(bounds, {padding: 40, maxZoom: 16, pitch: 60, duration: 600});
}

function resetCamera() {
  if (ready) map.easeTo({center: snapshot.center, zoom: snapshot.zoom,
    bearing: snapshot.bearing, pitch: 60, duration: 500});
}

window.GlanceMapTerrain = {start, setBasemap, fitTracks, resetCamera, pause: () => map?.stop()};

})();
