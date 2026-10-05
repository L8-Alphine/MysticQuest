// Dialogue: conversations, their nodes and choices, a flow view that flags unreachable nodes, and the
// rest of each conversation (NPC binding, conditions, events) as JSON.
import { api, can } from './api.js';
import { badge, clear, field, h, jsonEditor, toast } from './dom.js';
import { addItem, collect, holders, packageName, saveItem } from './model.js';
import { fail, refresh } from './app.js';

const NODE_FIELDS = ['id', 'text', 'voice', 'choices'];
const CHOICE_FIELDS = ['text', 'next'];
const CONVERSATION_FIELDS = ['id', 'speaker', 'start', 'nodes'];
let selected = null;

export async function dialogueView(container) {
  const documents = await api.documents(true);
  const conversations = collect(documents, 'conversations');
  const list = h('ul', { class: 'list' }, conversations.map(conversation => h('li', {
    class: selected && conversation.path === selected.path && conversation.id === selected.id ? 'active' : '',
    onclick: () => { selected = conversation; dialogueView(container); },
  }, h('span', {}, conversation.id), h('span', { class: 'small muted' }, packageName(conversation.path)))));
  const editor = h('div', {});
  clear(container,
    h('div', { class: 'row' }, h('h1', { class: 'grow' }, 'Dialogue'),
      can('DIALOGUE') ? h('button', { class: 'btn primary', onclick: () => newConversation(editor, documents) }, 'New conversation') : null),
    h('div', { class: 'split' },
      h('div', { class: 'card' }, conversations.length ? list : h('p', { class: 'muted' }, 'No conversations in the draft yet.')),
      editor));
  const again = selected && conversations.find(item => item.path === selected.path && item.id === selected.id);
  if (again) conversationForm(editor, again);
  else clear(editor, h('div', { class: 'card' }, h('p', { class: 'muted' }, 'Pick a conversation to edit its lines and choices.')));
}

/** Node ids reachable from the start through choices and node-level next links. */
export function reachable(conversation) {
  const nodes = new Map((conversation.nodes || []).map(node => [node.id, node]));
  const seen = new Set();
  // start is one line id or an ordered list (the first whose conditions pass opens); all can open.
  const starts = Array.isArray(conversation.start) ? conversation.start : [conversation.start];
  const queue = starts.filter(Boolean).length ? starts.filter(Boolean) : [conversation.nodes?.[0]?.id];
  while (queue.length) {
    const id = queue.shift();
    if (!id || seen.has(id) || !nodes.has(id)) continue;
    seen.add(id);
    const node = nodes.get(id);
    if (node.next) queue.push(node.next);
    (node.choices || []).forEach(choice => choice?.next && queue.push(choice.next));
  }
  return seen;
}

