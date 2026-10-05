// Files: every draft file as text, for anything the forms do not cover and to keep YAML comments.
// Saving checks the syntax on the server and refuses edits based on an older version of the file.
import { api, can } from './api.js';
import { clear, h, toast } from './dom.js';
import { fail, refresh } from './app.js';

let open = null; // { path, version }

export async function filesView(container) {
  const files = await api.files();
  const editor = h('div', {});
  const filter = h('input', { type: 'text', placeholder: 'Filter files', oninput: () => fill() });
  const list = h('ul', { class: 'list' });
  const fill = () => clear(list, files
    .filter(file => file.path.toLowerCase().includes(filter.value.toLowerCase()))
    .map(file => h('li', {
      class: open?.path === file.path ? 'active' : '',
      onclick: () => { openFile(editor, file.path).then(fill); },
    }, h('span', { class: 'mono' }, file.path.replace(/^packages\//, '')), h('span', { class: 'small muted' }, `${Math.ceil(file.size / 1024)} KiB`))));
  fill();
  clear(container,
    h('div', { class: 'row' }, h('h1', { class: 'grow' }, 'Files'),
      can('EDIT') ? h('button', { class: 'btn', onclick: () => newFile(editor) }, 'New file') : null),
    h('div', { class: 'split' }, h('div', { class: 'card stack' }, filter, list), editor));
  if (open && files.some(file => file.path === open.path)) await openFile(editor, open.path);
  else clear(editor, h('div', { class: 'card' }, h('p', { class: 'muted' },
    'Pick a file. Edits stay in the draft until a release is published; nothing here changes what players run.')));
}

async function openFile(container, path) {
  const file = await api.file(path);
  open = { path: file.path, version: file.version };
  const text = h('textarea', { class: 'editor', spellcheck: 'false' });
  text.value = file.text;
  text.addEventListener('keydown', event => {
    if (event.key === 'Tab') {
      event.preventDefault();
      text.setRangeText('  ', text.selectionStart, text.selectionEnd, 'end');
    }
    if ((event.ctrlKey || event.metaKey) && event.key === 's') {
      event.preventDefault();
      save();
    }
  });
  const save = async () => {
    try {
      const result = await api.saveFile(open.path, text.value, open.version);
      open.version = result.version;
      toast(`Saved ${open.path} to the draft.`, 'good');
    } catch (error) {
      fail(error);
    }
  };
  const remove = async () => {
    if (!window.confirm(`Delete ${open.path} from the draft?`)) return;
    try {
      await api.deleteFile(open.path, open.version);
      open = null;
      refresh();
    } catch (error) {
      fail(error);
    }
  };
  clear(container, h('div', { class: 'card stack' },
    h('div', { class: 'row' }, h('code', { class: 'grow' }, file.path),
      h('button', { class: 'btn primary', onclick: save }, 'Save'),
      h('button', { class: 'btn danger', onclick: remove }, 'Delete')),
    text,
    h('p', { class: 'small muted' }, 'Ctrl+S saves. Saving needs the permission for every section the edit changes: '
      + 'conversations need dialogue, puzzles need puzzles, media and cutscenes need audio, overlays need triggers, the rest needs edit.')));
}

function newFile(container) {
  const path = h('input', { type: 'text', value: 'packages/' });
  const create = async () => {
    try {
      const result = await api.saveFile(path.value, '# New MysticQuests content\nquests: []\n', null);
      open = { path: path.value, version: result.version };
      refresh();
    } catch (error) {
      fail(error);
    }
  };
  clear(container, h('div', { class: 'card stack' },
    h('h2', {}, 'New file'),
    h('label', { class: 'field' }, h('span', {}, 'Path'), path,
      h('small', { class: 'muted' }, 'packages/<package>/<name>.yml — a new folder under packages/ is a new package; add a package.yml to it.')),
    h('button', { class: 'btn primary', onclick: create }, 'Create')));
}
