// MysticQuests Studio: shell, sign-in, overview, validation, publishing, history and audit.
import { api, ApiError, can, invalidate, state } from './api.js';
import { badge, clear, h, toast, when } from './dom.js';
import { questsView } from './quests.js';
import { dialogueView } from './dialogue.js';
import { storyView } from './story.js';
import { filesView } from './files.js';
import { liveView } from './live.js';
import { playersView } from './players.js';
import { audioView } from './audio.js';
import { worldView } from './world.js';
import { collect } from './model.js';

const root = document.getElementById('app');

const VIEWS = [
  { id: 'overview', label: 'Overview', render: overview },
  { id: 'quests', label: 'Quests', render: questsView },
  { id: 'dialogue', label: 'Dialogue', render: dialogueView },
  { id: 'story', label: 'Story state & puzzles', render: storyView },
  { id: 'world', label: 'World', render: worldView },
  { id: 'audio', label: 'Audio', render: audioView },
  { id: 'files', label: 'Files', render: filesView },
  { id: 'validate', label: 'Validate', render: validateView },
  { id: 'publish', label: 'Publish & history', render: publishView },
  { id: 'live', label: 'Live sessions', render: liveView },
  { id: 'players', label: 'Players', render: playersView },
  { id: 'audit', label: 'Audit log', render: auditView },
];

export function fail(error) {
  if (error instanceof ApiError && error.status === 401) return;
  toast(error.message || String(error), 'error');
}

// --- Sign-in ---

async function start() {
  const fragment = new URLSearchParams(location.hash.slice(1));
  const code = fragment.get('code');
  const refused = fragment.get('signin-error');
  if (refused) {
    history.replaceState(null, '', location.pathname);
    return signIn(refused);
  }
  if (code) {
    // The code is in the fragment so it never reaches a server log; drop it from the address bar too.
    history.replaceState(null, '', location.pathname);
    try {
      state.me = await api.login(code);
      return shell();
    } catch (error) {
      return signIn(error.message);
    }
  }
  try {
    state.me = await api.me();
    shell();
  } catch {
    signIn();
  }
}

async function signIn(message = '') {
  let options = { mysticIdentity: false };
  try {
    options = await api.signInOptions();
  } catch {
    // Codes always work; the MysticIdentity button is only offered when the server says so.
  }
  const input = h('input', { type: 'text', maxlength: 12, autocomplete: 'one-time-code', 'aria-label': 'Sign-in code' });
  const submit = async event => {
    event.preventDefault();
    try {
      state.me = await api.login(input.value);
      shell();
    } catch (error) {
      clear(note, error.message);
    }
  };
  const note = h('p', { class: 'muted small', role: 'alert' }, message);
  clear(root, h('form', { class: 'card signin stack', onsubmit: submit },
    h('h1', {}, 'MysticQuests Studio'),
    h('p', {}, 'In game, run ', h('code', {}, '/mquest studio login'), ' and open the link, or enter the code here.'),
    input,
    h('button', { class: 'btn primary', type: 'submit' }, 'Sign in'),
    options.mysticIdentity ? [
      h('p', { class: 'muted small' }, 'Or use your MysticIdentity account, linked to your Hytale profile:'),
      h('a', { class: 'btn', href: '/auth/login' }, 'Sign in with MysticIdentity'),
    ] : null,
    note));
  root.classList.remove('app');
  input.focus();
}

window.addEventListener('studio:signed-out', () => signIn('Your session ended. Sign in again.'));
// Opening a new sign-in link in a tab that already shows the Studio changes only the fragment.
window.addEventListener('hashchange', () => {
  if (new URLSearchParams(location.hash.slice(1)).has('code')) start();
});

// --- Shell ---

let current = 'overview';
let main;

function shell() {
  root.classList.add('app');
  const nav = h('nav', { class: 'nav', 'aria-label': 'Studio' },
    h('div', { class: 'brand' }, h('img', { src: 'icon.svg', alt: '' }), 'MysticQuests Studio'),
    VIEWS.map(view => h('button', { 'data-view': view.id, onclick: () => go(view.id) }, view.label)),
    h('div', { class: 'spacer' }),
    h('div', { class: 'who' },
      h('div', {}, state.me.name),
      badge(state.me.environment, `env-${state.me.environment}`), ' ',
      h('button', { class: 'btn small', onclick: signOut }, 'Sign out')));
  main = h('main', { class: 'main' });
  clear(root, nav, main);
  const wanted = location.hash.slice(1);
  go(VIEWS.some(view => view.id === wanted) ? wanted : 'overview');
}

