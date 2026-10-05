// Small DOM helpers. Text always goes through textContent, never innerHTML, so quest content can
// never inject markup into the Studio.

export function h(tag, attrs = {}, ...children) {
  const element = document.createElement(tag);
  for (const [key, value] of Object.entries(attrs || {})) {
    if (value === undefined || value === null || value === false) continue;
    if (key === 'class') element.className = value;
    else if (key.startsWith('on') && typeof value === 'function') element.addEventListener(key.slice(2), value);
    else if (key === 'value') element.value = value;
    else if (key === 'checked') element.checked = !!value;
    else element.setAttribute(key, value === true ? '' : String(value));
  }
  append(element, children);
  return element;
}

function append(element, children) {
  for (const child of children.flat(Infinity)) {
    if (child === undefined || child === null || child === false) continue;
    element.append(child instanceof Node ? child : document.createTextNode(String(child)));
  }
}

export function clear(element, ...children) {
  element.replaceChildren();
  append(element, children);
  return element;
}

let toastTimer;
export function toast(message, kind = '') {
  document.querySelector('.toast')?.remove();
  const box = h('div', { class: `toast ${kind}`, role: 'status' }, message);
  document.body.append(box);
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => box.remove(), kind === 'error' ? 9000 : 4000);
}

/** A labelled input bound to obj[key]. */
export function field(label, obj, key, { type = 'text', readonly = false, placeholder = '', options = null, hint = '' } = {}) {
  let input;
  if (options) {
    input = h('select', { onchange: () => { set(obj, key, input.value); } },
      options.map(option => h('option', { value: option }, option || '—')));
    input.value = obj[key] ?? '';
  } else if (type === 'checkbox') {
    input = h('input', { type: 'checkbox', checked: !!obj[key], onchange: () => { set(obj, key, input.checked || undefined); } });
  } else if (type === 'textarea') {
    input = h('textarea', { placeholder, oninput: () => set(obj, key, input.value) });
    input.value = obj[key] ?? '';
  } else {
    input = h('input', { type, value: obj[key] ?? '', readonly, placeholder,
      oninput: () => set(obj, key, type === 'number' ? (input.value === '' ? undefined : Number(input.value)) : input.value) });
  }
  return h('label', { class: 'field' }, h('span', {}, label), input, hint ? h('small', { class: 'muted' }, hint) : null);
}

function set(obj, key, value) {
  if (value === undefined || value === '') delete obj[key];
  else obj[key] = value;
}

/** A JSON editor for an object; returns { element, read() } where read() throws on invalid JSON. */
export function jsonEditor(value, rows = 10) {
  const area = h('textarea', { rows, spellcheck: 'false' });
  area.value = JSON.stringify(value ?? {}, null, 2);
  return {
    element: area,
    read() {
      try {
        return JSON.parse(area.value);
      } catch (error) {
        throw new Error(`Advanced JSON is not valid: ${error.message}`);
      }
    },
  };
}

export function badge(text, kind = '') {
  return h('span', { class: `badge ${kind}` }, text);
}

export function when(timestamp) {
  try {
    return new Date(timestamp).toLocaleString();
  } catch {
    return timestamp;
  }
}
