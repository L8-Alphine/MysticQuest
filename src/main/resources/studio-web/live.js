// Live Sessions: who is online, the runtime's limits, counters and slowest operations, and one
// player's story state as /mq debug shows it. Read-only, refreshed every 10 seconds while open;
// interventions stay in game, where each is audited.
import { api, can } from './api.js';
import { badge, clear, h } from './dom.js';
import { fail } from './app.js';

let selected = null;
let timer = null;
window.addEventListener('studio:navigate', () => clearInterval(timer));

export async function liveView(container) {
  clearInterval(timer);
  if (!can('LIVE')) {
    clear(container, h('h1', {}, 'Live sessions'), h('div', { class: 'card' },
      h('p', { class: 'muted' }, 'Watching live sessions needs mysticquests.studio.live.')));
    return;
  }
  const players = h('div', {});
  const metrics = h('div', {});
  const detail = h('div', {});
  const auto = h('input', { type: 'checkbox', checked: true });
  clear(container,
    h('div', { class: 'row' }, h('h1', { class: 'grow' }, 'Live sessions'),
      h('label', { class: 'row small muted' }, auto, 'Refresh every 10 s'),
      h('button', { class: 'btn', onclick: () => draw() }, 'Refresh')),
    h('div', { class: 'split' }, h('div', { class: 'stack' }, players, metrics), detail));

  const draw = async () => {
    if (!container.isConnected) {
      clearInterval(timer);
      return;
    }
    try {
      const overview = await api.live();
      drawPlayers(players, overview.players, detail);
      drawMetrics(metrics, overview.metrics, overview.integrations || []);
      if (selected) await drawPlayer(detail, selected);
    } catch (error) {
      clearInterval(timer);
      fail(error);
    }
  };
  await draw();
  if (!selected) clear(detail, h('div', { class: 'card' }, h('p', { class: 'muted' },
    'Pick a player to see their quests, story sessions, tags, variables, puzzles, story entities, audio and scene.')));
  timer = setInterval(() => auto.checked && draw(), 10_000);
}

function drawPlayers(container, players, detail) {
  clear(container, h('div', { class: 'card' },
    h('h3', {}, `Online (${players.length})`),
    players.length ? h('ul', { class: 'list' }, players.map(player => h('li', {
      class: selected?.id === player.id ? 'active' : '',
      onclick: () => { selected = player; drawPlayers(container, players, detail); drawPlayer(detail, player); },
    }, h('span', {}, player.name), player.activeStories ? badge(`${player.activeStories} stor${player.activeStories === 1 ? 'y' : 'ies'}`) : null)))
      : h('p', { class: 'muted' }, 'Nobody is online.')));
}

function drawMetrics(container, metrics, integrations) {
  const status = integrations.length ? h('div', { class: 'card stack' },
    h('h3', {}, 'Integrations'),
    h('table', {}, h('tbody', {}, integrations.map(entry => h('tr', {},
      h('td', {}, entry.name),
      h('td', {}, badge(entry.state, { active: 'good', partial: 'warn', absent: '', disabled: '' }[entry.state] ?? 'warn')),
      h('td', { class: 'small muted' }, entry.detail)))))) : null;
  if (!metrics) {
    clear(container, h('div', { class: 'card' }, h('p', { class: 'muted' }, 'The story runtime is not running.')), status);
    return;
  }
  const counters = Object.entries(metrics.counters).filter(([, value]) => value > 0);
  const slowest = [...metrics.timings].sort((a, b) => b.maxNanos - a.maxNanos).slice(0, 6);
  const ms = nanos => `${(nanos / 1e6).toFixed(2)} ms`;
  clear(container, h('div', { class: 'card stack' },
    h('h3', {}, 'Runtime'),
    h('table', {}, h('tbody', {}, metrics.gauges.map(gauge => h('tr', {},
      h('td', {}, gauge.name),
      h('td', {}, String(gauge.value), gauge.limit > 0 ? ` / ${gauge.limit}` : '',
        gauge.limit > 0 && gauge.value > gauge.limit ? [' ', badge('over the limit', 'bad')] : null))))),
    counters.length ? h('p', { class: 'small' }, counters.map(([name, value]) => `${name.toLowerCase().replaceAll('_', ' ')} ${value}`).join(' · '))
      : h('p', { class: 'small muted' }, 'No story activity counted yet.'),
    slowest.length ? h('table', {},
      h('thead', {}, h('tr', {}, h('th', {}, 'Slowest operations'), h('th', {}, 'Runs'), h('th', {}, 'Mean'), h('th', {}, 'Worst'))),
      h('tbody', {}, slowest.map(timing => h('tr', {},
        h('td', { class: 'mono' }, timing.operation), h('td', {}, String(timing.count)),
        h('td', {}, ms(timing.count ? timing.totalNanos / timing.count : 0)),
        h('td', {}, ms(timing.maxNanos), timing.maxNanos > 5e6 ? [' ', badge('slow', 'warn')] : null))))) : null), status);
}

async function drawPlayer(container, player) {
  const sections = await api.livePlayer(player.id);
  clear(container, h('div', { class: 'card stack' },
    h('div', { class: 'row' }, h('h2', { class: 'grow' }, player.name), h('code', { class: 'small' }, player.id)),
    h('p', { class: 'small muted' }, 'Read-only. To fix something, use the in-game commands (/mquest narrative …); each change is audited.'),
    Object.entries(sections).map(([section, lines]) => [
      h('h3', {}, section),
      lines.length ? h('div', { class: 'flow' }, lines.map(line => h('div', { class: 'node' }, line)))
        : h('p', { class: 'small muted' }, 'None'),
    ])));
}
