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
const escapeHtml = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
let players = [], selected = new Set(), view = 'matchday', division = null, generatedIds = '', busy = false, loading = false, historyPage = 0, minimumGames = 5, teamSize = 5, votingConfigured = false, adminConfigured = false, removeTarget = null, toastTimer;
try { const ids = JSON.parse(localStorage.getItem('football-attendance') || '[]'); if (Array.isArray(ids)) selected = new Set(ids.map(String)); } catch {}
function remember() { try { localStorage.setItem('football-attendance', JSON.stringify([...selected])); } catch {} }
function toast(message) { $('toast').textContent = message; $('toast').hidden = false; clearTimeout(toastTimer); toastTimer = setTimeout(() => $('toast').hidden = true, 5000); }
function connection(message, error = false) { $('connection').textContent = message; document.querySelector('.status-line').classList.toggle('error', error); }
async function api(path, body) {
  const response = await fetch(footballUrl(path), {method: body === undefined ? 'GET' : 'POST', credentials: 'same-origin', headers: body === undefined ? {} : {'Content-Type':'application/json'}, body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(path.includes("rating-polls") || path.endsWith("/vote") ? 45000 : 20000)});
  const data = await response.json();
  if (response.status === 401 && !$('login-dialog').open) $('login-dialog').showModal();
  if (!response.ok) { const error = new Error(data.error || 'The server could not complete the request.'); error.status = response.status; throw error; }
  return data;
}
function filtered(search, sort, qualified = false) {
  const query = search.trim().toLocaleLowerCase();
  return players.filter(p => p.name.toLocaleLowerCase().includes(query) && (!qualified || p.games >= minimumGames)).sort((a,b) => sort === 'name' ? a.name.localeCompare(b.name) : (b[sort] ?? -1) - (a[sort] ?? -1) || a.name.localeCompare(b.name));
}
function renderRoster() {
  const focusedPlayer = document.activeElement?.dataset?.player;
  const list = filtered($('search').value, $('sort').value);
  $('roster-count').textContent = players.length;
  $('player-grid').innerHTML = list.length ? list.map(p => `<button class="player-card ${selected.has(p.id) ? 'selected' : ''}" data-player="${p.id}" ${p.ratingReady === false ? 'disabled' : ''} aria-pressed="${selected.has(p.id)}" aria-label="${escapeHtml(p.name)}, rating ${p.rating}, ${selected.has(p.id) ? 'playing' : 'not selected'}"><span class="selection-check" aria-hidden="true">✓</span><span class="avatar" aria-hidden="true">${escapeHtml(p.name.slice(0,2).toUpperCase())}</span><span class="player-name">${escapeHtml(p.name)}</span><span class="player-meta"><span class="rating">${p.ratingReady === false ? 'Awaiting votes' : p.rating.toFixed(1)+' <span>RATING</span>'}</span><span>${p.games} ${p.games === 1 ? 'game' : 'games'}</span></span></button>`).join('') : '<p class="empty">No players found.</p>';
  renderSelection();
  if (focusedPlayer) $('player-grid').querySelector(`[data-player="${focusedPlayer}"]`)?.focus({preventScroll:true});
}
function renderSelection() {
  const count = selected.size;
  $('selected-count').textContent = count;
  $('progress-bar').style.width = `${Math.min(count / 10 * 100, 100)}%`;
  $('selection-hint').textContent = count < 10 ? `Choose ${10-count} more ${10-count === 1 ? 'player' : 'players'} for 5v5.` : count === 10 ? '10 players selected.' : `${count} confirmed · 10 will play · ${count-10} ${count-10 === 1 ? 'substitute' : 'substitutes'}.`;
  $('selected-chips').innerHTML = players.filter(p => selected.has(p.id)).map(p => `<button class="chip" data-remove="${p.id}" aria-label="Remove ${escapeHtml(p.name)} from attendance">${escapeHtml(p.name)} <span aria-hidden="true">×</span></button>`).join('');
  $('generate').disabled = count < 10 || busy || loading;
  $('generate').innerHTML = busy ? 'Making teams…' : 'Make the teams <span>↗</span>';
  if (division) renderDivisionLabel();
}
function togglePlayer(id) { if (busy || players.find(p => p.id === id)?.ratingReady === false) return; selected.has(id) ? selected.delete(id) : selected.add(id); remember(); renderRoster(); }
function selectionSignature() { return [...selected].sort().join(','); }
function renderDivisionLabel() {
  $('division-label').textContent = `Saved division #${division.id} · ${division.random ? 'Random split' : `Rating difference: ${Math.abs(division.sumA-division.sumB).toFixed(1)}`} ${generatedIds !== selectionSignature() ? '· Attendance has changed. Generate again for this selection.' : ''}`;
}
function renderTeams() {
  $('log-generated').disabled = false;
  $('results').hidden = false;
  renderDivisionLabel();
  $('team-cards').innerHTML = ['A','B'].map(team => `<article class="team-card"><div class="team-head"><h3>Team ${team}</h3><span>${division[`sum${team}`].toFixed(1)} total rating</span></div>${division[`team${team}`].map((p,i) => `<div class="team-player"><span class="number">0${i+1}</span><span>${escapeHtml(p.name)}</span><span class="rating">${p.rating.toFixed(1)}</span></div>`).join('')}</article>`).join('');
  $('substitutes').innerHTML = division.substitutes.length ? `<strong>On the bench (${division.substitutes.length})</strong> &nbsp; ${division.substitutes.map(escapeHtml).join(' · ')}` : 'No substitutes.';
}
async function generate(random = false) {
  if (busy || selected.size < 10) return;
  busy = true; renderSelection(); $('remake').disabled = true;
  const signature = selectionSignature();
  try {
    division = await api('/api/teams', {playerIds:[...selected], random}); generatedIds = signature;
    renderTeams(); $('results').scrollIntoView({behavior:matchMedia('(prefers-reduced-motion: reduce)').matches ? 'instant' : 'smooth', block:'start'});
  } catch(e) { toast(e.message); }
  finally { busy = false; renderSelection(); $('remake').disabled = false; }
}
function renderCatalogue() {
  const sort = $('catalogue-sort').value;
  const list = filtered($('catalogue-search').value, sort, sort === 'winRate');
  $('qualification').textContent = `Win rate = wins ÷ games played. Win-rate rankings require ${minimumGames} games. Team goals contribute to each player's goal difference.`;
  $('catalogue').innerHTML = list.map(p => `<tr><td>${escapeHtml(p.name)}</td><td class="rating">${p.ratingReady === false ? 'Awaiting votes' : p.rating.toFixed(1)}</td><td>${p.games}</td><td>${p.wins} / ${p.draws} / ${p.losses}</td><td>${p.winRate == null ? '—' : p.winRate.toFixed(1)+'%'}</td><td>${p.goalDifference > 0 ? '+' : ''}${p.goalDifference}</td><td>${p.points}</td><td><button type="button" class="text-button remove-player" data-delete-player="${p.id}" aria-label="Remove ${escapeHtml(p.name)}" ${!adminConfigured ? 'disabled' : ''}>Remove</button></td></tr>`).join('');
  $('player-admin-note').hidden = adminConfigured;
  $('catalogue-empty').hidden = list.length > 0;
}
async function refreshHistory() {
  const requestedPage = historyPage;
  try {
    const data = await api(`/api/history?page=${requestedPage}`);
    if (requestedPage !== historyPage) return;
    $('history-list').innerHTML = data.matches.length ? data.matches.map(m => `<article class="history-card"><div class="history-top"><span>MATCH #${m.id}</span><time datetime="${escapeHtml(m.playedAt)}">${escapeHtml(new Date(m.playedAt).toLocaleString(undefined,{dateStyle:'medium', timeStyle:'short'}))}</time></div><div class="history-score"><span>TEAM A</span><strong>${m.goalsA} – ${m.goalsB}</strong><span>TEAM B</span></div><div class="history-teams"><div>${m.teamA.map(escapeHtml).join(' · ')}</div><div>${m.teamB.map(escapeHtml).join(' · ')}</div></div></article>`).join('') : '<p class="empty">No games logged yet. Use Log a game to enter the teams and score.</p>';
    $('history-page').textContent = `Page ${historyPage+1}`; $('previous').disabled = historyPage === 0; $('next').disabled = !data.hasMore;
  } catch(e) { connection('Could not load match history. Use Refresh to retry.', true); toast(e.message); }
}
async function refresh(manual = false) {
  if (loading || $('login-dialog').open) return;
  loading = true; $('refresh').disabled = true;
  try {
    const data = await api('/api/players'); players = data.players; minimumGames = data.minimumGames; teamSize = data.teamSize; votingConfigured = data.votingConfigured; adminConfigured = data.adminConfigured;
    const available = new Set(players.filter(p => p.ratingReady !== false).map(p => p.id)); selected = new Set([...selected].filter(id => available.has(id))); remember();
    renderRoster(); renderCatalogue(); renderPolls();
    connection(`Updated · ${new Date().toLocaleTimeString([], {hour:'2-digit',minute:'2-digit'})} · refreshes every 15s`);
    if (view === 'history') await refreshHistory();
    if (manual) toast('Updated.');
  } catch(e) { connection('Connection interrupted · your selection is kept. Refresh to retry.', true); if (manual) toast(e.message); }
  finally { loading = false; $('refresh').disabled = false; renderSelection(); }
}
function setView(next) {
  view = next;
  document.querySelectorAll('.view').forEach(el => el.hidden = el.id !== `view-${view}`);
  document.querySelectorAll('[data-view]').forEach(el => { el.classList.toggle('active', el.dataset.view === view); if (el.dataset.view === view) el.setAttribute('aria-current','page'); else el.removeAttribute('aria-current'); });
  const content = {matchday:['Make teams', "Select who's playing."], players:['Players', 'Ratings and stats from the games you log.'], history:['Games', 'Log the teams and final scores from your games.']}[view];
  $('page-title').textContent = content[0]; $('page-subtitle').textContent = content[1];
  if (view === 'history') refreshHistory();
  if (view === 'players') renderCatalogue();
}
let addingPlayer = false;
function updateAddMode() {
  const voting = $('new-rating-mode').value === 'vote';
  $('new-score-field').hidden = voting;
  $('new-score').disabled = voting;
  $('new-score').required = !voting;
  $('new-vote-note').hidden = !voting;
  $('forms-setup-note').hidden = adminConfigured && (!voting || votingConfigured);
  $('forms-setup-note').textContent = !adminConfigured ? 'Set FOOTBALL_ADMIN_PASSWORD on the server to enable player management.' : 'Voting is ready.';
  $('add-submit').disabled = addingPlayer || !adminConfigured || (voting && !votingConfigured);
  $('add-submit').textContent = addingPlayer ? 'Adding player…' : voting ? 'Start voting' : 'Add player';
  $('new-rating-mode').disabled = addingPlayer;
}
function openAdd() {
  $('add-form').reset(); $('add-error').textContent = ''; $('add-password').value = '';
  updateAddMode();
  $('add-dialog').showModal(); $('new-name').focus();
}
$('new-rating-mode').addEventListener('change', updateAddMode);
$('player-grid').addEventListener('click', e => { const card = e.target.closest('[data-player]'); if (card) togglePlayer(card.dataset.player); });
$('selected-chips').addEventListener('click', e => { const chip = e.target.closest('[data-remove]'); if (chip) togglePlayer(chip.dataset.remove); });
$('search').addEventListener('input', renderRoster); $('sort').addEventListener('change', renderRoster);
$('catalogue-search').addEventListener('input', renderCatalogue); $('catalogue-sort').addEventListener('change', renderCatalogue);
$('select-all').onclick = () => { if (!busy) { selected = new Set(players.filter(p => p.ratingReady !== false).map(p => p.id)); remember(); renderRoster(); } };
$('clear').onclick = () => { if (!busy) { selected.clear(); remember(); renderRoster(); } };
$('generate').onclick = () => generate(); $('remake').onclick = () => generate(true);
$('refresh').onclick = () => refresh(true);
document.querySelectorAll('[data-view]').forEach(el => el.onclick = () => setView(el.dataset.view));
$('add-open').onclick = openAdd; $('catalogue-add').onclick = openAdd; $('add-close').onclick = () => $('add-dialog').close();
$('add-form').onsubmit = async e => {
  e.preventDefault(); if (addingPlayer) return;
  const voting = $('new-rating-mode').value === 'vote';
  addingPlayer = true; updateAddMode(); $('add-error').textContent = '';
  try {
    const payload = {name:$('new-name').value.trim(), password:$('add-password').value};
    if (!voting) {
      payload.rating = Number($('new-score').value);
      if ($('new-score').value.trim() === '' || !Number.isFinite(payload.rating) || payload.rating < 0 || payload.rating > 100) throw new Error('Enter a score between 0 and 100.');
    }
    const data = await api(voting ? '/api/players/vote' : '/api/players', payload);
    $('add-dialog').close(); $('add-form').reset(); await refresh();
    if (!voting) toast('Player added with the score you entered.');
    else if (data.poll.url) showRatingForm(data.poll);
    else toast('Player added.');
  } catch(e) { $('add-error').textContent = e.message; }
  finally { addingPlayer = false; $('add-password').value = ''; updateAddMode(); }
};
$('add-dialog').addEventListener('close', () => { $('add-password').value = ''; });
$('catalogue').addEventListener('click', e => {
  const button = e.target.closest('[data-delete-player]'); if (!button || button.disabled) return;
  removeTarget = players.find(p => p.id === button.dataset.deletePlayer); if (!removeTarget) return;
  $('remove-title').textContent = `Remove ${removeTarget.name}?`;
  $('remove-password').value = ''; $('remove-error').textContent = ''; $('remove-submit').disabled = false;
  $('remove-dialog').showModal(); $('remove-password').focus();
});
$('remove-close').onclick = () => $('remove-dialog').close();
$('remove-dialog').addEventListener('close', () => { $('remove-password').value = ''; });
$('remove-form').onsubmit = async e => {
  e.preventDefault(); if (!removeTarget) return;
  $('remove-submit').disabled = true; $('remove-error').textContent = '';
  try {
    await api('/api/players/remove',{id:removeTarget.id,password:$('remove-password').value});
    selected.delete(removeTarget.id); remember();
    if (division && [...division.teamA,...division.teamB].some(p => p.id === removeTarget.id)) {
      division = null; $('results').hidden = true;
    }
    $('remove-dialog').close(); await refresh(); toast(`${removeTarget.name} removed. Past games are kept.`);
  } catch(e) { $('remove-error').textContent = e.message; }
  finally { $('remove-password').value = ''; $('remove-submit').disabled = false; }
};
$('login-dialog').addEventListener('cancel', e => e.preventDefault());
$('login-form').onsubmit = async e => {
  e.preventDefault(); const button = e.submitter; button.disabled = true; $('login-error').textContent = '';
  try {
    await api('/api/login', {key:$('access-key').value});
    try { await api('/api/players'); }
    catch(e) { if (e.status === 401) throw new Error('The access key was accepted, but the sign-in cookie was not returned. Use HTTPS, allow cookies, and check the server cookie_path setting.'); throw e; }
    $('access-key').value = ''; $('login-dialog').close(); await refresh();
  }
  catch(e) { $('login-error').textContent = e.message; }
  finally { button.disabled = false; }
};
$('previous').onclick = () => { if (historyPage > 0) { historyPage--; refreshHistory(); } };
$('next').onclick = () => { historyPage++; refreshHistory(); };
$('copy').onclick = async () => {
  if (!division) return;
  const text = `Futebol · Division #${division.id}\n\nTeam A (${division.sumA.toFixed(1)})\n${division.teamA.map(p => `${p.name} (${p.rating.toFixed(1)})`).join('\n')}\n\nTeam B (${division.sumB.toFixed(1)})\n${division.teamB.map(p => `${p.name} (${p.rating.toFixed(1)})`).join('\n')}${division.substitutes.length ? '\n\nSubstitutes: '+division.substitutes.join(', ') : ''}\n\nRating difference: ${Math.abs(division.sumA-division.sumB).toFixed(1)}`;
  try {
    if (navigator.clipboard && window.isSecureContext) await navigator.clipboard.writeText(text);
    else { const field = document.createElement('textarea'); field.value = text; document.body.appendChild(field); field.select(); const ok = document.execCommand('copy'); field.remove(); if (!ok) throw new Error('copy unavailable'); }
    toast('Teams copied.');
  } catch { toast('Copy is unavailable in this browser. Select and copy the teams on screen.'); }
};
document.addEventListener('visibilitychange', () => { if (!document.hidden) refresh(); });
setInterval(() => { if (!document.hidden && !$('add-dialog').open && !$('remove-dialog').open && !$('game-dialog').open && !$('rating-dialog').open && !$('finish-vote-dialog').open) refresh(); }, 15000);
refresh();
let gameDivisions = [], manualTeams = new Map(), resultDraft = null, savingGame = false;
function localDateValue(date = new Date()) { return new Date(date.getTime() - date.getTimezoneOffset()*60000).toISOString().slice(0,16); }
function renderManualTeams() {
  const count = team => [...manualTeams.values()].filter(value => value === team).length;
  $('manual-hint').textContent = `Select ${teamSize} per team. Team A: ${count('A')}/${teamSize} · Team B: ${count('B')}/${teamSize}`;
  const query = $('game-search').value.toLocaleLowerCase().trim();
  $('game-player-list').innerHTML = [...players].sort((a,b) => a.name.localeCompare(b.name)).filter(p => p.name.toLocaleLowerCase().includes(query)).map(p => `<div class="game-player-row"><span>${escapeHtml(p.name)}</span><div>${['A','B'].map(team => `<button type="button" class="assign-team ${manualTeams.get(p.id) === team ? 'assigned' : ''}" data-assign="${p.id}" data-team="${team}" aria-pressed="${manualTeams.get(p.id) === team}" aria-label="${escapeHtml(p.name)}, Team ${team}">${team}</button>`).join('')}</div></div>`).join('') || '<p class="empty">No players found.</p>';
}
function renderGameSource() {
  const source = $('game-source').value;
  $('manual-teams').hidden = source !== 'manual'; $('saved-teams').hidden = source === 'manual';
  if (source === 'manual') { renderManualTeams(); return; }
  const saved = gameDivisions.find(d => d.id === source);
  $('saved-teams').innerHTML = saved ? ['A','B'].map(team => `<p><strong>Team ${team}</strong><br>${saved[`team${team}`].map(p => escapeHtml(p.name)).join(' · ')}</p>`).join('') : '';
}
async function openGame(divisionId = null) {
  $('game-form').reset(); manualTeams.clear(); resultDraft = null; savingGame = false;
  $('game-error').textContent = ''; $('game-review').hidden = true; $('game-inputs').hidden = false; $('game-back').hidden = true;
  $('game-submit').textContent = 'Review game'; $('game-submit').disabled = true;
  $('game-date').value = localDateValue(); $('game-date').max = localDateValue();
  $('game-source').innerHTML = '<option value="manual">Choose the players manually</option>';
  renderManualTeams(); $('manual-teams').hidden = false; $('saved-teams').hidden = true;
  $('game-dialog').showModal();
  try {
    const data = await api('/api/divisions'); gameDivisions = data.divisions;
    $('game-source').innerHTML += gameDivisions.map(d => `<option value="${d.id}">Saved teams #${d.id} · ${escapeHtml(new Date(d.createdAt).toLocaleDateString())}</option>`).join('');
    if (divisionId && gameDivisions.some(d => d.id === divisionId)) $('game-source').value = divisionId;
    else if (divisionId) $('game-error').textContent = 'This lineup already has a result. Choose another lineup or enter different teams.';
    renderGameSource();
  } catch(e) { $('game-error').textContent = e.message; }
  finally { $('game-submit').disabled = false; }
}
$('log-open').onclick = () => openGame();
$('log-generated').onclick = () => { if (division) openGame(division.id); };
$('game-close').onclick = () => { if (!savingGame) $('game-dialog').close(); };
$('game-dialog').addEventListener('cancel', e => { if (savingGame) e.preventDefault(); });
$('game-search').addEventListener('input', renderManualTeams);
$('game-source').addEventListener('change', renderGameSource);
$('game-player-list').addEventListener('click', e => {
  const button = e.target.closest('[data-assign]'); if (!button) return;
  const id = button.dataset.assign, team = button.dataset.team;
  if (manualTeams.get(id) === team) manualTeams.delete(id);
  else {
    if ([...manualTeams.values()].filter(value => value === team).length >= teamSize) { toast(`Team ${team} already has ${teamSize} players.`); return; }
    manualTeams.set(id, team);
  }
  renderManualTeams();
  $('game-player-list').querySelector(`[data-assign="${id}"][data-team="${team}"]`)?.focus({preventScroll:true});
});
$('game-back').onclick = () => {
  resultDraft = null; $('game-review').hidden = true; $('game-inputs').hidden = false; $('game-back').hidden = true; $('game-submit').textContent = 'Review game'; $('game-error').textContent = '';
};
$('game-form').onsubmit = async e => {
  e.preventDefault(); if (savingGame) return;
  $('game-error').textContent = '';
  savingGame = true; $('game-submit').disabled = true; $('game-back').disabled = true;
  try {
    if (!resultDraft) {
      const source = $('game-source').value;
      const payload = {goalsA:$('goals-a').value, goalsB:$('goals-b').value, playedAt:new Date($('game-date').value).toISOString()};
      if (source === 'manual') {
        payload.teamA = [...manualTeams].filter(([,t]) => t === 'A').map(([id]) => id);
        payload.teamB = [...manualTeams].filter(([,t]) => t === 'B').map(([id]) => id);
        if (payload.teamA.length !== teamSize || payload.teamB.length !== teamSize) throw new Error(`Select exactly ${teamSize} players for each team.`);
      } else payload.divisionId = source;
      resultDraft = await api('/api/results/review', payload);
      $('game-inputs').hidden = true; $('game-review').hidden = false; $('game-back').hidden = false;
      $('game-review').innerHTML = `<p class="review-score">${resultDraft.goalsA} – ${resultDraft.goalsB}</p><p class="muted">${escapeHtml(new Date(resultDraft.playedAt).toLocaleString())}</p>${['A','B'].map(team => `<p><strong>Team ${team}</strong><br>${resultDraft[`team${team}`].map(p => escapeHtml(p.name)).join(' · ')}</p>`).join('')}<p class="roster-note">Confirm to add this game to everyone's stats.</p>`;
      $('game-submit').textContent = 'Confirm and save';
    } else {
      const saved = await api('/api/results/confirm',{draftId:resultDraft.draftId,revision:resultDraft.revision});
      if (division && $('game-source').value === division.id) $('log-generated').disabled = true;
      $('game-dialog').close(); historyPage = 0; setView('history'); await refresh(); toast(`Game #${saved.id} saved.`);
    }
  } catch(e) { $('game-error').textContent = e.message; }
  finally { savingGame = false; $('game-submit').disabled = false; $('game-back').disabled = false; }
};
function renderPolls() {
  const polls = players.filter(p => p.ratingPoll).map(p => p.ratingPoll);
  $('rating-sync-note').textContent = 'Share a voting link. Friends open the link and enter a score. They can update their vote until you finish voting.';
  $('rating-polls').innerHTML = polls.length ? polls.map(p => `<article class="rating-poll"><div><strong>${escapeHtml(p.name)}</strong><p>${p.count} ${p.count === 1 ? 'vote' : 'votes'} · ${p.average == null ? 'Awaiting votes' : 'Average '+p.average.toFixed(1)} · ${p.status === 'CLOSED' ? 'Final rating' : p.native ? 'Voting open' : 'Older Google poll — last saved score'}</p></div><div class="poll-actions">${p.native ? `<button class="button secondary" data-poll-share="${p.id}">Voting link</button>` : ''}${p.status !== 'CLOSED' ? `<button class="button secondary" data-poll-action="close" data-poll-id="${p.id}" ${!adminConfigured || p.count === 0 ? 'disabled' : ''}>${p.native ? 'Finish voting' : 'Use saved average'}</button>` : ''}</div></article>`).join('') : '<p class="roster-note">No rating votes yet.</p>';
}
function showRatingForm(poll) {
  $('rating-dialog-title').textContent = `Rate ${poll.name}`;
  $('rating-dialog-note').textContent = `${poll.count} ${poll.count === 1 ? 'vote' : 'votes'}. ${poll.average == null ? 'Waiting for the first vote.' : 'Average rating: '+poll.average.toFixed(1)+'.'}`;
  $('rating-form-link').href = poll.url; $('rating-form-link').textContent = 'Open voting page ↗';
  $('rating-copy').dataset.url = new URL(poll.url, location.href).href;
  $('rating-dialog').showModal();
}
$('rating-close').onclick = () => $('rating-dialog').close();
$('rating-copy').onclick = async () => {
  const url = $('rating-copy').dataset.url;
  try {
    if (navigator.clipboard && window.isSecureContext) await navigator.clipboard.writeText(url);
    else { const field = document.createElement('textarea'); field.value = url; document.body.appendChild(field); field.select(); const ok = document.execCommand('copy'); field.remove(); if (!ok) throw new Error('Copy unavailable'); }
    toast('Voting link copied.');
  } catch { toast('Open the voting page and copy its URL to share it.'); }
};
$('rating-polls').addEventListener('click', async e => {
  const share = e.target.closest('[data-poll-share]');
  if (share) { const poll = players.find(p => p.ratingPoll?.id === share.dataset.pollShare)?.ratingPoll; if (poll?.url) showRatingForm(poll); return; }
  const button = e.target.closest('[data-poll-action]'); if (!button || button.disabled) return;
  $('finish-vote-form').dataset.poll = button.dataset.pollId;
  const native = players.find(p => p.ratingPoll?.id === button.dataset.pollId)?.ratingPoll.native;
  $('finish-vote-form').dataset.native = String(native);
  $('finish-vote-note').textContent = native ? 'The final average and vote count will be kept. Individual votes will be deleted and voting cannot reopen.' : 'Keep the last average already saved in this database. New Google responses will not be imported. The old Google Form must be deleted from Drive manually.';
  $('finish-vote-password').value = ''; $('finish-vote-error').textContent = '';
  $('finish-vote-dialog').showModal();
});
$('finish-vote-cancel').onclick = () => $('finish-vote-dialog').close();
$('finish-vote-dialog').addEventListener('close', () => { $('finish-vote-password').value = ''; });
$('finish-vote-form').onsubmit = async e => {
  e.preventDefault(); if ($('finish-vote-submit').disabled) return;
  $('finish-vote-submit').disabled = true; $('finish-vote-error').textContent = '';
  const password = $('finish-vote-password').value; $('finish-vote-password').value = '';
  try {
    await api('/api/rating-polls/close', {id: $('finish-vote-form').dataset.poll, password});
    $('finish-vote-dialog').close(); await refresh(); toast($('finish-vote-form').dataset.native === 'true' ? 'Final rating saved. Individual votes deleted.' : 'Saved average kept as the final rating.');
  } catch(e) { $('finish-vote-error').textContent = e.message; }
  finally { $('finish-vote-submit').disabled = false; }
};
