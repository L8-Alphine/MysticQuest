// Audio: upload Ogg Vorbis clips, hear them, copy the SoundEvent id into story media, and build the
// generated sound pack. Uploads keep the original as the master; nothing is converted.
import { api, can } from './api.js';
import { badge, clear, h, toast, when } from './dom.js';
import { fail, go } from './app.js';

const KINDS = ['voice', 'sfx', 'ambient', 'stinger', 'ui', 'cinematic'];

export async function audioView(container) {
  let clips;
  try {
    clips = await api.audio();
  } catch (error) {
    clear(container, h('h1', {}, 'Audio'), h('div', { class: 'card' }, h('p', { class: 'muted' }, error.message)));
    return;
  }
  const result = h('div', {});
  clear(container,
    h('h1', {}, 'Audio'),
    can('AUDIO') ? uploadForm(result) : null,
    result,
    h('div', { class: 'card' },
      h('div', { class: 'row' }, h('h2', { class: 'grow' }, `Clips (${clips.length})`),
        can('AUDIO') && can('PUBLISH') ? h('button', { class: 'btn primary', onclick: build, disabled: !clips.length }, 'Build sound pack') : null),
      h('p', { class: 'small muted' }, 'Building writes the clips into the MysticQuests-Generated pack in mods/. '
        + 'The server loads new and changed sounds when it next starts. Story media plays a clip by its sound event id.'),
      clips.length ? h('table', {},
        h('thead', {}, h('tr', {}, ['Clip', 'Kind', 'Length', 'Format', 'Sound event', 'Listen', ''].map(label => h('th', {}, label)))),
        h('tbody', {}, clips.map(view => clipRow(view)))) : h('p', { class: 'muted' }, 'No clips uploaded yet.')));
}

function clipRow(view) {
  const clip = view.clip;
  const player = h('audio', { controls: true, preload: 'none', src: `/api/audio/file?name=${encodeURIComponent(clip.name)}` });
  const copy = async () => {
    const snippet = `{ id: mypack:${clip.name}, kind: ${clip.kind.toLowerCase()}, sound: ${view.soundEvent} }`;
    try {
      await navigator.clipboard.writeText(snippet);
      toast(`Copied a media entry:\n${snippet}`, 'good');
    } catch {
      toast(snippet);
    }
  };
  const remove = async () => {
    if (!window.confirm(`Delete ${clip.name}? Media that plays ${view.soundEvent} will be silent after the next pack build.`)) return;
    try {
      await api.deleteAudio(clip.name);
      go('audio');
    } catch (error) {
      fail(error);
    }
  };
  return h('tr', {},
    h('td', {}, h('strong', {}, clip.name), h('div', { class: 'small muted' }, `${clip.uploadedBy.replace(/ \(.*/, '')}, ${when(clip.uploadedAt)}`)),
    h('td', {}, badge(clip.kind.toLowerCase())),
    h('td', {}, `${clip.seconds.toFixed(1)} s`),
    h('td', { class: 'small' }, `${clip.channels === 1 ? 'mono' : 'stereo'}, ${(clip.sampleRate / 1000).toFixed(1)} kHz, ${Math.ceil(clip.bytes / 1024)} KiB`),
    h('td', {}, h('code', { class: 'small' }, view.soundEvent), ' ', h('button', { class: 'btn small', onclick: copy }, 'Copy media entry')),
    h('td', {}, player),
    h('td', {}, can('AUDIO') ? h('button', { class: 'btn small danger', onclick: remove }, 'Delete') : null));
}

function uploadForm(result) {
  const file = h('input', { type: 'file', accept: '.ogg,audio/ogg' });
  const name = h('input', { type: 'text', placeholder: 'warden_greeting' });
  const kind = h('select', {}, KINDS.map(value => h('option', { value }, value)));
  file.addEventListener('change', () => {
    if (!name.value && file.files[0]) {
      name.value = file.files[0].name.replace(/\.[^.]+$/, '').toLowerCase().replace(/[^a-z0-9_]+/g, '_').replace(/^_+/, '').slice(0, 48);
    }
  });
  const upload = async event => {
    event.preventDefault();
    if (!file.files[0]) return toast('Pick an .ogg file first.', 'error');
    try {
      const uploaded = await api.uploadAudio(name.value, kind.value, file.files[0]);
      toast(`Uploaded ${uploaded.clip.clip.name} as ${uploaded.clip.soundEvent}.`, 'good');
      if (uploaded.warnings.length) {
        clear(result, h('div', { class: 'card' }, uploaded.warnings.map(warning => h('p', { class: 'problem warning' }, warning))));
      }
      setTimeout(() => go('audio'), uploaded.warnings.length ? 4000 : 0);
    } catch (error) {
      fail(error);
    }
  };
  return h('form', { class: 'card stack', onsubmit: upload },
    h('h2', {}, 'Upload a clip'),
    h('p', { class: 'small muted' }, 'Ogg Vorbis (.ogg) only, up to 8 MiB: Hytale plays nothing else, and the server cannot convert WAV or FLAC. '
      + 'Use mono for anything heard from an NPC or a place. Uploading an existing name replaces it.'),
    h('div', { class: 'grid2' },
      h('label', { class: 'field' }, h('span', {}, 'File'), file),
      h('label', { class: 'field' }, h('span', {}, 'Clip name'), name),
      h('label', { class: 'field' }, h('span', {}, 'Kind'), kind)),
    h('button', { class: 'btn primary', type: 'submit' }, 'Upload'));
}

async function build() {
  try {
    const built = await api.buildSoundPack();
    toast(`Built ${built.folder} with ${built.sounds} sound(s)${built.removed ? `, removed ${built.removed} old file(s)` : ''}. `
      + 'Restart the server to load the changes.', 'good');
  } catch (error) {
    fail(error);
  }
}
