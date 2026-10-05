// World: every trigger volume and story NPC the draft refers to, and where (§11 "Trigger Volume
// Studio", "Entity / NPC Studio"). A reference view: it finds what content names, so a renamed
// volume or a forgotten NPC binding shows up before players meet it.
import { api } from './api.js';
import { badge, clear, h } from './dom.js';
import { packageName } from './model.js';

const TRIGGER_OBJECTIVES = new Set(['triggerEnter', 'triggerExit']);

/** Walks a document, calling visit(node, trail) for every object; trail names where it sits. */
function walk(value, visit, trail = []) {
  if (Array.isArray(value)) {
    value.forEach((entry, index) => walk(entry, visit, [...trail, entry && typeof entry === 'object' && entry.id ? entry.id : `#${index + 1}`]));
  } else if (value && typeof value === 'object') {
    visit(value, trail);
    for (const [key, entry] of Object.entries(value)) {
      if (entry && typeof entry === 'object') walk(entry, visit, Array.isArray(entry) ? [...trail, key] : [...trail, key]);
    }
  }
}

function add(map, key, use) {
  if (!key || typeof key !== 'string') return;
  (map.get(key) || map.set(key, []).get(key)).push(use);
}

/** Trigger volumes, story NPCs and NPC bindings named anywhere in the draft. */
export function references(documents) {
  const volumes = new Map();
  const npcs = new Map();
  for (const doc of documents) {
    if (!doc.document) continue;
    walk(doc.document, (node, trail) => {
      const where = { path: doc.path, at: trail.filter(Boolean).join(' › ') };
      if (typeof node.volume === 'string') {
        const as = trail.includes('objectives') ? 'objective' : trail.includes('inputs') ? 'puzzle input'
          : trail.includes('overlays') ? 'world overlay' : 'volume reference';
        add(volumes, node.volume, { ...where, as });
      }
      if (Array.isArray(node.volumes)) node.volumes.forEach(volume => add(volumes, volume, { ...where, as: 'trigger state action' }));
      if (TRIGGER_OBJECTIVES.has(node.type) && typeof node.target === 'string') add(volumes, node.target, { ...where, as: `${node.type} objective` });
      const type = typeof node.type === 'string' ? node.type : '';
      if (type.endsWith('entity.spawn') && typeof node.definition === 'string') add(npcs, `generation definition ${node.definition}`, { ...where, as: 'spawned for a story' });
      if (type.endsWith('entity.claim') && typeof node.entity === 'string') add(npcs, node.entity, { ...where, as: 'claimed for a story' });
      if (type === 'interactNpc' || type === 'interactEntity') {
        if (typeof node.target === 'string') add(npcs, node.target, { ...where, as: `${type} objective` });
      }
      if (node.entity && typeof node.entity === 'object' && !Array.isArray(node.entity)) {
        const binding = node.entity.uuid ? `uuid:${node.entity.uuid}` : node.entity.name ? `named "${node.entity.name}"` : null;
        add(npcs, binding, { ...where, as: 'conversation NPC' });
      }
      if (typeof node.generation === 'string') add(npcs, `generation ${node.generation}`, { ...where, as: 'conversation NPC' });
    });
  }
  return { volumes, npcs };
}

export async function worldView(container) {
  const documents = await api.documents(true);
  const { volumes, npcs } = references(documents);
  const filter = h('input', { type: 'text', placeholder: 'Filter by name' });
  const body = h('div', {});
  const draw = () => {
    const needle = filter.value.toLowerCase();
    clear(body,
      table('Trigger volumes', 'Volumes are made in Hytale with the Trigger Volume tool; content names them as world:volume. '
        + 'A volume used only once may be a typo of another.', volumes, needle),
      table('Story NPCs and NPC bindings', 'NPCs spawned or claimed for a story, NPCs objectives ask players to talk to, '
        + 'and the NPCs conversations open on.', npcs, needle));
  };
  filter.addEventListener('input', draw);
  draw();
  clear(container, h('h1', {}, 'World'), h('div', { class: 'card' }, filter), body);
}

function table(title, help, map, needle) {
  const rows = [...map.entries()].filter(([key]) => key.toLowerCase().includes(needle)).sort(([a], [b]) => a.localeCompare(b));
  return h('div', { class: 'card stack' },
    h('h2', {}, `${title} (${rows.length})`),
    h('p', { class: 'small muted' }, help),
    rows.length ? h('table', {},
      h('thead', {}, h('tr', {}, h('th', {}, 'Name'), h('th', {}, 'Used'), h('th', {}, 'Where'))),
      h('tbody', {}, rows.map(([key, uses]) => h('tr', {},
        h('td', {}, h('code', {}, key)),
        h('td', {}, uses.length === 1 ? badge('once', 'warn') : `${uses.length}×`),
        h('td', { class: 'small' }, uses.map(use => h('div', {},
          h('span', { class: 'muted' }, `${packageName(use.path)} · ${use.at || 'top'}: `), use.as))))))) : h('p', { class: 'muted' }, 'None found.'));
}
