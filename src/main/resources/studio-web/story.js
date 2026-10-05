// Story state and puzzles: tag and variable schemas as tables, and puzzles, overlays, speakers, media
// and cutscenes as items with a summary and their JSON. Each section asks for the capability the
// server requires for it, so a writer is never offered a save the server would refuse.
import { api, can } from './api.js';
import { badge, clear, field, h, jsonEditor, toast } from './dom.js';
import { addItem, collect, holders, packageName, saveItem } from './model.js';
import { fail, refresh } from './app.js';
import { puzzleEditor } from './puzzles.js';
import { cutsceneEditor } from './cutscenes.js';

const SCOPES = ['player', 'quest', 'quest_session', 'party', 'world', 'server', 'network', 'temporary', 'account', 'season'];
const TYPES = ['boolean', 'integer', 'long', 'double', 'string', 'uuid', 'duration', 'timestamp', 'location', 'entity',
  'list<string>', 'set<string>', 'map<string>'];

const SECTIONS = [
  { id: 'tagSchemas', label: 'Tags', capability: 'EDIT', table: true },
  { id: 'variableSchemas', label: 'Variables', capability: 'EDIT', table: true },
  { id: 'puzzles', label: 'Puzzles', capability: 'PUZZLES', summary: puzzleSummary, editor: puzzleEditor },
  { id: 'overlays', label: 'World overlays', capability: 'TRIGGERS', summary: value => `${value.volume ?? value.entity ?? ''}` },
  { id: 'speakers', label: 'Speakers', capability: 'AUDIO', summary: value => value.name ?? value.displayName ?? '' },
  { id: 'media', label: 'Media', capability: 'AUDIO', summary: value => `${value.kind ?? ''} ${value.sound ?? ''}` },
  { id: 'cutscenes', label: 'Cutscenes', capability: 'AUDIO', summary: value => `${(value.steps || []).length} step(s)${value.skippable === false ? ', unskippable' : ''}`, editor: cutsceneEditor },
];
let section = 'tagSchemas';

/**
 * Where an id is mentioned across the draft (§11 "usage search"): files whose content names it,
 * not counting its own definition. A text search, so it also finds ids inside v1 instructions.
 */
function usages(documents, id) {
  const found = [];
  for (const doc of documents) {
    if (!doc.document) continue;
    const text = JSON.stringify(doc.document);
    const count = text.split(id).length - 1;
    if (count > 0) found.push({ path: doc.path, count });
  }
  const total = found.reduce((sum, entry) => sum + entry.count, 0) - 1;
  return { total: Math.max(0, total), files: found.map(entry => entry.path) };
}

function puzzleSummary(value) {
  const inputs = (value.inputs || []).length;
  const rule = typeof value.rule === 'string' ? value.rule : value.rule?.type ?? '';
  const active = value.selection?.active;
  return `${inputs} input(s), rule ${rule}${active ? `, ${active} dealt per audience` : ''}`;
}

export async function storyView(container) {
  const documents = await api.documents(true);
  if (section === 'locales') {
    return localesView(container, documents);
  }
  const current = SECTIONS.find(candidate => candidate.id === section);
  const items = collect(documents, current.id);
  const tabs = h('div', { class: 'row' }, SECTIONS.map(candidate => h('button', {
    class: `btn small ${candidate.id === section ? 'primary' : ''}`,
    onclick: () => { section = candidate.id; storyView(container); },
  }, `${candidate.label} (${collect(documents, candidate.id).length})`)),
    h('button', { class: 'btn small', onclick: () => { section = 'locales'; storyView(container); } }, 'Locales'));
  const body = h('div', {});
  clear(container, h('h1', {}, 'Story state & puzzles'), h('div', { class: 'card' }, tabs), body);
  if (current.table) schemaTable(body, current, items, documents);
  else itemList(body, current, items, documents);
}

/** §16 locale coverage: which voice lines have a recording in which language. */
function localesView(container, documents) {
  const media = collect(documents, 'media').filter(item => (item.value.kind ?? 'sfx') === 'voice');
  const locales = [...new Set(media.flatMap(item => Object.keys(item.value.sounds || {})))].sort();
  const tabs = h('div', { class: 'row' }, SECTIONS.map(candidate => h('button', {
    class: 'btn small', onclick: () => { section = candidate.id; storyView(container); },
  }, candidate.label)), h('button', { class: 'btn small primary' }, 'Locales'));
  clear(container, h('h1', {}, 'Story state & puzzles'), h('div', { class: 'card' }, tabs),
    h('div', { class: 'card stack' },
      h('p', { class: 'small muted' }, "Voice lines and the languages they are recorded in. A line plays the player's language when it has one, "
        + "otherwise the server's fallback language; its subtitle shows either way. Reload warns when the fallback is missing."),
      media.length ? h('table', {},
        h('thead', {}, h('tr', {}, h('th', {}, 'Voice line'), h('th', {}, 'Default'), locales.map(locale => h('th', {}, locale)), h('th', {}, 'Subtitle'))),
        h('tbody', {}, media.map(item => h('tr', {},
          h('td', {}, h('code', {}, item.id)),
          h('td', {}, item.value.sound ? badge('yes', 'good') : badge('none')),
          locales.map(locale => h('td', {}, item.value.sounds?.[locale] ? badge('yes', 'good') : badge('missing', 'warn'))),
          h('td', {}, item.value.subtitle || item.value.subtitleKey ? badge('yes', 'good') : badge('none', 'warn'))))))
        : h('p', { class: 'muted' }, 'No voice media in the draft.')));
}

