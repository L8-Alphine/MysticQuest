// Players: look a player up and correct their quest state (Redesign Bible §9.1). Reading needs
// mysticquests.studio.live. Every change needs mysticquests.studio.players and a reason; it goes
// through the same server service as /mquest player and the in-game admin page, which checks it
// the way the game would and writes it to the audit trail. Destructive changes ask first.
import { api, can } from './api.js';
import { badge, clear, h, toast, when } from './dom.js';
import { fail } from './app.js';

let selected = null; // { id, name }
let reasonText = '';

const PARTS = [
  { id: 'QUESTS', label: 'v1 quests', hint: 'active, completed and abandoned, with their quest variables', progress: true },
  { id: 'TAGS', label: 'v1 tags', hint: 'player-scope tags', progress: true },
  { id: 'VARIABLES', label: 'v1 variables', hint: 'player-scope variables', progress: true },
  { id: 'STORY_STATE', label: 'Story state', hint: 'their own story variables, tags and trigger overrides', progress: true },
  { id: 'SESSIONS', label: 'Story sessions', hint: 'their own active stories restart; party stories are never touched', progress: true },
  { id: 'PREFERENCES', label: 'Saved settings', hint: 'tracker density, pop-ups, subtitles, voice language', progress: false },
];

export async function playersView(container) {
  if (!can('LIVE')) {
    clear(container, h('h1', {}, 'Players'), h('div', { class: 'card' },
      h('p', { class: 'muted' }, 'Looking players up needs mysticquests.studio.live; changing them needs mysticquests.studio.players.')));
    return;
  }
  const list = h('div', {});
  const detail = h('div', {});
  const find = h('input', { type: 'text', placeholder: 'Name or UUID', 'aria-label': 'Find a player' });
  const lookup = async event => {
    event.preventDefault();
    try {
      const found = await api.findPlayer(find.value);
      selected = { id: found.player, name: find.value.trim() };
      await refresh();
    } catch (error) {
      fail(error);
    }
  };
  const refresh = async () => {
    try {
      const overview = await api.live();
      drawList(list, overview.players, refresh);
      if (selected) await drawPlayer(detail);
    } catch (error) {
      fail(error);
    }
  };
  clear(container,
    h('div', { class: 'row' }, h('h1', { class: 'grow' }, 'Players'),
      h('button', { class: 'btn', onclick: refresh }, 'Refresh')),
    h('p', { class: 'muted small' }, can('PLAYERS')
      ? 'Inspect and correct a player\'s quest state. Every change needs a reason and is audited; the same tools are in game under /mquest admin and /mquest player.'
      : 'Read-only: changing a player needs mysticquests.studio.players.'),
    h('div', { class: 'split' },
      h('div', { class: 'stack' },
        h('form', { class: 'card row', onsubmit: lookup }, h('div', { class: 'grow' }, find), h('button', { class: 'btn primary', type: 'submit' }, 'Find')),
        list),
      detail));
  await refresh();
  if (!selected) {
    clear(detail, h('div', { class: 'card' }, h('p', { class: 'muted' },
      'Pick an online player, or find anyone by UUID, to see their quests, tags, variables, story state and sessions.')));
  }
}

function drawList(container, players, refresh) {
  clear(container, h('div', { class: 'card' },
    h('h3', {}, `Online (${players.length})`),
    players.length ? h('ul', { class: 'list' }, players.map(player => h('li', {
      class: selected?.id === player.id ? 'active' : '',
      onclick: () => { selected = { id: player.id, name: player.name }; refresh(); },
    }, h('span', {}, player.name), player.activeStories ? badge(`${player.activeStories} stor${player.activeStories === 1 ? 'y' : 'ies'}`) : null)))
      : h('p', { class: 'muted' }, 'Nobody is online. Find offline players by UUID.')));
}