function conversationForm(container, item) {
  const conversation = structuredClone(item.value);
  conversation.nodes = Array.isArray(conversation.nodes) ? conversation.nodes : [];
  const opening = { lines: (Array.isArray(conversation.start) ? conversation.start : [conversation.start]).filter(Boolean).join(', ') };
  const readOpening = () => {
    const lines = opening.lines.split(',').map(line => line.trim()).filter(Boolean);
    return lines.length > 1 ? lines : lines[0];
  };
  const editable = can('DIALOGUE');
  const rest = jsonEditor(Object.fromEntries(Object.entries(conversation).filter(([key]) => !CONVERSATION_FIELDS.includes(key))), 8);
  const nodeExtras = new Map();
  const nodes = h('div', { class: 'stack' });
  const flow = h('div', { class: 'flow' });
  const nodeIds = () => conversation.nodes.map(node => node.id).filter(Boolean);

  const drawFlow = () => {
    const live = reachable({ ...conversation, start: readOpening() });
    clear(flow, conversation.nodes.map(node => [
      h('div', { class: `node ${live.has(node.id) ? '' : 'unreachable'}` }, node.id || '(no id)', live.has(node.id) ? '' : '  — never reached'),
      (node.choices || []).filter(choice => choice && typeof choice === 'object').map(choice =>
        h('div', { class: 'edge' }, `→ "${String(choice.text ?? '').slice(0, 40)}" `, choice.next ? `⇒ ${choice.next}` : '(ends)',
          choice.next && choice.next !== 'end' && !nodeIds().includes(choice.next) ? badge('missing node', 'bad') : null)),
    ]));
  };

  const drawNodes = () => {
    nodeExtras.clear();
    clear(nodes, conversation.nodes.map((node, index) => {
      node.choices = Array.isArray(node.choices) ? node.choices : [];
      const extra = jsonEditor(Object.fromEntries(Object.entries(node).filter(([key]) => !NODE_FIELDS.includes(key))), 4);
      nodeExtras.set(node, extra);
      const choices = h('div', {});
      const drawChoices = () => clear(choices,
        h('table', {}, h('thead', {}, h('tr', {}, h('th', {}, 'Choice text'), h('th', {}, 'Goes to'), h('th', {}, ''))),
          h('tbody', {}, node.choices.map((choice, choiceIndex) => typeof choice !== 'object' ? null : h('tr', {},
            h('td', {}, field('', choice, 'text')),
            h('td', {}, field('', choice, 'next', { options: ['', 'end', ...new Set([...nodeIds(), ...(choice.next ? [choice.next] : [])])] })),
            h('td', {}, editable ? h('button', { class: 'btn small danger', onclick: () => { node.choices.splice(choiceIndex, 1); drawChoices(); drawFlow(); } }, 'Remove') : null))))),
        editable ? h('button', { class: 'btn small', onclick: () => { node.choices.push({ text: '', next: '' }); drawChoices(); } }, 'Add choice') : null,
        h('p', { class: 'small muted' }, 'Conditions and events on a choice stay as they are; edit them in Files.'));
      drawChoices();
      return h('div', { class: 'card stack' },
        h('div', { class: 'row' }, h('strong', { class: 'grow' }, node.id || 'New line'),
          editable ? h('button', { class: 'btn small danger', onclick: () => { conversation.nodes.splice(index, 1); drawNodes(); drawFlow(); } }, 'Remove line') : null),
        h('div', { class: 'grid2' }, field('Line id', node, 'id'), field('Voice line (media id)', node, 'voice', { placeholder: 'grove:warden.greeting' })),
        field('What the speaker says', node, 'text', { type: 'textarea' }),
        h('h3', {}, 'Choices'), choices,
        h('details', {}, h('summary', { class: 'small muted' }, 'Conditions, events and other fields (JSON)'), extra.element));
    }));
  };

  drawNodes();
  drawFlow();

  const save = async () => {
    try {
      const others = rest.read();
      const value = {
        id: conversation.id, speaker: conversation.speaker, start: readOpening(),
        ...others,
        nodes: conversation.nodes.map(node => {
          const extra = nodeExtras.get(node)?.read() ?? {};
          const { id, text, voice, choices } = node;
          const out = { id, text, ...(voice ? { voice } : {}), ...extra, choices: (choices || []).map(choice =>
            typeof choice === 'object' ? Object.fromEntries(Object.entries(choice).filter(([key, v]) => !(CHOICE_FIELDS.includes(key) && (v === '' || v === undefined)))) : choice) };
          if (!out.choices.length) delete out.choices;
          return out;
        }),
      };
      Object.keys(value).forEach(key => value[key] === undefined && delete value[key]);
      if (await saveItem(item, value)) {
        toast(`Saved ${conversation.id} to the draft.`, 'good');
        refresh();
      }
    } catch (error) {
      fail(error);
    }
  };

  clear(container,
    h('div', { class: 'card stack' },
      h('div', { class: 'row' }, h('h2', { class: 'grow' }, conversation.id), badge(packageName(item.path)), h('code', { class: 'small' }, item.path)),
      h('div', { class: 'grid2' },
        field('Speaker', conversation, 'speaker'),
        field('Opening line(s)', opening, 'lines', { placeholder: 'hello', hint: 'One line id, or several separated by commas: the first whose conditions pass opens.' })),
      h('details', {}, h('summary', { class: 'small muted' }, 'NPC binding and other fields (JSON)'), rest.element)),
    h('div', { class: 'card' }, h('h3', {}, 'Flow'), flow),
    nodes,
    editable ? h('div', { class: 'row' },
      h('button', { class: 'btn', onclick: () => { conversation.nodes.push({ id: `line_${conversation.nodes.length + 1}`, text: '', choices: [] }); drawNodes(); drawFlow(); } }, 'Add line'),
      h('button', { class: 'btn primary', onclick: save }, 'Save to draft')) : h('p', { class: 'muted' }, 'Editing dialogue needs mysticquests.studio.dialogue.'));
}

function newConversation(container, documents) {
  const places = holders(documents, 'conversations');
  const values = { id: '', speaker: '', path: places[0] || 'packages/new_package/conversations.yml' };
  const create = async () => {
    if (!/^[a-z0-9_.-]+$/i.test(values.id)) return toast('Give the conversation an id: letters, digits, _ . -', 'error');
    try {
      await addItem(values.path, 'conversations', { id: values.id, speaker: values.speaker || values.id, start: 'hello',
        nodes: [{ id: 'hello', text: '' }] });
      selected = { path: values.path, id: values.id };
      refresh();
    } catch (error) {
      fail(error);
    }
  };
  clear(container, h('div', { class: 'card stack' },
    h('h2', {}, 'New conversation'),
    field('Id', values, 'id', { placeholder: 'elder_intro' }),
    field('Speaker', values, 'speaker', { placeholder: 'Village Elder' }),
    field('File', values, 'path', { hint: 'An existing conversations file, or a new path.' }),
    h('button', { class: 'btn primary', onclick: create }, 'Create')));
}
