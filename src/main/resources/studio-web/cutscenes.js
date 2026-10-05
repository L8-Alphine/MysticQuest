// Cutscene Studio (§17): a timeline of steps at their times, a table to edit them, and the onEnd
// actions that run however the scene ends. Cosmetic steps are dropped when a player skips.
import { can } from './api.js';
import { clear, field, h, jsonEditor, toast } from './dom.js';
import { saveItem } from './model.js';
import { fail, refresh } from './app.js';

const STEP_FIELDS = ['at', 'type', 'cosmetic'];

export function cutsceneEditor(container, item) {
  const scene = structuredClone(item.value);
  scene.steps = Array.isArray(scene.steps) ? scene.steps : [];
  const editable = can('AUDIO');
  const onEnd = jsonEditor(scene.onEnd ?? [], 5);
  const extra = jsonEditor(Object.fromEntries(Object.entries(scene).filter(([key]) => !['id', 'story', 'skippable', 'steps', 'onEnd'].includes(key))), 3);
  const params = new Map();
  const timeline = h('div', { class: 'graph' });
  const table = h('div', {});

  const drawTimeline = () => {
    const end = Math.max(5, ...scene.steps.map(step => Number(step.at) || 0)) + 1;
    const width = 760, row = 22, top = 24;
    const ns = 'http://www.w3.org/2000/svg';
    const svg = (tag, attrs = {}, text) => {
      const element = document.createElementNS(ns, tag);
      Object.entries(attrs).forEach(([key, value]) => element.setAttribute(key, value));
      if (text !== undefined) element.textContent = text;
      return element;
    };
    const x = seconds => 20 + (seconds / end) * (width - 40);
    const chart = svg('svg', { width, height: top + scene.steps.length * row + 10, role: 'img', 'aria-label': 'Cutscene timeline' });
    for (let second = 0; second <= end; second += end > 30 ? 5 : 1) {
      chart.append(svg('line', { x1: x(second), x2: x(second), y1: 14, y2: top + scene.steps.length * row, class: 'g-grid' }));
      chart.append(svg('text', { x: x(second) + 2, y: 11, class: 'g-tick' }, `${second}s`));
    }
    [...scene.steps].sort((a, b) => (a.at ?? 0) - (b.at ?? 0)).forEach((step, index) => {
      const group = svg('g', { class: 'g-node', transform: `translate(${x(Number(step.at) || 0)},${top + index * row})` });
      group.append(svg('rect', { width: 8, height: 14, rx: 2 }));
      group.append(svg('text', { x: 12, y: 11 }, `${step.at ?? 0}s ${step.type ?? '?'}${step.cosmetic ? ' (cosmetic)' : ''}`));
      chart.append(group);
    });
    clear(timeline, chart);
  };

  const drawTable = () => {
    params.clear();
    clear(table,
      h('table', {}, h('thead', {}, h('tr', {}, ['At (s)', 'Action', 'Cosmetic', 'Parameters (JSON)', ''].map(label => h('th', {}, label)))),
        h('tbody', {}, scene.steps.map((step, index) => {
          const rest = jsonEditor(Object.fromEntries(Object.entries(step).filter(([key]) => !STEP_FIELDS.includes(key))), 2);
          params.set(step, rest);
          rest.element.addEventListener('change', drawTimeline);
          const at = field('', step, 'at', { type: 'number' });
          at.addEventListener('input', drawTimeline);
          return h('tr', {},
            h('td', {}, at), h('td', {}, field('', step, 'type', { placeholder: 'mysticquests:media.play' })),
            h('td', {}, field('', step, 'cosmetic', { type: 'checkbox' })), h('td', {}, rest.element),
            h('td', {}, editable ? h('button', { class: 'btn small danger', onclick: () => { scene.steps.splice(index, 1); drawTable(); drawTimeline(); } }, 'Remove') : null));
        }))),
      editable ? h('button', { class: 'btn small', onclick: () => { scene.steps.push({ at: 0, type: '' }); drawTable(); drawTimeline(); } }, 'Add step') : null);
  };
  drawTable();
  drawTimeline();

  const save = async () => {
    try {
      const value = { id: scene.id, story: scene.story || undefined, skippable: scene.skippable === false ? false : undefined, ...extra.read(),
        steps: [...scene.steps].sort((a, b) => (a.at ?? 0) - (b.at ?? 0)).map(step => ({ at: Number(step.at) || 0, type: step.type,
          ...(step.cosmetic ? { cosmetic: true } : {}), ...(params.get(step)?.read() ?? {}) })),
        onEnd: onEnd.read() };
      if (!value.onEnd.length) delete value.onEnd;
      Object.keys(value).forEach(key => value[key] === undefined && delete value[key]);
      if (await saveItem(item, value)) {
        toast(`Saved ${scene.id}.`, 'good');
        refresh();
      }
    } catch (error) {
      fail(error);
    }
  };
  const skippable = { value: scene.skippable !== false };
  const skip = field('Players may skip it', skippable, 'value', { type: 'checkbox' });
  skip.querySelector('input').addEventListener('change', () => { scene.skippable = skippable.value ? undefined : false; });

  clear(container, h('div', { class: 'card stack' },
    h('h2', {}, scene.id),
    h('div', { class: 'grid2' }, field('Story', scene, 'story'), skip),
    h('h3', {}, 'Timeline'), timeline,
    h('h3', {}, 'Steps'),
    h('p', { class: 'small muted' }, 'Each step is an ordinary action run at its time. Mark camera moves, sounds and effects cosmetic: '
      + 'they are dropped when a player skips, while state changes still happen.'),
    table,
    h('h3', {}, 'When it ends'),
    h('p', { class: 'small muted' }, 'Runs however the scene ends: played out, skipped, replaced, or finished when a player rejoins.'),
    onEnd.element,
    h('details', {}, h('summary', { class: 'small muted' }, 'Other fields (JSON)'), extra.element),
    editable ? h('button', { class: 'btn primary', onclick: save }, 'Save to draft') : h('p', { class: 'muted' }, 'Editing cutscenes needs mysticquests.studio.audio.')));
}