async function drawPlayer(container) {
  const state = await api.playerState(selected.id);
  const editable = can('PLAYERS');
  const reason = h('input', { type: 'text', value: reasonText, placeholder: 'Why you are changing this player (required, audited)',
    'aria-label': 'Reason', oninput: () => { reasonText = reason.value; } });

  const act = async (action, params = {}, confirmText = '') => {
    if (!reasonText.trim()) {
      toast('Give a reason first; every change to a player is audited.', 'error');
      reason.focus();
      return;
    }
    if (confirmText && !window.confirm(confirmText)) return;
    try {
      const outcome = await api.changePlayer(selected.id, { action, reason: reasonText, ...params });
      toast(outcome.message, outcome.ok ? 'good' : 'error');
      await drawPlayer(container);
    } catch (error) {
      fail(error);
    }
  };
  const button = (label, onclick, kind = '') => editable ? h('button', { class: `btn small ${kind}`, onclick }, label) : null;
  const who = state.name || selected.name || state.player;

  clear(container,
    header(state, who, reason, editable, act),
    questsCard(state, who, button, act, editable),
    v1Card(state, button, act, editable),
    storyCard(state, who, button, act, editable));
}

function header(state, who, reason, editable, act) {
  const clearBox = editable ? clearPanel(who, act) : null;
  return h('div', { class: 'card stack' },
    h('div', { class: 'row' },
      h('h2', { class: 'grow' }, who),
      state.online ? badge('online', 'good') : badge('offline'),
      h('code', { class: 'small' }, state.player)),
    h('p', { class: 'small muted' }, summary(state)),
    editable ? h('label', { class: 'field' }, h('span', {}, 'Reason for changes'), reason) : null,
    (state.problems || []).map(problem => h('div', { class: 'problem error' }, problem)),
    clearBox);
}

function summary(state) {
  const quests = state.quests.filter(quest => quest.status === 'ACTIVE' || quest.status === 'TRACKED').length;
  const stories = state.sessions.filter(session => session.active).length;
  return `${quests} active quest${quests === 1 ? '' : 's'} · ${stories} active stor${stories === 1 ? 'y' : 'ies'}`;
}

function clearPanel(who, act) {
  const chosen = new Set(PARTS.filter(part => part.progress).map(part => part.id));
  const box = h('div', { class: 'danger-zone stack', hidden: true },
    h('h3', {}, 'Clear player state'),
    h('p', { class: 'small muted' }, 'Rewards already given are not taken back. Each part is cleared and audited on its own.'),
    PARTS.map(part => {
      const input = h('input', { type: 'checkbox', checked: chosen.has(part.id),
        onchange: () => (input.checked ? chosen.add(part.id) : chosen.delete(part.id)) });
      return h('label', { class: 'check' }, input, h('span', {}, h('strong', {}, part.label), ' ', h('span', { class: 'muted small' }, part.hint)));
    }),
    h('div', { class: 'row' },
      h('button', { class: 'btn danger', onclick: () => {
        if (!chosen.size) {
          toast('Choose at least one part to clear.', 'error');
          return;
        }
        const names = PARTS.filter(part => chosen.has(part.id)).map(part => part.label.toLowerCase()).join(', ');
        act('clear', { parts: [...chosen] }, `Clear ${names} for ${who}? This cannot be undone.`);
      } }, 'Clear selected'),
      h('button', { class: 'btn', onclick: () => { box.hidden = true; opener.hidden = false; } }, 'Cancel')));
  const opener = h('button', { class: 'btn danger', onclick: () => { box.hidden = false; opener.hidden = true; } }, 'Clear state…');
  return h('div', {}, opener, box);
}