async function signOut() {
  try {
    await api.logout();
  } finally {
    state.me = null;
    signIn('Signed out.');
  }
}

export async function go(id) {
  // Views that poll (Live sessions) stop when another view takes the page.
  window.dispatchEvent(new Event('studio:navigate'));
  current = id;
  history.replaceState(null, '', `#${id}`);
  root.querySelectorAll('.nav button[data-view]').forEach(button =>
    button.classList.toggle('active', button.dataset.view === id));
  clear(main, h('p', { class: 'muted' }, 'Loading…'));
  try {
    const view = VIEWS.find(candidate => candidate.id === id);
    await view.render(main);
  } catch (error) {
    clear(main, h('div', { class: 'card' }, h('h2', {}, 'Could not load this view'), h('p', {}, error.message)));
    fail(error);
  }
}

export function refresh() {
  invalidate();
  return go(current);
}

// --- Overview ---

async function overview(container) {
  const [status, releases] = await Promise.all([api.status(), api.releases()]);
  const documents = await api.documents(true);
  const unparseable = documents.filter(doc => doc.error);
  const latest = releases.filter(release => !release.baseline).at(-1);
  clear(container,
    h('h1', {}, 'Overview'),
    h('div', { class: 'grid2' },
      h('div', { class: 'card' },
        h('h3', {}, 'Draft'),
        h('div', { class: 'stat' }, String(status.changes.length)),
        h('p', { class: 'muted' }, status.changes.length === 1 ? 'file differs from what players run' : 'files differ from what players run'),
        h('button', { class: 'btn', onclick: () => go('publish') }, 'Review and publish')),
      h('div', { class: 'card' },
        h('h3', {}, 'Latest release'),
        latest
          ? [h('div', { class: 'stat' }, `#${latest.number}`), h('p', { class: 'muted' }, `${latest.message || 'No message'} — ${latest.actor}, ${when(latest.at)}`)]
          : h('p', { class: 'muted' }, 'Nothing published from the Studio yet.')),
      h('div', { class: 'card' },
        h('h3', {}, 'You'),
        h('p', {}, state.me.name, ' on ', badge(state.me.environment, `env-${state.me.environment}`)),
        h('p', { class: 'small muted' }, 'Can: ', (state.me.capabilities || []).filter(c => c !== 'LOGIN').join(', ').toLowerCase() || 'nothing yet'))),
    status.liveEdits.length ? h('div', { class: 'card' },
      h('h2', {}, 'Live files were edited outside the Studio'),
      h('p', {}, 'Someone changed the live content by hand or with the in-game editor since this draft was made. '
        + 'Publishing would replace those edits. Reset the draft to start from them.'),
      h('ul', {}, status.liveEdits.map(change => h('li', {}, h('code', {}, change.path), ' ', change.kind.toLowerCase())))) : null,
    unparseable.length ? h('div', { class: 'card' },
      h('h2', {}, 'Files that do not parse'),
      unparseable.map(doc => h('div', { class: 'problem error' }, h('div', { class: 'where' }, doc.path), doc.error))) : null,
    packageTree(documents));
}

/**
 * Packages as folders nest (§11 "Projects / Seasons": a season folder holding chapter packages, a
 * chapter holding acts), with what each holds.
 */
