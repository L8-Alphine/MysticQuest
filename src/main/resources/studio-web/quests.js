// Quests: a list across every package, a form for the common fields, the rest as JSON, and a quest
// map of which quests start or unlock which.
import { api, can } from './api.js';
import { badge, clear, field, h, jsonEditor, toast } from './dom.js';
import { addItem, collect, deleteItem, holders, packageName, saveItem } from './model.js';
import { fail, refresh } from './app.js';

export const OBJECTIVE_TYPES = ['kill', 'gather', 'craft', 'triggerEnter', 'triggerExit', 'interactEntity', 'interactObject',
  'interactNpc', 'reachLocation', 'dialogue', 'timer', 'custom', 'signal'];
const FORM_FIELDS = ['id', 'displayName', 'description', 'category', 'rewardText', 'difficulty', 'partySize', 'lockedText', 'startOnJoin', 'stages', 'objectives'];
const CATEGORIES = ['', 'story', 'side', 'contract', 'guild', 'community', 'daily'];

let selected = null;

export async function questsView(container) {
  const documents = await api.documents(true);
  const quests = collect(documents, 'quests');
  const list = h('ul', { class: 'list' });
  const editor = h('div', {});
  const filter = h('input', { type: 'text', placeholder: 'Filter quests', oninput: () => fill() });
  const fill = () => {
    const needle = filter.value.toLowerCase();
    clear(list, quests
      .filter(quest => !needle || `${quest.id} ${quest.value.displayName ?? ''}`.toLowerCase().includes(needle))
      .map(quest => h('li', {
        class: selected && quest.path === selected.path && quest.id === selected.id ? 'active' : '',
        onclick: () => { selected = quest; fill(); questForm(editor, quest, documents); },
      }, h('span', {}, quest.value.displayName || quest.id), h('span', { class: 'small muted' }, packageName(quest.path)))));
  };
  fill();
  clear(container,
    h('div', { class: 'row' }, h('h1', { class: 'grow' }, 'Quests'),
      can('EDIT') ? h('button', { class: 'btn primary', onclick: () => newQuest(editor, documents) }, 'New quest') : null),
    h('div', { class: 'split' },
      h('div', { class: 'card stack' }, filter, quests.length ? list : h('p', { class: 'muted' }, 'No quests in the draft yet.')),
      editor));
  const again = selected && quests.find(quest => quest.path === selected.path && quest.id === selected.id);
  if (again) questForm(editor, again, documents);
  else clear(editor, questMap(quests));
}