function questsCard(state, who, button, act, editable) {
  const statusBadge = status => ({
    TRACKED: badge('tracked', 'warn'),
    ACTIVE: badge('active', 'good'),
    COMPLETED: badge('completed', 'good'),
    ABANDONED: badge('abandoned'),
  })[status];
  const rows = state.quests.map(quest => {
    const actions = [];
    if (quest.status === 'ACTIVE' || quest.status === 'TRACKED') {
      actions.push(button('Complete', () => act('quest.complete', { quest: quest.questId })));
      if (quest.status === 'ACTIVE') actions.push(button('Track', () => act('quest.track', { quest: quest.questId })));
      actions.push(button('Abandon', () => act('quest.abandon', { quest: quest.questId },
        `Abandon ${quest.name} for ${who}? Their progress on it is lost.`), 'danger'));
    }
    if (quest.status === 'ABANDONED') actions.push(button('Allow again', () => act('quest.allow', { quest: quest.questId })));
    actions.push(button('Reset', () => act('quest.reset', { quest: quest.questId },
      `Reset ${quest.name} for ${who}? Every record of it is removed.`), 'danger'));
    return h('tr', {},
      h('td', {}, h('div', {}, quest.name), h('code', { class: 'small muted' }, quest.questId)),
      h('td', {}, statusBadge(quest.status)),
      h('td', { class: 'small' }, quest.detail, quest.at ? h('div', { class: 'muted' }, when(quest.at)) : null),
      h('td', { class: 'actions' }, actions));
  });

  const questInput = h('input', { type: 'text', placeholder: 'package:quest', 'aria-label': 'Quest id' });
  const objectives = state.quests.filter(quest => quest.objectives?.length)
    .flatMap(quest => quest.objectives.map(objective => ({ quest, objective })));
  const objectiveSelect = h('select', { 'aria-label': 'Objective' }, objectives.map(({ quest, objective }, index) =>
    h('option', { value: String(index) }, `${quest.name} — ${objective.name} (${objective.current}/${objective.target})`)));
  const amount = h('input', { type: 'number', min: 0, value: 0, 'aria-label': 'Objective value' });

  return h('div', { class: 'card stack' },
    h('h3', {}, `Quests (${state.quests.length})`),
    rows.length ? h('table', {}, h('thead', {}, h('tr', {}, h('th', {}, 'Quest'), h('th', {}, 'Status'), h('th', {}, 'Progress'), h('th', {}, ''))),
      h('tbody', {}, rows)) : h('p', { class: 'muted small' }, 'No quest records.'),
    editable ? h('div', { class: 'row' }, h('div', { class: 'grow' }, questInput),
      h('button', { class: 'btn primary', onclick: () => act('quest.start', { quest: questInput.value.trim() }) }, 'Start quest')) : null,
    editable && objectives.length ? h('div', { class: 'row' }, h('div', { class: 'grow' }, objectiveSelect), amount,
      h('button', { class: 'btn', onclick: () => {
        const pick = objectives[Number(objectiveSelect.value)];
        if (!pick) return;
        act('quest.objective', { quest: pick.quest.questId, objective: pick.objective.id, amount: Number(amount.value) });
      } }, 'Set objective')) : null);
}

function v1Card(state, button, act, editable) {
  const tag = h('input', { type: 'text', placeholder: 'tag', 'aria-label': 'Tag' });
  const key = h('input', { type: 'text', placeholder: 'variable', 'aria-label': 'Variable' });
  const value = h('input', { type: 'text', placeholder: 'value', 'aria-label': 'Value' });
  const variables = Object.entries(state.variables || {});
  return h('div', { class: 'card stack' },
    h('h3', {}, 'Tags and variables (v1, player scope)'),
    state.tags.length ? h('div', { class: 'chips' }, state.tags.map(name => h('span', { class: 'chip' }, name,
      editable ? h('button', { class: 'chip-x', title: `Remove ${name}`, 'aria-label': `Remove ${name}`,
        onclick: () => act('tag.remove', { id: name }) }, '×') : null)))
      : h('p', { class: 'muted small' }, 'No tags.'),
    editable ? h('div', { class: 'row' }, h('div', { class: 'grow' }, tag),
      h('button', { class: 'btn', onclick: () => act('tag.add', { id: tag.value.trim() }) }, 'Add tag')) : null,
    variables.length ? h('table', {}, h('tbody', {}, variables.map(([name, current]) => h('tr', {},
      h('td', { class: 'mono' }, name), h('td', {}, current),
      h('td', { class: 'actions' }, button('Remove', () => act('variable.remove', { id: name }), 'danger'))))))
      : h('p', { class: 'muted small' }, 'No variables.'),
    editable ? h('div', { class: 'row' }, h('div', { class: 'grow' }, key), h('div', { class: 'grow' }, value),
      h('button', { class: 'btn', onclick: () => act('variable.set', { id: key.value.trim(), value: value.value }) }, 'Set')) : null);
}

