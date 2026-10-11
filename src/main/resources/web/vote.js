'use strict';
function footballUrl(path) {
  const backend = document.querySelector('meta[name="football-api"]');
  if (!backend) return path;
  const url = new URL(backend.content, document.baseURI);
  const [route, query] = path.split('?', 2);
  url.searchParams.set('route', route);
  for (const [key, value] of new URLSearchParams(query || '')) url.searchParams.set(key, value);
  return url.href;
}
const $ = id => document.getElementById(id);
const pollId = new URLSearchParams(location.search).get('id');
let saving = false, initialized = false;
async function request(path, body) {
  const response = await fetch(footballUrl(path), {method: body === undefined ? 'GET' : 'POST', credentials: 'same-origin', headers: body === undefined ? {} : {'Content-Type':'application/json'}, body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(20000)});
  const data = await response.json();
  if (!response.ok) throw new Error(data.error || 'Could not load voting.');
  return data;
}
async function load() {
  if (!pollId) { $('vote-error').textContent = 'Open the voting link shared by your friends.'; $('vote-summary').textContent = ''; return; }
  try {
    const data = await request('/api/voting?id='+encodeURIComponent(pollId));
    const open = data.status === 'OPEN';
    $('vote-title').textContent = 'Rate '+data.name;
    $('vote-summary').textContent = `${data.count} ${data.count === 1 ? 'vote' : 'votes'} · ${data.average == null ? 'Awaiting votes' : 'Average '+data.average.toFixed(1)}${open ? '' : ' · Final rating'}`;
    $('vote-form').hidden = !open;
    if (!initialized && data.ownScore != null) $('vote-score').value = data.ownScore;
    initialized = true;
    if (!open) $('vote-message').textContent = 'Voting has finished. The final rating is saved and individual votes have been deleted.';
  } catch(e) { $('vote-error').textContent = e.message; }
}
$('vote-form').onsubmit = async e => {
  e.preventDefault(); if (saving) return;
  saving = true; $('vote-submit').disabled = true; $('vote-error').textContent = ''; $('vote-message').textContent = '';
  try {
    const score = Number($('vote-score').value);
    if ($('vote-score').value.trim() === '' || !Number.isFinite(score) || score < 0 || score > 100) throw new Error('Enter a score between 0 and 100.');
    await request('/api/voting?id='+encodeURIComponent(pollId), {score});
    $('vote-message').textContent = 'Your vote is saved. Saving again updates the same vote.'; await load();
  } catch(e) { $('vote-error').textContent = e.message; }
  finally { saving = false; $('vote-submit').disabled = false; }
};
load();
setInterval(() => { if (!document.hidden && !saving) load(); }, 15000);