function questForm(container, item, documents) {
  const quest = structuredClone(item.value);
  const editable = can('EDIT');
  if (Array.isArray(quest.stages)) {
    quest.stages = quest.stages.map(stage => typeof stage === 'string' ? { id: stage } : stage);
  }
  quest.objectives = Array.isArray(quest.objectives) ? quest.objectives : [];
  const rest = Object.fromEntries(Object.entries(quest).filter(([key]) => !FORM_FIELDS.includes(key)));
  const advanced = jsonEditor(rest, 12);
  const trial = h('div', {});
  const stageIds = () => (quest.stages || []).map(stage => stage.id).filter(Boolean);

  const stages = h('div', {});
  const drawStages = () => clear(stages,
    h('table', {}, h('thead', {}, h('tr', {}, h('th', {}, 'Step id'), h('th', {}, 'Name shown'), h('th', {}, ''))),
      h('tbody', {}, (quest.stages || []).map((stage, index) => h('tr', {},
        h('td', {}, field('', stage, 'id')), h('td', {}, field('', stage, 'displayName')),
        h('td', {}, editable ? h('button', { class: 'btn small danger', onclick: () => { quest.stages.splice(index, 1); drawStages(); } }, 'Remove') : null))))),
    editable ? h('button', { class: 'btn small', onclick: () => { (quest.stages ||= []).push({ id: '' }); drawStages(); } }, 'Add step') : null);

  const objectives = h('div', {});
  const drawObjectives = () => clear(objectives,
    h('table', {},
      h('thead', {}, h('tr', {}, ['Id', 'Shown as', 'Type', 'Target', 'Amount', 'Step', ''].map(label => h('th', {}, label)))),
      h('tbody', {}, quest.objectives.map((objective, index) => typeof objective !== 'object'
        ? h('tr', {}, h('td', { colspan: 6 }, h('code', {}, String(objective)), h('div', { class: 'small muted' }, 'Instruction form; edit it in Files.')), h('td', {}))
        : h('tr', {},
          h('td', {}, field('', objective, 'id')),
          h('td', {}, field('', objective, 'displayName')),
          h('td', {}, field('', objective, 'type', { options: OBJECTIVE_TYPES.includes(objective.type) || !objective.type ? ['', ...OBJECTIVE_TYPES] : [objective.type, ...OBJECTIVE_TYPES] })),
          h('td', {}, field('', objective, 'target')),
          h('td', {}, field('', objective, 'amount', { type: 'number' })),
          h('td', {}, field('', objective, 'stage', { options: ['', ...new Set([...stageIds(), ...(objective.stage ? [objective.stage] : [])])] })),
          h('td', {}, editable ? h('button', { class: 'btn small danger', onclick: () => { quest.objectives.splice(index, 1); drawObjectives(); } }, 'Remove') : null))))),
    editable ? h('button', { class: 'btn small', onclick: () => { quest.objectives.push({ id: '', type: 'kill', amount: 1 }); drawObjectives(); } }, 'Add objective') : null);

  drawStages();
  drawObjectives();

  const save = async () => {
    try {
      const others = advanced.read();
      const value = { id: quest.id, displayName: quest.displayName, description: quest.description,
        category: quest.category || undefined, rewardText: quest.rewardText || undefined, difficulty: quest.difficulty || undefined,
        partySize: quest.partySize || undefined, lockedText: quest.lockedText || undefined, startOnJoin: quest.startOnJoin,
        ...(quest.stages?.length ? { stages: quest.stages.filter(stage => stage.id) } : {}),
        objectives: quest.objectives, ...others };
      Object.keys(value).forEach(key => value[key] === undefined && delete value[key]);
      if (await saveItem(item, value)) {
        toast(`Saved ${quest.id} to the draft.`, 'good');
        selected = { path: item.path, id: quest.id };
        refresh();
      }
    } catch (error) {
      fail(error);
    }
  };
  const remove = async () => {
    if (!window.confirm(`Delete quest ${item.id} from ${item.path}?`)) return;
    try {
      if (await deleteItem(item)) {
        selected = null;
        toast(`Deleted ${item.id} from the draft.`, 'good');
        refresh();
      }
    } catch (error) {
      fail(error);
    }
  };

  clear(container,
    h('div', { class: 'card stack' },
      h('div', { class: 'row' }, h('h2', { class: 'grow' }, quest.displayName || quest.id), badge(packageName(item.path)), h('code', { class: 'small' }, item.path)),
      h('div', { class: 'grid2' },
        field('Id', quest, 'id', { readonly: true }),
        field('Name shown to players', quest, 'displayName')),
      field('Journal text', quest, 'description', { type: 'textarea' }),
      h('div', { class: 'grid2' },
        field('Journal category', quest, 'category', { options: CATEGORIES.includes(quest.category ?? '') ? CATEGORIES : [quest.category, ...CATEGORIES],
          hint: 'Where the Journal files it, and the badge on the tracker. Blank: plain "Quests".' }),
        field('Rewards players are told about', quest, 'rewardText', { placeholder: '50 gold and a grove relic',
          hint: 'Shown in the Journal. Rewards not mentioned stay a surprise.' })),
      h('div', { class: 'grid2' },
        field('Difficulty on the board', quest, 'difficulty', { placeholder: 'Hard' }),
        field('Recommended party size', quest, 'partySize', { type: 'number', hint: 'Leave blank for a solo quest.' })),
      field('Shown while it is not available yet', quest, 'lockedText', { placeholder: 'Break the grove seal first.',
        hint: 'Puts the quest on the board as a locked card with this text. Without it, a locked quest stays hidden.' }),
      field('Start when the player joins', quest, 'startOnJoin', { type: 'checkbox' }),
      h('h3', {}, 'Steps'), h('p', { class: 'small muted' }, 'Optional. Steps group objectives on the HUD and in the journal; they do not change what counts.'),
      stages,
      h('h3', {}, 'Objectives'), objectives,
      h('h3', {}, 'Everything else'),
      h('p', { class: 'small muted' }, 'Start conditions, start and completion events, rewards and other fields, as JSON. The content format guide lists them.'),
      advanced.element,
      h('div', { class: 'row' },
        editable ? h('button', { class: 'btn primary', onclick: save }, 'Save to draft') : null,
        h('button', { class: 'btn', onclick: tryIt }, 'Try it'),
        editable ? h('button', { class: 'btn danger', onclick: remove }, 'Delete quest') : null),
      editable ? null : h('p', { class: 'muted' }, 'Editing quests needs mysticquests.studio.edit.')),
    trial);

  function tryIt() {
    let others = {};
    try {
      others = advanced.read();
    } catch (error) {
      fail(error);
      return;
    }
    // The form as it is now, saved or not, so authors can try a change before saving it.
    simulate(trial, { ...others, ...quest, stages: quest.stages, objectives: quest.objectives });
  }
}

// --- Try it (§11 "Testing: quest simulation") ---