function storyCard(state, who, button, act, editable) {
  if (!state.narrative) {
    return h('div', { class: 'card' }, h('h3', {}, 'Story state'), h('p', { class: 'muted small' }, 'The story runtime is not running on this server.'));
  }
  const checkpoint = h('input', { type: 'text', placeholder: 'checkpoint label', 'aria-label': 'Checkpoint' });
  const storyVar = h('input', { type: 'text', placeholder: 'namespace:variable', 'aria-label': 'Story variable' });
  const storyValue = h('input', { type: 'text', placeholder: 'value', 'aria-label': 'Story value' });
  const storyTag = h('input', { type: 'text', placeholder: 'namespace:tag', 'aria-label': 'Story tag' });
  const scope = owner => owner.split('/')[0];
  return h('div', { class: 'card stack' },
    h('h3', {}, 'Story sessions'),
    state.sessions.length ? h('table', {}, h('thead', {}, h('tr', {}, h('th', {}, 'Story'), h('th', {}, 'Status'), h('th', {}, 'Node'), h('th', {}, 'Updated'), h('th', {}, ''))),
      h('tbody', {}, state.sessions.map(session => h('tr', {},
        h('td', { class: 'mono' }, session.story, session.party ? [' ', badge('party')] : null),
        h('td', {}, badge(session.status, session.active ? 'good' : '')),
        h('td', { class: 'mono small' }, session.node || '—'),
        h('td', { class: 'small muted' }, session.updated ? when(session.updated) : ''),
        h('td', { class: 'actions' }, session.active ? button('Restart', () => act('story.restart', { id: session.story },
          session.party ? `Restart ${session.story}? It is a party story, so it restarts for the whole party.`
            : `Restart ${session.story} for ${who}? Their progress in it starts over.`), 'danger') : null)))))
      : h('p', { class: 'muted small' }, 'No story sessions.'),
    editable ? h('div', { class: 'row' }, h('div', { class: 'grow' }, checkpoint),
      h('button', { class: 'btn danger', onclick: () => act('story.rewind', { id: checkpoint.value.trim() },
        `Rewind ${who}'s story to checkpoint ${checkpoint.value.trim()}?`) }, 'Rewind to checkpoint')) : null,

    h('h3', {}, 'Story variables'),
    state.storyVariables.length ? h('table', {}, h('tbody', {}, state.storyVariables.map(line => h('tr', {},
      h('td', { class: 'mono' }, line.id), h('td', {}, line.value),
      h('td', { class: 'small muted' }, line.preference ? badge('setting') : scope(line.owner)),
      h('td', { class: 'actions' }, line.preference ? null
        : button('Remove', () => act('story.variable.remove', { owner: line.owner, id: line.id }), 'danger'))))))
      : h('p', { class: 'muted small' }, 'No story variables.'),
    editable ? h('div', { class: 'row' }, h('div', { class: 'grow' }, storyVar), h('div', { class: 'grow' }, storyValue),
      h('button', { class: 'btn', onclick: () => act('story.variable.set', { id: storyVar.value.trim(), value: storyValue.value }) }, 'Set')) : null,

    h('h3', {}, 'Story tags'),
    state.storyTags.length ? h('table', {}, h('tbody', {}, state.storyTags.map(line => h('tr', {},
      h('td', { class: 'mono' }, line.id), h('td', { class: 'small muted' }, scope(line.owner), line.value ? ` · ${line.value}` : ''),
      h('td', { class: 'actions' }, button('Remove', () => act('story.tag.remove', { owner: line.owner, id: line.id }), 'danger'))))))
      : h('p', { class: 'muted small' }, 'No story tags.'),
    editable ? h('div', { class: 'row' }, h('div', { class: 'grow' }, storyTag),
      h('button', { class: 'btn', onclick: () => act('story.tag.add', { id: storyTag.value.trim() }) }, 'Add tag')) : null);
}
