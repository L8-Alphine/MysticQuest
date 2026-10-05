// Puzzle Studio (§11): inputs, random selection, the rule and its parameters as a form, the action
// lists as JSON, and a "try it" panel that plays the rule in the browser. The simulation is a guide
// for authors; Validate runs the server's own checks, which decide what loads.
import { can } from './api.js';
import { badge, clear, field, h, jsonEditor, toast } from './dom.js';
import { saveItem } from './model.js';
import { fail, refresh } from './app.js';

const RULES = ['all', 'any', 'n_of_m', 'sequence', 'unordered_sequence', 'exact', 'timed', 'weighted', 'groups', 'state_machine'];
const PARAMS = {
  n_of_m: ['required'], sequence: ['sequence', 'resetOnMistake'], unordered_sequence: ['sequence'], exact: ['required'],
  timed: ['window', 'required'], weighted: ['threshold'], groups: ['perGroup'], state_machine: [],
};
const ACTION_LISTS = ['onInput', 'onMistake', 'onReset', 'outputs'];
const KNOWN = ['id', 'story', 'audience', 'repeatable', 'inputs', 'selection', 'rule', 'requires', ...ACTION_LISTS];

export function puzzleEditor(container, item) {
  const puzzle = structuredClone(item.value);
  puzzle.inputs = Array.isArray(puzzle.inputs) ? puzzle.inputs : [];
  const rule = typeof puzzle.rule === 'string' ? { type: puzzle.rule } : { type: 'all', ...(puzzle.rule || {}) };
  if (Array.isArray(rule.sequence)) rule.sequence = rule.sequence.join(', ');
  const selection = { active: puzzle.selection?.active };
  const editable = can('PUZZLES');
  const lists = Object.fromEntries(ACTION_LISTS.map(name => [name, jsonEditor(puzzle[name] ?? [], 5)]));
  const requires = jsonEditor(puzzle.requires ?? null, 3);
  const machine = jsonEditor(rule.states ?? {}, 8);
  const extra = jsonEditor(Object.fromEntries(Object.entries(puzzle).filter(([key]) => !KNOWN.includes(key))), 3);

  const inputs = h('div', {});
  const drawInputs = () => clear(inputs,
    h('table', {}, h('thead', {}, h('tr', {}, ['Input', 'Trigger volume', 'Group', 'Weight', 'Toggle', ''].map(label => h('th', {}, label)))),
      h('tbody', {}, puzzle.inputs.map((input, index) => h('tr', {},
        h('td', {}, field('', input, 'id')), h('td', {}, field('', input, 'volume', { placeholder: 'world:volume' })),
        h('td', {}, field('', input, 'group')), h('td', {}, field('', input, 'weight', { type: 'number' })),
        h('td', {}, field('', input, 'toggleable', { type: 'checkbox' })),
        h('td', {}, editable ? h('button', { class: 'btn small danger', onclick: () => { puzzle.inputs.splice(index, 1); drawInputs(); } }, 'Remove') : null))))),
    editable ? h('button', { class: 'btn small', onclick: () => { puzzle.inputs.push({ id: `input_${puzzle.inputs.length + 1}` }); drawInputs(); } }, 'Add input') : null);

  const params = h('div', { class: 'grid2' });
  const drawParams = () => clear(params, (PARAMS[rule.type] || []).map(name => {
    if (name === 'resetOnMistake') return field('Start over on a wrong input', rule, name, { type: 'checkbox' });
    if (name === 'sequence') return field('Order (input ids, comma separated)', rule, name);
    if (name === 'window') return field('Time window', rule, name, { placeholder: '30s' });
    return field(name === 'perGroup' ? 'Per group' : name[0].toUpperCase() + name.slice(1), rule, name, { type: 'number' });
  }), rule.type === 'state_machine' ? h('label', { class: 'field' }, h('span', {}, 'States (JSON); also set "initial"'), field('Initial state', rule, 'initial'), machine.element) : null);
  drawParams();
  drawInputs();

  const build = () => {
    const out = { id: puzzle.id, story: puzzle.story || undefined, audience: puzzle.audience || undefined,
      repeatable: puzzle.repeatable || undefined, ...extra.read() };
    const requirement = requires.read();
    if (requirement) out.requires = requirement;
    out.inputs = puzzle.inputs.map(input => Object.fromEntries(Object.entries(input).filter(([, v]) => v !== '' && v !== undefined)));
    if (selection.active) out.selection = { ...(puzzle.selection || {}), active: Number(selection.active) };
    const ruleOut = { ...rule };
    if (typeof ruleOut.sequence === 'string') ruleOut.sequence = ruleOut.sequence.split(',').map(id => id.trim()).filter(Boolean);
    if (rule.type === 'state_machine') ruleOut.states = machine.read();
    Object.keys(ruleOut).forEach(key => (ruleOut[key] === undefined || ruleOut[key] === '') && delete ruleOut[key]);
    out.rule = Object.keys(ruleOut).length === 1 ? ruleOut.type : ruleOut;
    for (const name of ACTION_LISTS) {
      const list = lists[name].read();
      if (Array.isArray(list) && list.length) out[name] = list;
    }
    Object.keys(out).forEach(key => out[key] === undefined && delete out[key]);
    return out;
  };
  const save = async () => {
    try {
      if (await saveItem(item, build())) {
        toast(`Saved ${puzzle.id}.`, 'good');
        refresh();
      }
    } catch (error) {
      fail(error);
    }
  };

  const ruleSelect = field('Rule', rule, 'type', { options: RULES });
  ruleSelect.querySelector('select').addEventListener('change', drawParams);
  const trial = h('div', {});
  clear(container,
    h('div', { class: 'card stack' },
      h('h2', {}, puzzle.id),
      h('div', { class: 'grid2' },
        field('Story', puzzle, 'story', { hint: 'The story session it lives in; defaults to the puzzle id.' }),
        field('Who shares it', puzzle, 'audience', { options: ['auto', 'player', 'party'], hint: 'auto: the party when in one.' }),
        field('Inputs dealt per player or party', selection, 'active', { type: 'number', hint: 'Blank: every input counts. 4 of 10 deals 4.' }),
        field('Can be solved again', puzzle, 'repeatable', { type: 'checkbox' })),
      h('h3', {}, 'Inputs'), inputs,
      h('h3', {}, 'Rule'), ruleSelect, params,
      h('details', {}, h('summary', { class: 'small muted' }, 'Requirement (condition JSON) and other fields'), requires.element, extra.element),
      h('h3', {}, 'What happens'),
      ACTION_LISTS.map(name => h('details', { open: name === 'outputs' },
        h('summary', {}, { onInput: 'After each accepted input', onMistake: 'After a wrong input (sequences)', onReset: 'When reset', outputs: 'When solved (once per round)' }[name]),
        lists[name].element)),
      editable ? h('div', { class: 'row' },
        h('button', { class: 'btn primary', onclick: save }, 'Save to draft'),
        h('button', { class: 'btn', onclick: () => tryIt(trial, build()) }, 'Try it')) : h('button', { class: 'btn', onclick: () => tryIt(trial, build()) }, 'Try it')),
    trial);
}