const humanise = id => String(id).replace(/[_-]+/g, ' ').replace(/\b\w/g, letter => letter.toUpperCase());

/**
 * The quest's steps as the HUD and journal group them: an objective with no stage joins the step
 * above it; objectives before any stage form "Objectives"; declared stages fix order and names.
 */
export function steps(quest) {
  const declared = (quest.stages || []).map(stage => typeof stage === 'string' ? { id: stage } : stage);
  const groups = [];
  const byId = new Map();
  const group = id => {
    if (!byId.has(id)) {
      const stage = declared.find(candidate => candidate.id === id);
      const entry = { id, name: id === null ? 'Objectives' : stage?.displayName || humanise(id), objectives: [] };
      byId.set(id, entry);
      groups.push(entry);
    }
    return byId.get(id);
  };
  declared.forEach(stage => group(stage.id));
  let current = null;
  for (const objective of quest.objectives || []) {
    if (typeof objective !== 'object') continue;
    if (objective.stage) current = objective.stage;
    group(current).objectives.push(objective);
  }
  const ordered = groups.filter(entry => entry.objectives.length);
  return declared.length
    ? [...ordered.filter(entry => entry.id === null), ...declared.map(stage => byId.get(stage.id)).filter(entry => entry.objectives.length)]
    : ordered;
}

function simulate(container, quest) {
  const progress = new Map((quest.objectives || []).filter(o => typeof o === 'object').map(objective => [objective, 0]));
  const target = objective => Math.max(1, Number(objective.amount ?? 1));
  const done = objective => progress.get(objective) >= target(objective);
  const draw = () => {
    const grouped = steps(quest);
    const all = [...progress.keys()];
    const finished = all.filter(done).length;
    const currentStep = grouped.find(step => step.objectives.some(objective => !done(objective)));
    const complete = all.length > 0 && finished === all.length;
    clear(container, h('div', { class: 'card stack' },
      h('h3', {}, 'Try it'),
      h('div', { class: 'problem' },
        h('div', { class: 'where' }, 'What the tracker shows'),
        complete ? h('strong', {}, 'Quest complete')
          : [h('strong', {}, currentStep ? currentStep.name : 'No objectives'), ' · ',
            `${finished} of ${all.length} objectives`,
            currentStep ? h('ul', {}, currentStep.objectives.map(objective =>
              h('li', {}, `${objective.displayName || objective.id}  ${Math.min(progress.get(objective), target(objective))} / ${target(objective)}`))) : null]),
      grouped.map(step => h('div', {},
        h('div', { class: 'small muted' }, step.name),
        step.objectives.map(objective => h('div', { class: 'row' },
          h('span', { class: 'grow' }, objective.displayName || objective.id, ' ', badge(objective.type || '?')),
          h('span', { class: 'small' }, `${Math.min(progress.get(objective), target(objective))} / ${target(objective)}`),
          h('button', { class: 'btn small', disabled: done(objective), onclick: () => { progress.set(objective, progress.get(objective) + 1); draw(); } }, '+1'),
          h('button', { class: 'btn small', disabled: done(objective), onclick: () => { progress.set(objective, target(objective)); draw(); } }, 'Complete'))))),
      complete ? h('p', { class: 'small' }, `On completion: ${(quest.completeEvents || []).length} completion event(s) and `
        + `${(quest.rewards || []).length} reward(s) run, once.`) : null,
      h('div', { class: 'row' }, h('button', { class: 'btn small', onclick: () => simulate(container, quest) }, 'Start over')),
      h('p', { class: 'small muted' }, 'Steps only change what players are shown; every objective counts whenever it happens, '
        + 'and the quest completes when all are done.')));
  };
  draw();
}

function newQuest(container, documents) {
  const places = holders(documents, 'quests');
  const values = { id: '', displayName: '', path: places[0] || 'packages/new_package/quests.yml' };
  const create = async () => {
    if (!/^[a-z0-9_.-]+$/i.test(values.id)) return toast('Give the quest an id: letters, digits, _ . -', 'error');
    try {
      await addItem(values.path, 'quests', { id: values.id, displayName: values.displayName || values.id, objectives: [] });
      selected = { path: values.path, id: values.id };
      toast(`Created ${values.id} in the draft.`, 'good');
      refresh();
    } catch (error) {
      fail(error);
    }
  };
  clear(container, h('div', { class: 'card stack' },
    h('h2', {}, 'New quest'),
    field('Id', values, 'id', { placeholder: 'wolf_trouble' }),
    field('Name shown to players', values, 'displayName'),
    field('File', values, 'path', { hint: 'An existing quests file, or a new path such as packages/greenvale/quests.yml.' }),
    places.length ? h('p', { class: 'small muted' }, 'Existing: ', places.join(', ')) : null,
    h('button', { class: 'btn primary', onclick: create }, 'Create')));
}