function packageTree(documents) {
  const packages = new Map();
  for (const doc of documents) {
    if (!doc.path.startsWith('packages/')) continue;
    const folder = doc.path.slice('packages/'.length, doc.path.lastIndexOf('/'));
    const entry = packages.get(folder) || packages.set(folder, { files: 0, manifest: null }).get(folder);
    entry.files += 1;
    if (/\/package\.(ya?ml|json)$/.test(doc.path) && doc.document) entry.manifest = doc.document;
  }
  const count = (folder, section) => collect(documents.filter(doc => doc.path.startsWith(`packages/${folder}/`)
    && doc.path.lastIndexOf('/') === `packages/${folder}`.length), section).length;
  const rows = [...packages.keys()].sort();
  return h('div', { class: 'card' },
    h('h2', {}, 'Packages'),
    h('p', { class: 'small muted' }, 'Folders nest: a season folder can hold chapter packages, and a chapter its acts. '
      + 'A folder with a package file is a package of its own.'),
    h('table', {},
      h('thead', {}, h('tr', {}, ['Package', 'Version', 'Quests', 'Conversations', 'Puzzles', 'Files'].map(label => h('th', {}, label)))),
      h('tbody', {}, rows.map(folder => {
        const entry = packages.get(folder);
        const depth = folder.split('/').length - 1;
        const disabled = entry.manifest && entry.manifest.enabled === false;
        return h('tr', {},
          h('td', {}, h('span', { class: 'mono' }, `${'\u00a0\u00a0'.repeat(depth)}${folder.split('/').pop()}`),
            disabled ? [' ', badge('disabled', 'warn')] : null),
          h('td', { class: 'small' }, entry.manifest?.version ?? ''),
          h('td', {}, String(count(folder, 'quests'))),
          h('td', {}, String(count(folder, 'conversations'))),
          h('td', {}, String(count(folder, 'puzzles'))),
          h('td', { class: 'small muted' }, String(entry.files)));
      }))));
}

// --- Validate ---

export function problemList(validation) {
  if (!validation.problems.length) return h('p', { class: 'badge good' }, 'No problems found');
  return validation.problems.map(problem => h('div', { class: `problem ${problem.severity}` },
    h('div', { class: 'where' }, `${problem.severity.toUpperCase()} ${problem.code}${problem.path ? ` · ${problem.path}` : ''}`),
    problem.message));
}

async function validateView(container) {
  clear(container, h('h1', {}, 'Validate'), h('p', { class: 'muted' }, 'Checking the draft as a reload would…'));
  const validation = await api.validate();
  const errors = validation.problems.filter(problem => problem.severity === 'error').length;
  clear(container,
    h('h1', {}, 'Validate'),
    h('div', { class: 'card' },
      h('div', { class: 'row' },
        validation.ok ? badge('Ready to publish', 'good') : badge(`${errors} error(s)`, 'bad'),
        h('span', { class: 'muted' }, `${validation.packages} package(s), ${validation.quests} quest(s)`),
        h('span', { class: 'grow' }),
        h('button', { class: 'btn', onclick: () => go('validate') }, 'Check again')),
      h('p', { class: 'small muted' }, 'The same loader and compiler as /mquest reload: a draft that passes here is one the server accepts.')),
    h('div', { class: 'card' }, problemList(validation)));
}

// --- Publish & history ---