// --- Try it ---

function tryIt(container, puzzle) {
  const rule = typeof puzzle.rule === 'string' ? { type: puzzle.rule } : puzzle.rule;
  if (['timed', 'state_machine'].includes(rule.type)) {
    clear(container, h('div', { class: 'card' }, h('p', { class: 'muted' }, `The ${rule.type} rule is not simulated here; Validate checks it can be solved.`)));
    return;
  }
  const ids = puzzle.inputs.map(input => input.id);
  const dealt = puzzle.selection?.active ? [...ids].sort(() => Math.random() - 0.5).slice(0, puzzle.selection.active) : ids;
  let active = new Set();
  let order = [];
  const log = h('div', { class: 'flow' });
  const status = h('div', {});
  const solved = () => {
    const weights = Object.fromEntries(puzzle.inputs.map(input => [input.id, Number(input.weight ?? 1)]));
    switch (rule.type) {
      case 'all': return dealt.every(id => active.has(id));
      case 'any': return active.size > 0;
      case 'n_of_m': return active.size >= Number(rule.required);
      case 'exact': return active.size === Number(rule.required);
      case 'sequence': case 'unordered_sequence': return (rule.sequence || []).every(id => active.has(id));
      case 'weighted': return [...active].reduce((sum, id) => sum + weights[id], 0) >= Number(rule.threshold);
      case 'groups': {
        const groups = {};
        puzzle.inputs.forEach(input => { groups[input.group ?? ''] ||= 0; if (active.has(input.id)) groups[input.group ?? ''] += 1; });
        return Object.values(groups).every(count => count >= Number(rule.perGroup ?? 1));
      }
      default: return false;
    }
  };
  const press = id => {
    if (!dealt.includes(id)) {
      log.append(h('div', { class: 'edge' }, `${id}: not dealt to you, so nothing happens`));
      return;
    }
    const input = puzzle.inputs.find(candidate => candidate.id === id);
    if (active.has(id) && input.toggleable) {
      active.delete(id);
      log.append(h('div', { class: 'edge' }, `${id}: released`));
    } else if (rule.type === 'sequence' && (rule.sequence || [])[order.length] !== id) {
      log.append(h('div', { class: 'node unreachable' }, `${id}: wrong order — mistake${rule.resetOnMistake === false ? '' : ', progress starts over'}`));
      if (rule.resetOnMistake !== false) {
        active = new Set();
        order = [];
      }
    } else if (!active.has(id)) {
      active.add(id);
      order.push(id);
      log.append(h('div', { class: 'edge' }, `${id}: accepted`));
    }
    draw();
  };
  const draw = () => clear(status, h('p', {}, solved() ? badge('Solved: the outputs run', 'good') : badge(`${active.size} active`)));
  clear(container, h('div', { class: 'card stack' },
    h('h3', {}, 'Try it'),
    puzzle.selection?.active ? h('p', { class: 'small muted' }, `Dealt ${dealt.length} of ${ids.length} at random, as a player would be: ${dealt.join(', ')}`) : null,
    h('div', { class: 'row' }, ids.map(id => h('button', { class: `btn small ${dealt.includes(id) ? '' : 'muted'}`, onclick: () => press(id) }, id))),
    status, log,
    h('p', { class: 'small muted' }, 'A guide only: the server deals inputs per player or party, keeps the deal, and checks the rule itself.')));
  draw();
}
