// Finds content items (quests, conversations, schemas, puzzles...) inside the draft's files, in all
// three shapes the loader accepts, and writes an edited item back into its own file.
//
//   quests: [ { id: wolves, ... } ]      a list with ids
//   quests: { wolves: { ... } }          a map keyed by id
//   quests.yml holding a bare list       the file is named after the section

import { api } from './api.js';

const stem = path => path.slice(path.lastIndexOf('/') + 1).replace(/\.(ya?ml|json)$/i, '');
export const folder = path => path.slice(0, path.lastIndexOf('/'));
export const packageName = path => folder(path).replace(/^packages\//, '').replace(/^templates\//, 'template: ');

/** Every item of a section across the draft: { path, version, section, shape, key, id, value, hasComments }. */
export function collect(documents, section) {
  const items = [];
  for (const doc of documents) {
    if (!doc.document) continue;
    const root = doc.document;
    let holder = null;
    let shape = null;
    if (Array.isArray(root) && stem(doc.path) === section) {
      holder = root;
      shape = 'root';
    } else if (root && typeof root === 'object' && !Array.isArray(root) && root[section] != null) {
      holder = root[section];
      shape = Array.isArray(holder) ? 'array' : 'map';
    }
    if (!holder) continue;
    if (Array.isArray(holder)) {
      holder.forEach((value, index) => {
        if (value && typeof value === 'object') {
          items.push({ path: doc.path, version: doc.version, section, shape, key: index, id: value.id ?? `#${index + 1}`, value, hasComments: doc.hasComments });
        }
      });
    } else if (typeof holder === 'object') {
      for (const [key, value] of Object.entries(holder)) {
        if (value && typeof value === 'object' && !Array.isArray(value)) {
          // A map entry's id is its key; it is shown on the item and stripped again when saved.
          items.push({ path: doc.path, version: doc.version, section, shape, key, id: key, value: { id: key, ...value }, hasComments: doc.hasComments });
        }
      }
    }
  }
  return items;
}

/** Files that already hold a section, as places to add a new item. */
export function holders(documents, section) {
  return [...new Set(collect(documents, section).map(item => item.path))]
    .concat(documents.filter(doc => doc.document && !Array.isArray(doc.document) && Array.isArray(doc.document[section])
      && doc.document[section].length === 0).map(doc => doc.path));
}

function confirmComments(path, hasComments) {
  return !hasComments || window.confirm(
    `${path} has YAML comments. Saving through this form rewrites the file and drops them.\n\n`
    + 'Use Files to edit it as text and keep them. Save anyway?');
}

/** Writes item.value (edited) back into its file. Returns false when the user cancelled. */
export async function saveItem(item, value) {
  if (!confirmComments(item.path, item.hasComments)) return false;
  const doc = (await api.documents(true)).find(candidate => candidate.path === item.path);
  if (!doc || !doc.document) throw new Error(`${item.path} is gone or no longer parses; reload.`);
  // Same version means the file is byte-for-byte what the item was read from, so its position holds.
  if (doc.version !== item.version) throw new Error(`${item.path} was changed by someone else; reload and edit again.`);
  const document = structuredClone(doc.document);
  const holder = item.shape === 'root' ? document : document[item.section];
  if (item.shape === 'map') {
    const copy = { ...value };
    if (copy.id === item.key) delete copy.id;
    holder[item.key] = copy;
  } else {
    holder[item.key] = value;
  }
  await api.saveDocument(item.path, document, doc.version);
  return true;
}

/** Removes an item from its file. */
export async function deleteItem(item) {
  if (!confirmComments(item.path, item.hasComments)) return false;
  const doc = (await api.documents(true)).find(candidate => candidate.path === item.path);
  if (!doc || doc.version !== item.version) throw new Error(`${item.path} was changed by someone else; reload.`);
  const document = structuredClone(doc.document);
  const holder = item.shape === 'root' ? document : document[item.section];
  if (Array.isArray(holder)) holder.splice(item.key, 1);
  else delete holder[item.key];
  await api.saveDocument(item.path, document, doc.version);
  return true;
}

/**
 * Adds a new item to a file's section, creating the section (or the file) when needed.
 * `path` must be a draft file path; a new file is created as { [section]: [value] }.
 */
export async function addItem(path, section, value) {
  const documents = await api.documents(true);
  const doc = documents.find(candidate => candidate.path === path);
  if (!doc) {
    await api.saveDocument(path, { [section]: [value] }, null);
    return;
  }
  if (!doc.document) throw new Error(`${path} does not parse; fix it in Files first.`);
  if (!confirmComments(path, doc.hasComments)) return;
  const document = structuredClone(doc.document);
  if (Array.isArray(document)) {
    document.push(value);
  } else if (Array.isArray(document[section])) {
    document[section].push(value);
  } else if (document[section] && typeof document[section] === 'object') {
    const { id, ...rest } = value;
    document[section][id] = rest;
  } else {
    document[section] = [value];
  }
  await api.saveDocument(path, document, doc.version);
}