async function publishView(container) {
  const [status, releases] = await Promise.all([api.status(), api.releases()]);
  const message = h('input', { type: 'text', placeholder: 'What changed, for the release history' });
  const overwrite = h('input', { type: 'checkbox' });
  const result = h('div', {});
  const publish = async () => {
    try {
      const release = await api.publish(message.value, overwrite.checked);
      toast(`Release #${release.number} is live.`, 'good');
      refresh();
    } catch (error) {
      clear(result, h('p', { class: 'problem error' }, error.message),
        error.detail?.problems ? problemList(error.detail) : null);
    }
  };
  const reset = async () => {
    if (!window.confirm('Throw away every draft change and start again from the live content?')) return;
    try {
      await api.reset();
      toast('The draft now matches the live content.', 'good');
      refresh();
    } catch (error) {
      fail(error);
    }
  };
  const history = [...releases].reverse();
  clear(container,
    h('h1', {}, 'Publish & history'),
    h('div', { class: 'card stack' },
      h('h2', {}, `Changes in the draft (${status.changes.length})`),
      status.changes.length
        ? h('ul', {}, status.changes.map(change => h('li', {}, badge(change.kind.toLowerCase()), ' ', h('code', {}, change.path))))
        : h('p', { class: 'muted' }, 'The draft matches what players run.'),
      status.liveEdits.length ? h('p', { class: 'problem warning' },
        `${status.liveEdits.length} live file(s) were edited outside the Studio. Publishing stops unless you choose to replace them.`) : null,
      can('PUBLISH') ? [
        h('label', { class: 'field' }, h('span', {}, 'Release message'), message),
        status.liveEdits.length ? h('label', { class: 'row small' }, overwrite, 'Replace the live edits made outside the Studio') : null,
        h('div', { class: 'row' },
          h('button', { class: 'btn primary', onclick: publish, disabled: !status.changes.length }, `Publish to ${state.me.environment}`),
          can('EDIT') ? h('button', { class: 'btn danger', onclick: reset }, 'Discard draft') : null),
      ] : h('p', { class: 'muted' }, 'Publishing needs mysticquests.studio.publish.'),
      result),
    h('div', { class: 'card' },
      h('h2', {}, 'Releases'),
      history.length ? h('table', {},
        h('thead', {}, h('tr', {}, h('th', {}, '#'), h('th', {}, 'When'), h('th', {}, 'Who'), h('th', {}, 'Message'), h('th', {}, 'Files'), h('th', {}, ''))),
        h('tbody', {}, history.map(release => h('tr', {},
          h('td', {}, `#${release.number}`),
          h('td', {}, when(release.at)),
          h('td', {}, release.actor),
          h('td', {}, release.baseline ? h('em', {}, release.message) : release.message),
          h('td', {}, release.baseline ? '' : String(release.changes.length)),
          h('td', { class: 'row' },
            release.number > 0 ? h('button', { class: 'btn small', onclick: () => compare(release.number - 1, release.number) }, 'Changes') : null,
            h('a', { class: 'btn small', href: `/api/releases/export?number=${release.number}`, download: '' }, 'Download'),
            can('EDIT') ? h('button', { class: 'btn small', onclick: () => restore(release.number) }, 'Load into draft') : null)))))
        : h('p', { class: 'muted' }, 'No releases yet. The first publish also keeps the current content as release #0.')),
    can('EDIT') ? importCard() : null);
}

/** Promotion (§23): a release downloaded on one server is imported into another server's draft, then published there. */
function importCard() {
  const file = h('input', { type: 'file', accept: '.zip,application/zip' });
  const load = async () => {
    if (!file.files[0]) return toast('Pick a release .zip first.', 'error');
    if (!window.confirm('Replace the draft with this release? It is not live until you publish it here.')) return;
    try {
      const result = await api.importBundle(file.files[0]);
      toast(`Loaded ${result.files} file(s) into the draft. Validate, then publish.`, 'good');
      refresh();
    } catch (error) {
      fail(error);
    }
  };
  return h('div', { class: 'card stack' },
    h('h2', {}, 'Import a release from another server'),
    h('p', { class: 'small muted' }, 'To promote content from development to staging to production: Download a release on one server, '
      + 'import it here, check the changes, then publish. The import only replaces the draft.'),
    h('div', { class: 'row' }, file, h('button', { class: 'btn', onclick: load }, 'Load into draft')));
}

async function compare(from, to) {
  try {
    const changes = await api.compare(from, to);
    toast(changes.length ? changes.map(change => `${change.kind.toLowerCase()} ${change.path}`).join('\n') : 'No file changes.');
  } catch (error) {
    fail(error);
  }
}

async function restore(number) {
  if (!window.confirm(`Replace the draft with release #${number}? Publishing it afterwards makes a new release.`)) return;
  try {
    await api.restore(number);
    toast(`Release #${number} is in the draft. Review it, then publish.`, 'good');
    refresh();
  } catch (error) {
    fail(error);
  }
}

// --- Audit ---

async function auditView(container) {
  const entries = await api.audit(200);
  clear(container,
    h('h1', {}, 'Audit log'),
    h('div', { class: 'card' },
      h('p', { class: 'small muted' }, 'Every sign-in, edit, publish and refused request, newest first. It is append-only.'),
      h('table', {},
        h('thead', {}, h('tr', {}, h('th', {}, 'When'), h('th', {}, 'Who'), h('th', {}, 'What'), h('th', {}, 'Target'), h('th', {}, 'Detail'))),
        h('tbody', {}, entries.map(entry => h('tr', {},
          h('td', {}, when(entry.at)), h('td', {}, entry.actor), h('td', {}, entry.action),
          h('td', {}, h('code', {}, entry.target)), h('td', { class: 'small muted' }, entry.detail)))))));
}

start();