function schemaTable(container, current, items, documents) {
  const editable = can(current.capability);
  const variables = current.id === 'variableSchemas';
  const rows = items.map(item => ({ item, value: structuredClone(item.value) }));
  const save = async row => {
    try {
      if (await saveItem(row.item, row.value)) {
        toast(`Saved ${row.value.id}.`, 'good');
        refresh();
      }
    } catch (error) {
      fail(error);
    }
  };
  const fresh = { id: '', scope: 'player', path: holders(documents, current.id)[0] || 'packages/new_package/narrative.yml' };
  const add = async () => {
    if (!/^[a-z0-9_.-]+:[a-z0-9_./-]+$/i.test(fresh.id)) return toast('Ids are namespaced: mypack:some_name', 'error');
    const { path, ...value } = fresh;
    if (variables && !value.type) value.type = 'integer';
    try {
      await addItem(path, current.id, value);
      refresh();
    } catch (error) {
      fail(error);
    }
  };
  clear(container,
    h('div', { class: 'card' },
      h('p', { class: 'small muted' }, variables
        ? 'Typed variables. A variable lives in one scope; reads and writes elsewhere fail the reload.'
        : 'Tags are flags in one scope. A player-scoped tag with a milestone text shows on the player portal when reached.'),
      h('table', {},
        h('thead', {}, h('tr', {}, ['Id', 'Used', 'Scope', variables ? 'Type' : 'Lasts', variables ? 'Default' : 'Milestone', 'Description', 'File', ''].map(label => h('th', {}, label)))),
        h('tbody', {}, rows.map(row => {
          const used = usages(documents, row.value.id);
          return h('tr', {},
          h('td', {}, h('code', {}, row.value.id)),
          h('td', { title: used.files.join('\n') }, used.total ? `${used.total}×` : badge('unused', 'warn')),
          h('td', {}, field('', row.value, 'scope', { options: SCOPES.includes(row.value.scope) ? SCOPES : [row.value.scope, ...SCOPES] })),
          h('td', {}, variables ? field('', row.value, 'type', { options: TYPES.includes(row.value.type) ? TYPES : [row.value.type, ...TYPES] })
            : field('', row.value, 'ttl', { placeholder: 'forever' })),
          h('td', {}, variables ? defaultField(row.value) : field('', row.value, 'milestone')),
          h('td', {}, field('', row.value, 'description')),
          h('td', { class: 'small muted' }, packageName(row.item.path)),
          h('td', {}, editable ? h('button', { class: 'btn small', onclick: () => save(row) }, 'Save') : null));
        }))),
      editable ? h('div', { class: 'row' },
        field('New id', fresh, 'id', { placeholder: 'grove:seal_broken' }),
        field('Scope', fresh, 'scope', { options: SCOPES }),
        field('File', fresh, 'path'),
        h('button', { class: 'btn', onclick: add }, 'Add')) : null));
}

/** Defaults are typed: numbers stay numbers, booleans stay booleans, anything else is JSON or text. */
function defaultField(value) {
  const input = h('input', { type: 'text', value: value.default === undefined ? '' : JSON.stringify(value.default),
    oninput: () => {
      if (input.value === '') delete value.default;
      else {
        try { value.default = JSON.parse(input.value); } catch { value.default = input.value; }
      }
    } });
  return input;
}

function itemList(container, current, items, documents) {
  const editable = can(current.capability);
  const editor = h('div', {});
  const open = item => {
    if (current.editor) {
      current.editor(editor, item);
      return;
    }
    const json = jsonEditor(item.value, 18);
    const save = async () => {
      try {
        if (await saveItem(item, json.read())) {
          toast(`Saved ${item.id}.`, 'good');
          refresh();
        }
      } catch (error) {
        fail(error);
      }
    };
    clear(editor, h('div', { class: 'card stack' },
      h('div', { class: 'row' }, h('h2', { class: 'grow' }, String(item.id)), badge(packageName(item.path))),
      h('p', { class: 'small muted' }, current.summary(item.value)),
      json.element,
      editable ? h('button', { class: 'btn primary', onclick: save }, 'Save to draft')
        : h('p', { class: 'muted' }, `Editing ${current.label.toLowerCase()} needs mysticquests.studio.${current.capability.toLowerCase()}.`)));
  };
  const add = async () => {
    const id = window.prompt(`Id for the new ${current.label.toLowerCase().replace(/s$/, '')} (namespaced, e.g. grove:keys)`);
    if (!id) return;
    const path = holders(documents, current.id)[0] || 'packages/new_package/narrative.yml';
    try {
      await addItem(path, current.id, { id });
      refresh();
    } catch (error) {
      fail(error);
    }
  };
  clear(container, h('div', { class: 'split' },
    h('div', { class: 'card stack' },
      items.length ? h('ul', { class: 'list' }, items.map(item => h('li', { onclick: () => open(item) },
        h('span', {}, String(item.id)), h('span', { class: 'small muted' }, current.summary(item.value)))))
        : h('p', { class: 'muted' }, `No ${current.label.toLowerCase()} in the draft.`),
      editable ? h('button', { class: 'btn small', onclick: add }, 'Add') : null),
    editor));
  clear(editor, h('div', { class: 'card' }, h('p', { class: 'muted' },
    'Pick an item to edit it. The narrative runtime guide documents every field; Validate checks them as a reload would.')));
}