// --- Quest map ---

/** Quest ids a value starts or requires, found anywhere in it. */
function references(value, type, found = new Set()) {
  if (Array.isArray(value)) value.forEach(entry => references(entry, type, found));
  else if (value && typeof value === 'object') {
    if (value.type === type && typeof value.quest === 'string') found.add(local(value.quest));
    Object.values(value).forEach(entry => references(entry, type, found));
  } else if (typeof value === 'string' && value.startsWith(`${type} `)) {
    found.add(local(value.slice(type.length + 1).trim().split(/\s+/)[0]));
  }
  return found;
}

// 'pack:id', and cross-package 'pack-sub>id', both name the quest 'id'.
const local = id => String(id).split(/[:>]/).pop();

function questMap(quests) {
  if (!quests.length) return h('div', { class: 'card' }, h('p', { class: 'muted' }, 'Create a quest to see the quest map.'));
  const byId = new Map(quests.map(quest => [local(quest.id), quest]));
  const edges = [];
  for (const quest of quests) {
    const from = local(quest.id);
    for (const started of references([quest.value.completeEvents, quest.value.rewards], 'startQuest')) {
      if (byId.has(started) && started !== from) edges.push([from, started, 'starts']);
    }
    for (const required of references(quest.value.startConditions, 'questCompleted')) {
      if (byId.has(required) && required !== from) edges.push([required, from, 'unlocks']);
    }
  }
  // Columns by longest chain of prerequisites; cycles are cut where they close.
  const depth = new Map();
  const visit = (id, trail = new Set()) => {
    if (depth.has(id)) return depth.get(id);
    if (trail.has(id)) return 0;
    trail.add(id);
    const parents = edges.filter(([, to]) => to === id).map(([from]) => from);
    const value = parents.length ? Math.max(...parents.map(parent => visit(parent, trail) + 1)) : 0;
    depth.set(id, value);
    return value;
  };
  [...byId.keys()].forEach(id => visit(id));
  const columns = [];
  for (const [id, column] of depth) (columns[column] ||= []).push(id);
  const W = 190, H = 46, GX = 70, GY = 18, P = 16;
  const position = new Map();
  columns.forEach((ids, column) => ids.forEach((id, row) => position.set(id, { x: P + column * (W + GX), y: P + row * (H + GY) })));
  const width = P * 2 + columns.length * (W + GX) - GX;
  const height = P * 2 + Math.max(...columns.map(ids => ids.length)) * (H + GY) - GY;
  const ns = 'http://www.w3.org/2000/svg';
  const svg = (tag, attrs = {}, ...children) => {
    const element = document.createElementNS(ns, tag);
    Object.entries(attrs).forEach(([key, value]) => element.setAttribute(key, value));
    children.flat(Infinity).forEach(child => element.append(child instanceof Node ? child : document.createTextNode(String(child))));
    return element;
  };
  const graph = svg('svg', { width, height, viewBox: `0 0 ${width} ${height}`, role: 'img', 'aria-label': 'Quest map' },
    svg('defs', {}, svg('marker', { id: 'arrow', viewBox: '0 0 10 10', refX: 9, refY: 5, markerWidth: 7, markerHeight: 7, orient: 'auto' },
      svg('path', { d: 'M0,0 L10,5 L0,10 z' }))),
    edges.map(([from, to, kind]) => {
      const a = position.get(from), b = position.get(to);
      const x1 = a.x + W, y1 = a.y + H / 2, x2 = b.x, y2 = b.y + H / 2;
      const mid = (x1 + x2) / 2;
      return svg('path', { class: 'g-edge', d: `M${x1},${y1} C${mid},${y1} ${mid},${y2} ${x2},${y2}` }, svg('title', {}, `${from} ${kind} ${to}`));
    }),
    [...position].map(([id, at]) => {
      const quest = byId.get(id);
      const name = String(quest.value.displayName || id);
      return svg('g', { class: 'g-node quest', transform: `translate(${at.x},${at.y})` },
        svg('rect', { width: W, height: H, rx: 8 }),
        svg('text', { x: 10, y: 19 }, name.length > 26 ? `${name.slice(0, 25)}…` : name),
        svg('text', { x: 10, y: 35, class: 'sub' }, `${(quest.value.objectives || []).length} objective(s)`));
    }));
  return h('div', { class: 'card stack' },
    h('h2', {}, 'Quest map'),
    h('p', { class: 'small muted' }, 'An arrow means a quest starts the next one (completion events or rewards) or is required by it (start conditions). Pick a quest to edit it.'),
    h('div', { class: 'graph' }, graph));
}
