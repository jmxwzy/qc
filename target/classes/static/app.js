'use strict';

const $ = id => document.getElementById(id);
const paths = ['root', 'ledger', 'rules', 'template'];
const drafts = new Map();
let batches = [], batch, selected = 0, tab = 'issues', navigation = 0;
let creating = false, saving = false, loading = false;

function esc(value = '') {
  return String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;', '<':'&lt;', '>':'&gt;', '"':'&quot;', "'":'&#39;'}[c]));
}
function message(text = '', error = false) {
  $('notice').textContent = text;
  $('notice').hidden = !text;
  $('notice').classList.toggle('error', error);
  if (error) $('notice').scrollIntoView({block:'nearest'});
}
async function request(url, method = 'GET', body, binary = false) {
  const headers = {};
  if (method !== 'GET') {
    headers['X-QC-Request'] = 'local';
    headers['Content-Type'] = 'application/json';
  }
  const response = await fetch(url, {method, headers, body:body === undefined ? undefined : JSON.stringify(body), cache:'no-store'});
  if (!response.ok) {
    let detail = await response.text();
    try { const parsed = JSON.parse(detail); detail = parsed.message || parsed.error || detail; } catch (_) {}
    const error = new Error(detail || '操作失败，请重新尝试。');
    error.status = response.status;
    throw error;
  }
  if (binary) return response.blob();
  const raw = await response.text();
  return raw ? JSON.parse(raw) : null;
}
const urlForBatch = id => '/api/batches/' + encodeURIComponent(id);
const user = () => batch.users[selected];
const userUrl = () => urlForBatch(batch.id) + '/users/' + selected;
const key = () => batch.id + '/' + selected;
function editableReview(record) {
  return {version:record.version || 0, plan:record.plan || '', value:record.value || '',
    unit:record.unit || '', note:record.note || '', decisions:{...record.decisions},
    associations:{...record.associations}};
}
function savedReading(record) {
  if (record.sourceReading) return {...record.sourceReading, comparison:'上次保存时的预案取值'};
  return record.sourceReadingNote ? {reason:record.sourceReadingNote, comparison:'上次复核记录'} : null;
}
function currentDraft() {
  let draft = drafts.get(key());
  if (!draft) {
    const review = user().review || {};
    draft = {review:editableReview(review), dirty:false, extraction:savedReading(review), conflict:false};
    drafts.set(key(), draft);
  }
  return draft;
}
function setDirty() {
  currentDraft().dirty = true;
  paintState();
}
function paintState() {
  if (!batch || !batch.users[selected]) return;
  const draft = currentDraft();
  $('dirty').textContent = draft.conflict ? '其他窗口已更新记录；本页编辑仍保留。'
    : draft.dirty ? '本户有尚未保存的编辑' : '当前记录已同步';
  $('save').textContent = draft.conflict ? '载入最新记录' : saving ? '正在保存…' : '保存本户复核';
  $('save').disabled = saving || loading || (!draft.dirty && !draft.conflict);
}
function isDemo(entry) {
  return Boolean(entry && (entry.demo || batches.find(row => row.id === entry.id)?.demo));
}
function when(value) {
  if (!value) return '';
  const date = new Date(value);
  return isNaN(date) ? String(value) : date.toLocaleString('zh-CN', {hour12:false});
}
function batchName(row) {
  return (isDemo(row) ? '演示' : '本机') + ' · ' + (when(row.created) || row.id) + ' · ' + String(row.id).slice(-6);
}
function renderBatchList() {
  $('batch').innerHTML = batches.length ? batches.map(row => '<option value="'+esc(row.id)+'">'+esc(batchName(row))+'</option>').join('')
    : '<option value="">暂无批次</option>';
}
// Only navigation metadata is kept in the browser; customer details and review text stay in the batch store.
function lastPosition() {
  try { return JSON.parse(window.localStorage.getItem('qc-workbench-position')) || {}; }
  catch (_) { return {}; }
}
function rememberPosition() {
  if (!batch) return;
  try { window.localStorage.setItem('qc-workbench-position', JSON.stringify({batch:batch.id, selected, tab})); }
  catch (_) { /* Private windows may disable storage. */ }
}
async function listBatches() {
  batches = await request('/api/batches');
  renderBatchList();
}
function displayBatch(data) {

  batch = data;
  // Discard clean cached views when loading from disk. Preserve any pending operator edits.
  for (const [k,d] of drafts) if (k.startsWith(data.id + '/') && !d.dirty) drafts.delete(k);
  const position = lastPosition();
  selected = position.batch === data.id && Number.isInteger(position.selected) && data.users[position.selected] ? position.selected : 0;
  tab = position.batch === data.id && ['issues', 'capacity', 'files'].includes(position.tab) ? position.tab : 'issues';
  rememberPosition();
  $('filter').value = '';
  $('batch').value = data.id;
  $('batchInfo').textContent = (isDemo(batch) ? '虚拟样例' : '本机档案') + '　·　' + (when(batch.created) || '已载入批次');
  $('totals').textContent = batch.users.length + ' 户　/　' + batch.users.reduce((n,u)=>n+u.issues.length,0)+' 条巡检提示';
  $('empty').hidden = true;
  $('workspace').hidden = !batch.users.length;
  $('export').hidden = false; $('reports').hidden = false;
  if (!batch.users.length) message('本批次没有可核查的用户。');
  renderUsers(); if (batch.users.length) renderRecord();
}
async function openBatch(id) {
  if (!id) return;
  const ticket = ++navigation;
  loading = true;
  $('workspace').inert = true;
  try {
    const data = await request(urlForBatch(id));
    if (ticket !== navigation) return;
    displayBatch(data);
  } catch (e) {
    if (ticket === navigation) { $('batch').value = batch?.id || ''; message(e.message, true); }
  } finally {
    if (ticket === navigation) {loading = false; $('workspace').inert = false; paintState();}
  }
}
function renderUsers() {
  const filter = $('filter').value.trim().toLowerCase();
  $('count').textContent = batch.users.length + ' 户';
  $('users').innerHTML = batch.users.map((u,i) => {
    if (!u.name.toLowerCase().includes(filter)) return '';
    const reviewed = Object.values(u.review?.decisions || {}).filter(v => String(v).trim()).length;
    return '<button type="button" aria-current="'+(i === selected ? 'true':'false')+'" data-user="'+i+'" class="userbtn '+(i === selected?'chosen':'')+'">'+esc(u.name)+'<span>'+u.issues.length+' 条提示 · '+reviewed+' 条已记录</span></button>';
  }).join('') || '<p>没有匹配的用户</p>';
}
function renderRecord() {
  const u = user();
  $('userName').textContent = u.name;
  const root = batch.root || '';
  const separator = root.includes('\\') ? '\\' : '/';
  const folder = root ? root.replace(/[\\/]$/, '') + separator + u.directory : u.directory;
  $('directory').textContent = u.directory ? '档案目录　' + folder : '台账用户 · 未匹配档案目录';
  for (const button of document.querySelectorAll('[data-tab]')) {
    button.classList.toggle('active', button.dataset.tab === tab);
    button.setAttribute('aria-pressed', String(button.dataset.tab === tab));
  }
  if(tab === 'issues') renderIssues();
  if(tab === 'capacity') renderCapacity();
  if(tab === 'files') renderFiles();
  paintState();
}
function associateControl(issue, review) {
  if (issue.type !== '文件未归类') return '';
  const options = user().items.map(item =>
    '<option value="'+esc(item.code)+'" '+(review.associations[issue.path] === item.code ? 'selected':'')+'>'+
    esc(item.code)+'　'+esc(item.name)+'</option>');
  return '<label class="field">将此文件认领到档案项<select data-associate="'+esc(issue.path)+'">'+
    '<option value="">尚未认领</option>'+options.join('')+'</select></label>';
}
function renderIssues() {
  const u = user(), r = currentDraft().review;
  $('panel').innerHTML = '<div class="sectionhead"><h3>巡检提示</h3><small>'+u.issues.length+' 条</small></div>' +
    (u.issues.map(item => '<article class="issue"><h3><span class="kind">'+esc(item.type)+'</span>'+esc(item.item)+'</h3><p>'+esc(item.detail)+'</p>' +
      (item.path ? '<div class="filepath">位置　'+esc(item.path)+'</div>' : '') +
      associateControl(item,r)+'<label class="field">人工复核结论<input data-issue="'+esc(item.id)+'" value="'+esc(r.decisions[item.id] || '')+'" placeholder="例如：已查原件，材料存在，需修正命名"></label></article>').join('') ||
      '<p class="noteonly">本次巡检未发现命名和清单异常。</p>') +
    '<label class="field">本户复核备注<textarea data-field="note" placeholder="记录有效版本、核实依据或后续待办">'+esc(r.note)+'</textarea></label>';
}
function renderCapacity() {
  const u = user(), d = currentDraft(), r = d.review, x = d.extraction;
  const docs = u.files.filter(path => /\.docx$/i.test(path));
  $('panel').innerHTML = '<h3>自备应急电源总容量</h3><p class="hint">选择本次核查采用的预案，核对表格原值及单位，再填写人工确认值。保存确认值时需在备注中记录依据。</p>' +
    '<label class="field">本次采用的预案（DOCX）<select data-field="plan"><option value="">请选择预案文件</option>'+
    docs.map(path=>'<option value="'+esc(path)+'" '+(path === r.plan ? 'selected':'')+'>'+esc(path)+'</option>').join('')+'</select></label>'+
    '<div style="margin-top:10px"><button type="button" id="extract" '+(!r.plan?'disabled':'')+'>读取并比对</button></div>'+
    '<div class="pair"><div class="box"><small>用户主台账</small><b>'+esc([u.ledgerValue,u.ledgerUnit].filter(Boolean).join(' ') || '未取得同口径值')+'</b><small>'+esc([u.ledgerSource, u.ledgerReason].filter(Boolean).join('　·　'))+'</small></div>'+
    '<div class="box"><small>预案表格</small><b>'+esc(x ? ([x.value,x.unit].filter(Boolean).join(' ') || '需人工读取') : '尚未读取')+'</b><small>'+esc(x?.source || '')+'</small></div></div>'+
    (x?'<div class="notice">'+esc([x.comparison || x.status, x.reason].filter(Boolean).join('　·　'))+'</div>':'')+
    '<div class="fields"><label class="field">经人工确认的容量<input data-field="value" value="'+esc(r.value)+'" placeholder="留空表示待核实"></label>'+
    '<label class="field">经人工确认的单位<input data-field="unit" value="'+esc(r.unit)+'" placeholder="如 kW 或 kVA"></label></div>'+
    '<label class="field">本户复核备注<textarea data-field="note" placeholder="填写确认依据及涉及的原件">'+esc(r.note)+'</textarea></label>';
}
function matchedFiles(item) {
  const original = item.paths.map(path=>'<div class="filepath">'+esc(path)+'</div>');
  const manual = Object.entries(currentDraft().review.associations)
    .filter(([,code])=>code === item.code)
    .map(([path])=>'<div class="filepath"><strong>人工认领</strong>　'+esc(path)+'</div>');
  return original.concat(manual).join('') || '未匹配';
}
function renderFiles(){
  const u = user();
  $('panel').innerHTML = '<div class="sectionhead"><h3>按规则核对的材料</h3><small>'+u.items.length+' 项</small></div>'+
    '<table class="ruletable"><thead><tr><th>标准档案项</th><th>适用规则</th><th>匹配到的文件</th></tr></thead><tbody>'+
    u.items.map(item => '<tr><td>'+esc(item.code)+'　'+esc(item.name)+'</td><td>'+esc(item.applicability)+'<br>'+esc(item.multiplicity)+'</td><td>'+
      matchedFiles(item)+'</td></tr>').join('')+
    '</tbody></table><details style="margin-top:15px"><summary>本户实际文件（'+u.files.length+' 份）</summary><ul class="slist">'+
    u.files.map(file=>'<li>'+esc(file)+'</li>').join('')+'</ul></details>';
}
async function extract() {
  const d = currentDraft(), plan = d.review.plan, identity = key();
  if (!plan) return;
  const button = $('extract');
  button.disabled = true; button.textContent = '正在读取…';
  try {
    const data = await request(userUrl()+'/extract','POST',{plan});
    if (d.review.plan === plan) d.extraction = data;
    if(key() === identity && tab === 'capacity') renderCapacity();
  } catch(e) {message(e.message,true);}
  finally {if(button.isConnected) {button.disabled = false; button.textContent = '读取并比对';}}
}
async function saveReview() {
  const d = currentDraft(), identity = key(), url = userUrl(), batchId = batch.id, index = selected;
  if (d.conflict) {
    if (!confirm('载入其他窗口最新保存的记录？此操作会放弃本页该户未保存的编辑。')) return;
    try {
      const fresh = await request(urlForBatch(batchId));
      if(!fresh.users[index]) throw new Error('本户记录已变化，请重新选择批次。');
      drafts.delete(identity);
      if (batch.id === batchId) {
        batch.users[index] = fresh.users[index];
        renderUsers();renderRecord();
      }
      message('已载入最新记录。');
    } catch(e) {message(e.message,true);}
    return;
  }
  saving = true;paintState();
  const sent = JSON.parse(JSON.stringify(d.review));
  try {
    const response = await request(url+'/review','PUT',sent);
    d.dirty = JSON.stringify(d.review) !== JSON.stringify(sent);
    if (d.dirty) {
      d.review.version = response.version;
    } else {
      d.review = editableReview(response);
      d.extraction = d.extraction || savedReading(response);
    }
    if (batch.id === batchId) {
      batch.users[index].review = response;
      renderUsers();if(selected === index && !d.dirty) renderRecord();
    }
    message('已保存本户复核。');
  } catch(e) {
    if (e.status === 409) d.conflict = true;
    message(e.message,true);
  } finally {saving = false;paintState();}
}
async function download(kind) {
  if (kind === 'report' ? currentDraft().dirty : [...drafts].some(([k,d])=> k.startsWith(batch.id+'/') && d.dirty)) {
    message('当前批次有未保存的复核记录，请先保存相关用户，再导出。',true);
    return;
  }
  const forReport = kind === 'report';
  const isZip = kind === 'reports';
  const name = forReport ? user().name+'-报告初稿.docx' : isZip ? '报告初稿-'+batch.id+'.zip' : '核查清单-'+batch.id+'.csv';
  const button = $(kind);
  button.disabled = true;
  try {
    const blob = await request(forReport ? userUrl()+'/report' : urlForBatch(batch.id)+(isZip ? '/reports.zip' : '/export'),'GET',undefined,true);
    const url = URL.createObjectURL(blob), a = document.createElement('a');
    a.href = url; a.download = name.replace(/[\\/:*?"<>|]/g,'_');
    document.body.append(a); a.click(); a.remove();setTimeout(()=>URL.revokeObjectURL(url),30000);
  } catch(e){message(e.message,true);} finally {button.disabled = false;}
}
function updateMode() {
  const demo = $('form').elements.mode.value === 'demo';
  $('paths').hidden = demo;
  paths.forEach(key=> {const input = $('form').elements[key];input.disabled = demo;input.required = !demo;});
  $('modeTip').textContent = demo ? '使用程序附带的虚拟用户、台账和报告模板。' : '粘贴本机绝对路径。每户档案放在总目录下独立的用户文件夹中。';
  $('formerror').textContent = '';
}
function newBatch(mode) {
  $('form').elements.mode.value = mode;
  updateMode(); $('dialog').showModal();
}
async function createBatch(event) {
  event.preventDefault();
  if(creating) return;
  const data = {}, form = $('form');
  paths.forEach(key => data[key] = form.elements.mode.value === 'demo' ? '' : form.elements[key].value.trim());
  if (form.elements.mode.value !== 'demo' && paths.some(key => !data[key])) { $('formerror').textContent='请填写全部四个路径。';return; }
  creating = true; form.inert = true; $('submit').textContent = '正在巡检…';
  try {
    const created = await request('/api/batches','POST',data);
    ++navigation;
    batches = [created, ...batches.filter(row => row.id !== created.id)];
    renderBatchList(); $('dialog').close(); message();
    loading = false; $('workspace').inert = false;
    displayBatch(created);
  } catch(e){$('formerror').textContent=e.message;} finally {form.inert = false; creating = false; $('submit').textContent='创建并巡检';}
}

$('create').onclick = $('custom').onclick = ()=>newBatch('real');
$('example').onclick = ()=>newBatch('demo');
$('close').onclick = $('cancel').onclick = ()=>$('dialog').close();
$('dialog').addEventListener('cancel',e=>{if(creating)e.preventDefault();});
$('form').addEventListener('change',e=>{if(e.target.name === 'mode') updateMode();});
$('form').onsubmit = createBatch;
$('batch').onchange = e=>{message();openBatch(e.target.value);};
$('filter').oninput = renderUsers;
$('users').onclick = e=>{const b=e.target.closest('[data-user]');if(b){selected=Number(b.dataset.user);rememberPosition();message();renderUsers();renderRecord();}};
document.querySelector('.tabs').onclick = e=>{if(e.target.dataset.tab){tab=e.target.dataset.tab;rememberPosition();renderRecord();}};
$('panel').addEventListener('input', e=>{
  if(e.target.dataset.issue !== undefined) {
    const decisions = currentDraft().review.decisions;
    if(e.target.value.trim()) decisions[e.target.dataset.issue] = e.target.value;
    else delete decisions[e.target.dataset.issue];
    setDirty();
  } else if(e.target.dataset.field && e.target.dataset.field !== 'plan'){
    currentDraft().review[e.target.dataset.field] = e.target.value;setDirty();
  }
});
$('panel').addEventListener('change', e=>{
  if (e.target.dataset.associate !== undefined) {
    const associations = currentDraft().review.associations;
    if (e.target.value) associations[e.target.dataset.associate] = e.target.value;
    else delete associations[e.target.dataset.associate];
    setDirty();
  } else if(e.target.dataset.field === 'plan') {
    const d = currentDraft(), r = d.review;
    if ((r.value || r.unit) && !confirm('更换预案后需要重新确认容量和单位。继续更换？')) {e.target.value = r.plan; return;}
    r.plan=e.target.value; r.value=''; r.unit='';d.extraction=null;setDirty();renderCapacity();
  }
});
$('panel').onclick = e=>{if(e.target.id === 'extract') extract();};
$('save').onclick=saveReview;
$('report').onclick=()=>download('report');
$('export').onclick=()=>download('export');
$('reports').onclick=()=>download('reports');
window.addEventListener('beforeunload',e=>{if([...drafts.values()].some(d=>d.dirty)){e.preventDefault();e.returnValue='';}});
(async()=>{
  try {
    await listBatches();
    if (batches.length) {
      const previousId = lastPosition().batch;
      const prior = batches.find(row=>row.id === previousId);
      await openBatch((prior || batches[0]).id);
    }
  } catch(e){message(e.message,true);}
})();
