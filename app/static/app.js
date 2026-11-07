/* 本地同传前端逻辑 */
const $ = (id) => document.getElementById(id);

const LANG_LABELS = {
  auto: '自动',
  zh: '简体中文', en: '英语', ja: '日语', ko: '韩语', fr: '法语',
  de: '德语', es: '西班牙语', ru: '俄语', it: '意大利语', pt: '葡萄牙语',
  ar: '阿拉伯语', th: '泰语', vi: '越南语',
};

const state = {
  settings: null,
  options: null,
  running: false,
  busy: false,
  paused: false,
  ws: null,
  ctx: null,
  stream: null,
  node: null,
  segments: [],
  lastPartial: null,
  partialId: null,
  latency: null,
  llmOk: false,
  append: null,
  assistantVisible: true,
  courses: [],
  currentCourseId: 0,
  currentCourse: null,
  lessonQuery: '',
  courseChat: [],
  sessionCourseId: 0,
  subtitleMode: false,
  overlayOn: localStorage.getItem('li_overlay') === '1',
  overlayTimer: 0,
  overlayLastAt: 0,
};

const nativeBridge =
  window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.audio;

if (nativeBridge) nativeBridge.postMessage({ action: 'ping' });

window.nativeEvent = (obj) => {
  if (!obj || !obj.type) return;
  if (obj.type === 'native_error') {
    toast(obj.message || '音频采集失败', true);
    if (nativeBridge) nativeBridge.postMessage({ action: 'stop' });
    state.running = false;
    updateRunUI();
    return;
  }
  if (obj.type === 'native_saved') {
    toast('已保存到 ' + (obj.path || '所选位置'));
    return;
  }
  if (obj.type === 'native_save_error') {
    toast(obj.message || '保存失败', true);
    return;
  }
  if (obj.type === 'native_stopped') {
    if (state.running) {
      state.running = false;
      state.paused = false;
      updateRunUI();
      $('sessionHint').textContent = '';
      maybeAutoGlossary();
    }
    return;
  }
  onWsMessage({ data: JSON.stringify(obj) });
};

/* ---------------- 启动 ---------------- */
boot();

async function boot() {
  bindUI();
  applyShowSource();
  try {
    const res = await fetch('/api/settings');
    const data = await res.json();
    state.settings = data.settings;
    state.options = data.options;
  } catch (e) {
    state.settings = defaultSettings();
    state.options = { source_langs: ['auto', 'zh', 'en'], target_langs: ['auto', 'zh', 'en'] };
  }
  renderSelects();
  fillSettingsUI();
  if (state.overlayOn && nativeBridge) toggleOverlay(true);
  pollHealth();
  setInterval(pollHealth, 5000);
  loadCourses();
  if (state.settings.llm_backend !== 'builtin' && !state.settings.llm_model) {
    setTimeout(() => {
      openDrawer();
      $('llmNote').textContent = '首次使用：启动 oMLX 后点「自动检测」，或直接填写地址和模型。';
    }, 700);
  }
}

function defaultSettings() {
  return {
    asr_model: 'mlx-community/whisper-large-v3-turbo',
    source_lang: 'auto', target_lang: 'auto',
    llm_base_url: 'http://127.0.0.1:8000/v1',
    llm_model: '', llm_api_key: '',
    backends: [],
    end_silence_ms: 550, max_segment_s: 6, partials: true, show_source: true,
  };
}

/* ---------------- 控件 ---------------- */
function renderSelects() {
  const srcLang = $('sourceLang');
  srcLang.innerHTML = '';
  for (const code of state.options.source_langs || ['auto']) {
    const opt = document.createElement('option');
    opt.value = code;
    opt.textContent = code === 'auto' ? '自动识别' : LANG_LABELS[code] || code;
    srcLang.appendChild(opt);
  }
  srcLang.value = state.settings.source_lang || 'auto';
  const bar = $('sourceLangBar');
  bar.innerHTML = srcLang.innerHTML;
  bar.value = srcLang.value;

  const tgt = $('targetSelect');
  tgt.innerHTML = '';
  for (const code of state.options.target_langs || ['auto']) {
    const opt = document.createElement('option');
    opt.value = code;
    opt.textContent = code === 'auto' ? '自动（中英互译）' : LANG_LABELS[code] || code;
    tgt.appendChild(opt);
  }
  tgt.value = state.settings.target_lang || 'auto';
}

function fillSettingsUI() {
  const s = state.settings;
  $('llmBackend').value = s.llm_backend || 'builtin';
  $('translateMode').value = s.translate_mode || 'sentence';
  updateBackendUI();
  $('llmBaseUrl').value = s.llm_base_url || 'http://127.0.0.1:8000/v1';
  $('llmModel').value = s.llm_model || '';
  $('llmApiKey').value = s.llm_api_key || '';
  fillModelSelect([], s.llm_model || '');
  renderProfiles();
  $('asrEngine').value = s.asr_engine || 'whisper';
  $('asrQwenModel').value = s.asr_qwen_model || 'mlx-community/Qwen3-ASR-0.6B-8bit';
  $('asrModel').value = s.asr_model;
  $('sourceLang').value = s.source_lang;
  $('partials').checked = !!s.partials;
  $('contextFix').checked = s.context_fix !== false;
  $('partialTranslate').checked = s.partial_translate !== false;
  $('assistantEnabled').checked = s.assistant_enabled !== false;
  $('assistantSummary').checked = s.assistant_summary !== false;
  $('assistantQA').checked = s.assistant_qa !== false;
  $('diarization').checked = s.diarization_enabled !== false;
  $('glossaryAuto').checked = s.glossary_auto !== false;
  $('onlyTeacher').checked = !!s.assistant_only_teacher;
  $('targetSpeaker').value = s.assistant_target_speaker || '';
  $('subtitleSize').value = s.subtitle_size || 46;
  $('subtitleOpacity').value = Math.round((s.subtitle_opacity ?? 0.82) * 100);
  applySubtitleStyle();
  $('assistantBaseUrl').value = s.assistant_base_url || '';
  $('assistantModel').value = s.assistant_model || '';
  $('assistantApiKey').value = s.assistant_api_key || '';
  applyAssistant();
  $('showSource').checked = !!s.show_source;
  $('retentionDays').value = s.retention_days ?? 0;
  $('endSilence').value = s.end_silence_ms;
  $('maxSeg').value = s.max_segment_s;
  updateSliderLabels();
}

function updateSliderLabels() {
  $('endSilenceVal').textContent = `${$('endSilence').value} ms`;
  $('maxSegVal').textContent = `${$('maxSeg').value} s`;
}

function applyShowSource() {
  const show = !state.settings || state.settings.show_source !== false;
  document.body.classList.toggle('hide-source', !show);
}

function bindUI() {
  $('startBtn').addEventListener('click', toggleRun);
  $('pauseBtn').addEventListener('click', togglePause);
  $('settingsBtn').addEventListener('click', openDrawer);
  $('drawerClose').addEventListener('click', closeDrawer);
  $('drawerMask').addEventListener('click', closeDrawer);
  $('saveSettings').addEventListener('click', saveSettings);
  $('refreshModels').addEventListener('click', refreshModels);
  $('detectBtn').addEventListener('click', detectBackends);
  $('readOmlxKey').addEventListener('click', readOmlxKey);
  $('testBtn').addEventListener('click', testBackend);
  $('saveProfile').addEventListener('click', saveProfile);
  $('llmModelSelect').addEventListener('change', () => {
    if ($('llmModelSelect').value) $('llmModel').value = $('llmModelSelect').value;
  });
  document.querySelectorAll('.chip[data-base]').forEach((chip) => {
    chip.addEventListener('click', () => {
      $('llmBaseUrl').value = chip.dataset.base;
      refreshModels();
    });
  });
  $('llmBackend').addEventListener('change', () => {
    updateBackendUI();
    $('testResult').classList.add('hidden');
  });
  $('llmPill').addEventListener('click', () => {
    if (!state.settings.llm_model) openDrawer();
  });
  $('endSilence').addEventListener('input', updateSliderLabels);
  $('subtitleSize').addEventListener('input', applySubtitleStyle);
  $('subtitleOpacity').addEventListener('input', applySubtitleStyle);
  $('maxSeg').addEventListener('input', updateSliderLabels);
  $('subtitleBtn').addEventListener('click', toggleSubtitle);
  $('overlayBtn').addEventListener('click', () => toggleOverlay());
  $('tabLive').addEventListener('click', showLive);
  $('tabHistory').addEventListener('click', showCourses);
  $('createCourse').addEventListener('click', createCourse);
  $('courseRename').addEventListener('click', renameCourse);
  $('courseDelete').addEventListener('click', deleteCourse);
  $('courseExport').addEventListener('click', exportCourse);
  $('saveCourseNotes').addEventListener('click', saveCourseNotes);
  $('courseAskBtn').addEventListener('click', askCourse);
  $('courseAskInput').addEventListener('keydown', (e) => {
    if (e.key === 'Enter') askCourse();
  });
  $('outlineBtn').addEventListener('click', generateOutline);
  $('glossaryAdd').addEventListener('click', addGlossary);
  $('glossaryGen').addEventListener('click', generateGlossary);
  $('bookmarkBtn').addEventListener('click', requestBookmark);
  $('storageRefresh').addEventListener('click', loadStorage);
  $('storageCleanup').addEventListener('click', cleanupStorage);
  $('modelRefresh').addEventListener('click', loadModelStatus);
  $('assistantDownload').addEventListener('click', downloadAssistant);
  $('evalBtn').addEventListener('click', runEval);
  $('lessonSearch').addEventListener('input', () => {
    scheduleTranscriptSearch();
    state.lessonQuery = $('lessonSearch').value.trim();
    renderLessonList(state.currentCourse ? state.currentCourse.sessions : []);
  });
  $('courseSelect').addEventListener('change', () => {
    state.currentCourseId = Number($('courseSelect').value || 0);
  });
  $('assistantBtn').addEventListener('click', () => {
    state.assistantVisible = !state.assistantVisible;
    applyAssistant();
  });
  $('recapBtn').addEventListener('click', requestRecap);
  $('assistantClose').addEventListener('click', () => {
    state.assistantVisible = false;
    applyAssistant();
  });
  $('askBtn').addEventListener('click', sendAsk);
  $('materialFile').addEventListener('change', async (e) => {
    const file = e.target.files && e.target.files[0];
    if (file) await uploadMaterial(file, state.currentCourseId);
    e.target.value = '';
  });
  $('askInput').addEventListener('keydown', (e) => {
    if (e.key === 'Enter') sendAsk();
  });
  $('exportBtn').addEventListener('click', exportSrt);
  $('quitApp').addEventListener('click', async () => {
    if (!confirm('退出后需要重新打开应用才能使用。确定退出？')) return;
    try { await fetch('/api/shutdown', { method: 'POST' }); } catch {}
    document.body.innerHTML =
      '<div style="height:100vh;display:flex;align-items:center;justify-content:center;color:#9aa3af;font-size:15px">本地同传已退出，可以关闭此窗口。</div>';
  });
  $('clearBtn').addEventListener('click', clearTranscript);
  $('sourceLangBar').addEventListener('change', () => {
    $('sourceLang').value = $('sourceLangBar').value;
    setSourceLang($('sourceLangBar').value);
  });
  $('sourceLang').addEventListener('change', () => {
    $('sourceLangBar').value = $('sourceLang').value;
    setSourceLang($('sourceLang').value);
  });
  $('targetSelect').addEventListener('change', () => {
    state.settings.target_lang = $('targetSelect').value;
    fetch('/api/settings', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ target_lang: $('targetSelect').value }),
    }).catch(() => {});
  });
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') {
      if (state.subtitleMode) toggleSubtitle(false);
      else closeDrawer();
    }
  });
  window.addEventListener('beforeunload', () => releaseAudio());
}

function applyAssistant() {
  const enabled = !state.settings || state.settings.assistant_enabled !== false;
  $('assistant').classList.toggle('hidden', !(enabled && state.assistantVisible));
  const hint = $('assistantHint');
  if (!hint) return;
  if (!state.settings || state.settings.assistant_enabled === false) {
    hint.textContent = '';
    return;
  }
  const hasApi = !!(state.settings.assistant_model || '').trim();
  hint.textContent = hasApi
    ? ''
    : '助教使用内置 Qwen3-4B 模型；也可在设置 → 分析模型里改用 oMLX 等外部服务。';
}

function renderSummary(msg) {
  const box = $('summaryList');
  box.innerHTML = '';
  const text = (msg.text || '').trim();
  if (!text) {
    box.innerHTML = '<p class="note">暂无要点</p>';
    return;
  }
  const wrap = document.createElement('div');
  wrap.className = 'summary-text';
  text.split(/\n+/).map((l) => l.trim()).filter(Boolean).forEach((line) => {
    const div = document.createElement('div');
    div.textContent = line.replace(/^(-\s*)+/, '- ');
    if (line.startsWith('主题')) div.className = 'summary-topic';
    wrap.appendChild(div);
  });
  box.appendChild(wrap);
}

function renderQuestion(msg) {
  const list = $('questionList');
  const empty = list.querySelector('.note');
  if (empty) empty.remove();
  const card = document.createElement('div');
  card.className = 'question-card';
  const q = document.createElement('div');
  q.className = 'q-src';
  q.textContent = (msg.manual ? '我的提问：' : '') + msg.question;
  card.appendChild(q);
  if (msg.question_tgt) {
    const t = document.createElement('div');
    t.className = 'q-tgt';
    t.textContent = msg.question_tgt;
    card.appendChild(t);
  }
  const a = document.createElement('div');
  a.className = 'q-answer';
  a.textContent = msg.answer;
  card.appendChild(a);
  if (msg.answer_alt) {
    const alt = document.createElement('div');
    alt.className = 'q-alt';
    alt.textContent = msg.answer_alt;
    card.appendChild(alt);
  }
  const meta = document.createElement('div');
  meta.className = 'q-meta';
  const tag = document.createElement('span');
  tag.className = 'tag';
  tag.textContent = '来源：' + (msg.source || '综合');
  const note = document.createElement('span');
  note.textContent = (msg.speaker ? msg.speaker + ' · ' : '') + 'AI 生成，仅供参考';
  meta.append(tag, note);
  card.appendChild(meta);
  list.prepend(card);
}

function sendAsk() {
  const input = $('askInput');
  const text = input.value.trim();
  if (!text) return;
  if (nativeBridge) {
    nativeBridge.postMessage({ action: 'ask', text });
  } else if (state.ws && state.ws.readyState === WebSocket.OPEN) {
    state.ws.send(JSON.stringify({ type: 'ask', text }));
  } else {
    toast('先开始一次会话再提问', true);
    return;
  }
  input.value = '';
}

/* ---------------- 课程与课次 ---------------- */
function showLive() {
  document.body.classList.remove('courses-mode');
  $('coursesPage').classList.add('hidden');
  $('tabLive').classList.add('active');
  $('tabHistory').classList.remove('active');
  stopHistoryAudio();
}

function showCourses() {
  document.body.classList.add('courses-mode');
  $('coursesPage').classList.remove('hidden');
  $('tabHistory').classList.add('active');
  $('tabLive').classList.remove('active');
  loadCourses();
}

function fmtDuration(seconds) {
  const s = Math.max(0, Math.round(seconds || 0));
  const m = Math.floor(s / 60);
  const r = s % 60;
  return m ? `${m} 分 ${r} 秒` : `${r} 秒`;
}

function fmtDate(iso) {
  if (!iso) return '';
  try { return new Date(iso).toLocaleString('zh-CN'); } catch { return iso; }
}

function el(tag, cls, text) {
  const node = document.createElement(tag);
  if (cls) node.className = cls;
  if (text !== undefined) node.textContent = text;
  return node;
}

function fillCourseSelect() {
  const sel = $('courseSelect');
  const current = Number(sel.value || state.currentCourseId || 0);
  sel.innerHTML = '';
  state.courses.forEach((course) => {
    const opt = document.createElement('option');
    opt.value = String(course.id);
    opt.textContent = course.name;
    sel.appendChild(opt);
  });
  if (state.courses.some((c) => c.id === current)) sel.value = String(current);
  else if (state.courses.length) sel.value = String(state.courses[0].id);
  state.currentCourseId = Number(sel.value || 0);
}

function renderCourseList() {
  const list = $('courseList');
  list.innerHTML = '';
  state.courses.forEach((course) => {
    const item = el('div', 'course-item' + (course.id === state.currentCourseId ? ' active' : ''));
    item.appendChild(el('div', 'ci-name', course.name));
    item.appendChild(
      el('div', 'ci-meta', `${course.lesson_count || 0} 课次 · ${course.material_count || 0} 份资料`)
    );
    item.addEventListener('click', () => selectCourse(course.id));
    list.appendChild(item);
  });
}

async function loadCourses() {
  try {
    const res = await fetch('/api/courses');
    const data = await res.json();
    state.courses = data.courses || [];
    fillCourseSelect();
    renderCourseList();
    if (state.currentCourseId) {
      await selectCourse(state.currentCourseId, true);
    } else if (state.courses.length) {
      await selectCourse(state.courses[0].id, true);
    } else {
      $('courseDetail').classList.add('hidden');
      $('courseEmpty').classList.remove('hidden');
    }
  } catch (err) {
    toast('读取课程失败：' + err.message, true);
  }
}

let courseSeq = 0;
async function selectCourse(courseId, keepSearch = false) {
  sessionSeq += 1;
  const seq = ++courseSeq;
  state.currentCourseId = courseId;
  try {
    const res = await fetch(`/api/courses/${courseId}`);
    if (!res.ok) throw new Error('未找到课程');
    const data = await res.json();
    if (seq !== courseSeq) return;
    state.currentCourse = data;
    if (!keepSearch) state.lessonQuery = '';
    renderCourseDetail(data);
    $('courseEmpty').classList.add('hidden');
    $('courseDetail').classList.remove('hidden');
    $('sessionDetail').classList.add('hidden');
    $('courseSelect').value = String(courseId);
    renderCourseList();
  } catch (err) {
    toast('打开课程失败：' + err.message, true);
  }
}

function renderCourseDetail(data) {
  $('courseTitle').textContent = data.name || '课程';
  $('courseMeta').textContent = `${data.kind || '课堂'} · ${
    data.sessions ? data.sessions.length : 0
  } 个课次 · ${data.materials ? data.materials.length : 0} 份资料 · 创建于 ${fmtDate(
    data.created_at
  )}`;
  $('courseNotes').value = data.notes || '';
  $('courseOutline').textContent = data.outline || '';
  $('courseAnswer').classList.add('hidden');
  $('courseAnswer').textContent = '';
  $('courseAskInput').value = '';
  state.courseChat = [];
  renderGlossary(data.glossary || []);
  renderMaterials(data.materials || []);
  renderLessonList(data.sessions || []);
}

function renderGlossary(items) {
  const box = $('glossaryList');
  box.innerHTML = '';
  if (!items.length) {
    box.innerHTML = '<p class="detail-empty">还没有术语。可以手动添加，或点「AI 自动提取」。</p>';
    return;
  }
  items.forEach((item) => {
    const row = el('div', 'glossary-item');
    row.appendChild(el('span', 'g-term', item.term));
    row.appendChild(el('span', 'g-trans', item.translation || ''));
    row.appendChild(el('span', 'g-note', item.note || ''));
    const del = el('button', 'g-del', '删除');
    del.addEventListener('click', async () => {
      await fetch(`/api/glossary/${item.id}`, { method: 'DELETE' });
      if (state.currentCourse) {
        state.currentCourse.glossary = (state.currentCourse.glossary || []).filter(
          (g) => g.id !== item.id
        );
        renderGlossary(state.currentCourse.glossary);
      }
    });
    row.appendChild(del);
    box.appendChild(row);
  });
}

async function addGlossary() {
  const term = $('glossaryTerm').value.trim();
  const translation = $('glossaryTrans').value.trim();
  if (!term) { toast('请输入术语'); return; }
  try {
    const res = await fetch(`/api/courses/${state.currentCourseId}/glossary`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ term, translation }),
    });
    const data = await res.json();
    $('glossaryTerm').value = '';
    $('glossaryTrans').value = '';
    if (state.currentCourse) state.currentCourse.glossary = data.items || [];
    renderGlossary(data.items || []);
    toast('术语已添加');
  } catch (err) {
    toast('添加失败：' + err.message, true);
  }
}

let glossaryJobRunning = false;
async function maybeAutoGlossary() {
  if (glossaryJobRunning) return;
  if (!state.settings || state.settings.glossary_auto === false) return;
  if (state.settings.assistant_enabled === false) return;
  const courseId = Number(
    state.sessionCourseId || state.currentCourseId || $('courseSelect').value || 0
  );
  if (!courseId) return;
  glossaryJobRunning = true;
  try {
    // 等本地引擎把最后几句和课次标签写完
    await new Promise((resolve) => setTimeout(resolve, 6000));
    if (state.running) return;
    const res = await fetch(`/api/courses/${courseId}/glossary/generate`, {
      method: 'POST',
    });
    const data = await res.json();
    if (data.ok && data.added) {
      toast(`下课后已自动补充 ${data.added} 个术语`);
      if (state.currentCourseId === courseId && state.currentCourse) {
        state.currentCourse.glossary = data.items || [];
        renderGlossary(data.items || []);
      }
    }
  } catch (err) {
    // 静默失败：不影响下课后的其它操作
  } finally {
    glossaryJobRunning = false;
  }
}

async function generateGlossary() {
  if (!state.currentCourseId) return;
  const btn = $('glossaryGen');
  btn.disabled = true;
  btn.textContent = '提取中…';
  try {
    const res = await fetch(`/api/courses/${state.currentCourseId}/glossary/generate`, {
      method: 'POST',
    });
    const data = await res.json();
    if (!data.ok) throw new Error(data.error || '提取失败');
    if (state.currentCourse) state.currentCourse.glossary = data.items || [];
    renderGlossary(data.items || []);
    toast(`已提取 ${data.added || 0} 个术语`);
  } catch (err) {
    toast('提取失败：' + err.message, true);
  } finally {
    btn.disabled = false;
    btn.textContent = 'AI 自动提取';
  }
}

async function askCourse() {
  const input = $('courseAskInput');
  const question = input.value.trim();
  if (!question || !state.currentCourseId) return;
  const box = $('courseAnswer');
  box.classList.remove('hidden');
  const btn = $('courseAskBtn');
  btn.disabled = true;
  input.value = '';

  const qBlock = el('div', 'qa-block');
  qBlock.appendChild(el('div', 'qa-q', '你：' + question));
  const aBlock = el('div', 'qa-a', '思考中…');
  qBlock.appendChild(aBlock);
  box.appendChild(qBlock);
  box.scrollTop = box.scrollHeight;

  try {
    const history = state.courseChat.slice(-4).map((turn) => ({
      q: turn.q,
      a: turn.a,
    }));
    const res = await fetch(`/api/courses/${state.currentCourseId}/ask`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ question, history }),
    });
    const data = await res.json();
    if (!data.ok && data.error) throw new Error(data.error);
    aBlock.textContent = data.answer || '';
    if (data.sources && data.sources.length) {
      aBlock.appendChild(
        el('div', 'src-list', '依据：' + data.sources.join('；'))
      );
    }
    state.courseChat.push({ q: question, a: data.answer || '' });
    if (state.courseChat.length > 8) state.courseChat.shift();
  } catch (err) {
    aBlock.textContent = '回答失败：' + err.message;
    aBlock.classList.add('bad');
  } finally {
    btn.disabled = false;
    box.scrollTop = box.scrollHeight;
  }
}

async function generateOutline() {
  if (!state.currentCourseId) return;
  const btn = $('outlineBtn');
  btn.disabled = true;
  btn.textContent = '生成中…';
  try {
    const res = await fetch(`/api/courses/${state.currentCourseId}/outline`, {
      method: 'POST',
    });
    const data = await res.json();
    if (!data.ok) throw new Error(data.error || '生成失败');
    $('courseOutline').textContent = data.outline || '';
    if (state.currentCourse) state.currentCourse.outline = data.outline;
    toast('提纲已生成');
  } catch (err) {
    toast('生成失败：' + err.message, true);
  } finally {
    btn.disabled = false;
    btn.textContent = '根据资料生成提纲';
  }
}

async function exportCourse() {
  if (!state.currentCourseId) return;
  try {
    const res = await fetch(`/api/courses/${state.currentCourseId}/export`);
    const data = await res.json();
    if (!data.ok) throw new Error(data.error || '导出失败');
    downloadText((data.filename || 'course.md').replace(/[\\/:*?"<>|]/g, '_'), data.markdown);
  } catch (err) {
    toast('导出失败：' + err.message, true);
  }
}

let searchTimer = 0;
let searchSeq = 0;
function scheduleTranscriptSearch() {
  clearTimeout(searchTimer);
  const query = $('lessonSearch').value.trim();
  if (query.length < 2) {
    searchSeq += 1;
    $('lessonHits').classList.add('hidden');
    $('lessonHits').innerHTML = '';
    return;
  }
  searchTimer = setTimeout(() => searchTranscripts(query), 280);
}

async function searchTranscripts(query) {
  if (!state.currentCourseId) return;
  const seq = ++searchSeq;
  try {
    const res = await fetch(
      `/api/courses/${state.currentCourseId}/search?q=${encodeURIComponent(query)}`
    );
    const data = await res.json();
    if (seq !== searchSeq) return;
    const box = $('lessonHits');
    box.innerHTML = '';
    const items = data.items || [];
    if (!items.length) {
      box.classList.add('hidden');
      return;
    }
    box.classList.remove('hidden');
    box.appendChild(el('div', 'hits-title', `字幕命中 ${items.length} 条`));
    items.slice(0, 12).forEach((item) => {
      const row = el('div', 'hit-row');
      row.appendChild(el('span', 'hit-time', fmtClock(item.t0 || 0)));
      const body = el('div', 'hit-body');
      body.appendChild(el('div', 'hit-src', item.src));
      if (item.dst) body.appendChild(el('div', 'hit-dst', item.dst));
      body.appendChild(
        el(
          'div',
          'hit-meta',
          `${fmtDate(item.started_at)} · ${item.title}`
        )
      );
      row.appendChild(body);
      row.addEventListener('click', () => openSession(item.session_id));
      box.appendChild(row);
    });
  } catch (err) {
    // 搜索失败不打扰输入
  }
}

function renderLessonList(sessions) {
  const list = $('sessionList');
  list.innerHTML = '';
  const query = state.lessonQuery.toLowerCase();
  const items = sessions.filter((item) => {
    if (!query) return true;
    return `${item.title || ''} ${item.summary || ''} ${(item.topics || []).join(' ')}`
      .toLowerCase()
      .includes(query);
  });
  if (!items.length) {
    list.innerHTML = '<p class="detail-empty">这个课程还没有课次。</p>';
    return;
  }
  items.forEach((item) => {
    const card = el('div', 'session-card');
    card.appendChild(el('div', 'sc-title', item.title || '未命名课次'));
    card.appendChild(
      el('div', 'sc-meta', `${fmtDate(item.started_at)} · ${fmtDuration(item.duration_s)} · ${item.seg_count || 0} 句`)
    );
    card.appendChild(el('div', 'sc-summary', item.summary || ''));
    const tags = el('div', 'sc-tags');
    (item.topics || []).forEach((t) => tags.appendChild(el('span', 'sc-tag', t)));
    card.appendChild(tags);
    card.addEventListener('click', () => openSession(item.id));
    list.appendChild(card);
  });
}

async function createCourse() {
  const input = $('newCourseName');
  const name = input.value.trim();
  if (!name) { toast('请输入课程名称'); return; }
  try {
    const res = await fetch('/api/courses', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name, kind: $('newCourseKind').value }),
    });
    const data = await res.json();
    if (!data.id) throw new Error(data.error || '创建失败');
    input.value = '';
    await loadCourses();
    await selectCourse(data.id);
    toast('课程已创建');
  } catch (err) {
    toast('创建课程失败：' + err.message, true);
  }
}

async function renameCourse() {
  if (!state.currentCourse) return;
  const name = prompt('课程名称：', state.currentCourse.name || '');
  if (!name) return;
  await fetch(`/api/courses/${state.currentCourseId}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name }),
  });
  await loadCourses();
}

async function deleteCourse() {
  if (!state.currentCourse) return;
  if (state.currentCourse.is_default) {
    toast('默认的「未分类」不能删除');
    return;
  }
  if (!confirm(`删除课程「${state.currentCourse.name}」？课次和资料会移到「未分类」。`)) return;
  await fetch(`/api/courses/${state.currentCourseId}`, { method: 'DELETE' });
  state.currentCourseId = 0;
  await loadCourses();
  toast('课程已删除，课次已移到未分类');
}

async function saveCourseNotes() {
  if (!state.currentCourseId) return;
  await fetch(`/api/courses/${state.currentCourseId}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ notes: $('courseNotes').value }),
  });
  toast('笔记已保存');
  if (state.currentCourse) state.currentCourse.notes = $('courseNotes').value;
}

let historyAudio = null;
let historyPlayingBtn = null;

function stopHistoryAudio() {
  if (historyAudio) { historyAudio.pause(); }
  if (historyPlayingBtn) { historyPlayingBtn.textContent = '▶'; historyPlayingBtn = null; }
}

function playSegment(btn, segmentId) {
  const url = `/api/segments/${segmentId}/audio`;
  if (!historyAudio) {
    historyAudio = new Audio();
    historyAudio.addEventListener('ended', () => {
      if (historyPlayingBtn) historyPlayingBtn.textContent = '▶';
      historyPlayingBtn = null;
    });
  }
  if (historyPlayingBtn === btn && !historyAudio.paused) {
    historyAudio.pause();
    btn.textContent = '▶';
    historyPlayingBtn = null;
    return;
  }
  stopHistoryAudio();
  historyAudio.src = url;
  historyAudio.play().catch(() => toast('这段音频不存在或无法播放', true));
  btn.textContent = '❚❚';
  historyPlayingBtn = btn;
}

let sessionSeq = 0;
async function openSession(id) {
  const seq = ++sessionSeq;
  try {
    const res = await fetch(`/api/sessions/${id}`);
    if (!res.ok) throw new Error('未找到');
    const data = await res.json();
    if (seq !== sessionSeq) return;
    renderSessionDetail(data);
    $('courseDetail').classList.add('hidden');
    $('sessionDetail').classList.remove('hidden');
  } catch (err) {
    toast('打开课次失败：' + err.message, true);
  }
}

function renderSessionDetail(data) {
  const box = $('sessionDetail');
  box.innerHTML = '';
  stopHistoryAudio();

  const top = el('div', 'detail-top');
  const back = el('button', 'icon-btn', '返回课程');
  back.addEventListener('click', () => selectCourse(state.currentCourseId || (data.course_id || 0)));
  const title = el('h2', '', data.title || '未命名课次');
  top.append(back, title);
  box.appendChild(top);

  const meta = el('div', 'detail-meta');
  meta.textContent = `${fmtDate(data.started_at)} · ${fmtDuration(data.duration_s)} · ${
    data.audio_source === 'system' ? '系统音频' : '麦克风'
  } · ${data.asr_engine || ''}${data.asr_model ? ' / ' + data.asr_model : ''}`;
  box.appendChild(meta);

  const tags = el('div', 'sc-tags');
  (data.topics || []).forEach((t) => tags.appendChild(el('span', 'sc-tag', t)));
  box.appendChild(tags);

  const noteSec = el('div', 'detail-section');
  noteSec.appendChild(el('h3', '', '本课笔记'));
  const note = el('textarea', 'course-notes');
  note.placeholder = '这节课的重点、疑问、待办…';
  note.value = data.user_note || '';
  noteSec.appendChild(note);
  const noteActions = el('div', 'detail-actions');
  const saveNote = el('button', 'mini', '保存笔记');
  saveNote.addEventListener('click', async () => {
    await fetch(`/api/sessions/${data.id}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ user_note: note.value }),
    });
    toast('笔记已保存');
  });
  noteActions.appendChild(saveNote);
  noteSec.appendChild(noteActions);
  box.appendChild(noteSec);

  if (data.speakers && data.speakers.length) {
    const sec = el('div', 'detail-section');
    sec.appendChild(el('h3', '', '说话人'));
    const row = el('div', 'sc-tags');
    data.speakers.forEach((spk) => {
      const chip = el('span', 'sc-tag', spk.label || '说话人');
      chip.style.cursor = 'pointer';
      chip.title = '点击重命名';
      chip.addEventListener('click', async () => {
        const label = prompt('重命名说话人：', spk.label || '');
        if (!label) return;
        await fetch(`/api/sessions/${data.id}/speakers/${spk.id}`, {
          method: 'PATCH',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ label }),
        });
        openSession(data.id);
      });
      row.appendChild(chip);
    });
    sec.appendChild(row);
    box.appendChild(sec);
  }

  if (data.summary) {
    const sec = el('div', 'detail-section');
    sec.appendChild(el('h3', '', '摘要'));
    sec.appendChild(el('p', 'sc-summary', data.summary));
    box.appendChild(sec);
  }

  const bmSec = el('div', 'detail-section');
  bmSec.appendChild(el('h3', '', '书签'));
  const bmBox = el('div', '');
  bmSec.appendChild(bmBox);
  box.appendChild(bmSec);
  loadBookmarks(data.id, bmBox);

  const quizSec = el('div', 'detail-section');
  quizSec.appendChild(el('h3', '', '复习题'));
  const quizBox = el('div', '');
  const quizActions = el('div', 'detail-actions');
  const quizBtn = el('button', 'mini', '生成复习题');
  const ankiBtn = el('button', 'mini', '导出 Anki');
  quizActions.append(quizBtn, ankiBtn);
  quizSec.append(quizBox, quizActions);
  box.appendChild(quizSec);
  let quizItems = [];
  const renderQuiz = (items) => {
    quizItems = items || [];
    quizBox.innerHTML = '';
    if (!quizItems.length) {
      quizBox.innerHTML = '<p class="detail-empty">还没有复习题，点下方按钮生成。</p>';
      return;
    }
    quizItems.forEach((item) => {
      const card = el('div', 'quiz-item');
      card.appendChild(el('div', 'q', item.q));
      card.appendChild(el('div', 'a', item.a || ''));
      quizBox.appendChild(card);
    });
  };
  quizBtn.addEventListener('click', async () => {
    quizBtn.disabled = true;
    quizBtn.textContent = '出题中…';
    try {
      const res = await fetch(`/api/sessions/${data.id}/quiz`, { method: 'POST' });
      const result = await res.json();
      if (!result.ok) throw new Error(result.error || '生成失败');
      renderQuiz(result.items);
      toast('复习题已生成');
    } catch (err) {
      toast('生成失败：' + err.message, true);
    } finally {
      quizBtn.disabled = false;
      quizBtn.textContent = '生成复习题';
    }
  });
  ankiBtn.addEventListener('click', () => {
    if (!quizItems.length) { toast('先生成复习题'); return; }
    const tsv = quizItems.map((item) => `${item.q}\t${item.a}`).join('\n');
    downloadText(`quiz-${data.id}.txt`, tsv);
  });
  fetch(`/api/sessions/${data.id}/quiz`)
    .then((res) => res.json())
    .then((result) => renderQuiz(result.items || []))
    .catch(() => renderQuiz([]));

  const actions = el('div', 'detail-actions');
  const exportBtn = el('button', 'mini', '导出 SRT');
  exportBtn.addEventListener('click', () => exportSessionSrt(data));
  const mdBtn = el('button', 'mini', '导出 Markdown');
  mdBtn.addEventListener('click', () => exportSessionMd(data));
  const delBtn = el('button', 'mini', '删除课次');
  delBtn.addEventListener('click', async () => {
    if (!confirm('删除这堂课及其音频？此操作不可恢复。')) return;
    await fetch(`/api/sessions/${data.id}`, { method: 'DELETE' });
    toast('已删除');
    if (state.currentCourseId) selectCourse(state.currentCourseId);
    else loadCourses();
  });
  actions.append(exportBtn, mdBtn, delBtn);
  box.appendChild(actions);

  if (data.questions && data.questions.length) {
    const sec = el('div', 'detail-section');
    sec.appendChild(el('h3', '', '问题与答案'));
    data.questions.forEach((q) => {
      const card = el('div', 'detail-q');
      card.appendChild(el('div', 'dq-q', q.question + (q.question_tgt ? ' / ' + q.question_tgt : '')));
      card.appendChild(el('div', 'dq-a', q.answer));
      if (q.answer_alt) card.appendChild(el('div', 'dq-alt', q.answer_alt));
      sec.appendChild(card);
    });
    box.appendChild(sec);
  }

  const segSec = el('div', 'detail-section');
  segSec.appendChild(el('h3', '', '字幕'));
  (data.segments || []).forEach((seg) => {
    const row = el('div', 'detail-seg');
    if (seg.confidence === 'low') {
      row.classList.add('conf-low');
      row.title = '识别置信度较低';
    } else if (seg.confidence === 'medium') {
      row.classList.add('conf-medium');
    }
    const play = el('button', 'play', '▶');
    play.disabled = !seg.audio_path;
    play.addEventListener('click', () => playSegment(play, seg.id));
    const body = el('div');
    body.style.flex = '1';
    const speaker = (data.speakers || []).find((s) => s.id === seg.speaker_id);
    if (speaker) {
      const chip = el('span', 'spk', speaker.label || '');
      if (speaker.color) {
        chip.style.color = speaker.color;
        chip.style.borderColor = speaker.color + '66';
      }
      body.appendChild(chip);
    }
    body.appendChild(el('div', 'segsrc', seg.src_text));
    body.appendChild(el('div', 'segdst', seg.tgt_text || '（未翻译）'));
    row.append(play, body);
    segSec.appendChild(row);
  });
  box.appendChild(segSec);
}

async function loadBookmarks(sessionId, box) {
  try {
    const res = await fetch(`/api/sessions/${sessionId}/bookmarks`);
    const data = await res.json();
    const items = data.items || [];
    box.innerHTML = '';
    if (!items.length) {
      box.innerHTML = '<p class="detail-empty">上课时点底部「标记」可以记下重点句。</p>';
      return;
    }
    items.forEach((item) => {
      const row = el('div', 'bookmark-item');
      const play = el('button', 'play', '▶');
      play.disabled = !item.segment_id;
      play.addEventListener('click', () => playSegment(play, item.segment_id));
      row.appendChild(play);
      row.appendChild(el('span', 'bm-time', fmtClock(item.t0 || 0)));
      row.appendChild(el('span', 'bm-text', item.note || item.src_text || '（空标记）'));
      const del = el('button', 'bm-del', '删除');
      del.addEventListener('click', async () => {
        await fetch(`/api/bookmarks/${item.id}`, { method: 'DELETE' });
        loadBookmarks(sessionId, box);
      });
      row.appendChild(del);
      box.appendChild(row);
    });
  } catch (err) {
    box.innerHTML = '<p class="detail-empty">读取书签失败。</p>';
  }
}

function exportSessionSrt(data) {
  const lines = [];
  let index = 1;
  (data.segments || []).forEach((seg) => {
    const text = seg.tgt_text || seg.src_text;
    if (!text) return;
    lines.push(String(index++));
    lines.push(`${fmtTime(seg.t0)} --> ${fmtTime(Math.max(seg.t1, seg.t0 + 0.5))}`);
    lines.push(text);
    lines.push('');
  });
  downloadText(`session-${data.id}.srt`, lines.join('\n'));
}

function exportSessionMd(data) {
  const lines = [`# ${data.title || '未命名课次'}`, '', `- 时间：${fmtDate(data.started_at)}`, `- 时长：${fmtDuration(data.duration_s)}`, `- 标签：${(data.topics || []).join('、')}`, '', '## 摘要', '', data.summary || '', ''];
  if (data.questions && data.questions.length) {
    lines.push('## 问题与答案', '');
    data.questions.forEach((q) => {
      lines.push(`**${q.question}**`, '', q.answer, '', q.answer_alt || '', '');
    });
  }
  if (data.user_note) {
    lines.push('## 本课笔记', '', data.user_note, '');
  }
  if (data.bookmarks && data.bookmarks.length) {
    lines.push('## 书签', '');
    data.bookmarks.forEach((bm) => {
      lines.push(`- ${fmtClock(bm.t0 || 0)} ${bm.note || bm.src_text || ''}`);
    });
    lines.push('');
  }
  try {
    const quiz = JSON.parse(data.quiz || '[]');
    if (quiz.length) {
      lines.push('## 复习题', '');
      quiz.forEach((card) => {
        lines.push(`**${card.q}**`, '', card.a || '', '');
      });
    }
  } catch {}
  lines.push('## 字幕', '');
  (data.segments || []).forEach((seg) => {
    lines.push(`- **${seg.src_text}**`, `  ${seg.tgt_text || ''}`, '');
  });
  downloadText(`session-${data.id}.md`, lines.join('\n'));
}

function downloadText(filename, text) {
  if (nativeBridge) {
    nativeBridge.postMessage({ action: 'save_text', filename, text });
    return;
  }
  const blob = new Blob([text], { type: 'text/plain;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 3000);
}

function renderMaterials(items) {
  const list = $('materialList');
  list.innerHTML = '';
  if (!items.length) {
    list.innerHTML = '<p class="note">这个课程还没有资料。</p>';
    return;
  }
  items.forEach((item) => {
    const row = el('div', 'material-item');
    row.appendChild(el('span', 'm-name', item.name));
    row.appendChild(el('span', 'm-pages', `${item.pages} 页`));
    const del = el('span', 'p-del', '×');
    del.title = '删除';
    del.addEventListener('click', async () => {
      await fetch(`/api/materials/${item.id}`, { method: 'DELETE' });
      await selectCourse(state.currentCourseId, true);
    });
    row.appendChild(del);
    list.appendChild(row);
  });
}

async function uploadMaterial(file, courseId) {
  const form = new FormData();
  form.append('file', file);
  if (courseId) form.append('course_id', String(courseId));
  toast('正在解析课件…');
  try {
    const res = await fetch('/api/materials', { method: 'POST', body: form });
    const data = await res.json();
    if (!data.ok) {
      toast(data.error || '上传失败', true);
      return;
    }
    toast(`已导入 ${data.pages} 页，共 ${data.chunks} 个片段`);
    await selectCourse(courseId || state.currentCourseId, true);
  } catch (err) {
    toast('上传失败：' + err.message, true);
  }
}

function courseSuffix() {
  const sel = $('courseSelect');
  const name = sel && sel.selectedOptions[0] ? sel.selectedOptions[0].textContent : '';
  return name ? ` · ${name}` : '';
}

function setSourceLang(code) {
  state.settings.source_lang = code;
  fetch('/api/settings', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ source_lang: code }),
  }).catch(() => {});
}

function toast(message, isError = false) {
  const el = document.createElement('div');
  el.className = 'toast' + (isError ? ' error' : '');
  el.textContent = message;
  $('toasts').appendChild(el);
  setTimeout(() => {
    el.style.transition = 'opacity .3s';
    el.style.opacity = '0';
    setTimeout(() => el.remove(), 320);
  }, isError ? 6000 : 2600);
}

/* ---------------- 状态 ---------------- */
let llmWasDown = false;
async function pollHealth() {
  let data;
  try {
    const res = await fetch('/api/health');
    data = await res.json();
  } catch {
    setPill('asr', 'bad', '服务未连接');
    setPill('llm', 'bad', '服务未连接');
    return;
  }
  const asr = data.asr || {};
  if (asr.status === 'ready') {
    setPill('asr', 'ok', `识别就绪 · ${shortModel(asr.model)}`);
  } else if (asr.status === 'loading') {
    setPill('asr', 'loading', '语音模型加载中（首次需下载）');
  } else if (asr.status === 'error') {
    setPill('asr', 'bad', '语音模型加载失败');
    $('asrPill').title = asr.error || '';
  } else {
    setPill('asr', 'loading', '语音模型准备中');
  }

  const llm = data.llm || {};
  state.llmOk = !!llm.ok;
  if (llm.backend === 'builtin' && !llm.ok) {
    const loading = !llm.error || llm.error.includes('加载中');
    setPill('llm', loading ? 'loading' : 'bad', loading ? '内置翻译模型加载中…' : '内置模型加载失败');
    if (!loading) {
      toast('内置翻译模型加载失败：' + (llm.error || '未知错误'), true);
    }
    return;
  }
  if (llm.configured && llm.model && state.settings) {
    state.settings.llm_model = llm.model;
  }
  if (llm.configured === false) {
    setPill('llm', 'bad', '翻译未配置 · 点此设置');
    llmWasDown = false;
  } else if (llm.ok) {
    setPill('llm', 'ok', `翻译就绪 · ${shortModel(llm.model)}`);
    if (llmWasDown) toast('翻译服务已连接');
    llmWasDown = false;
  } else if (llm.error === '需要 API Key') {
    setPill('llm', 'bad', '翻译需要 API Key · 点此设置');
    if (!llmWasDown) {
      toast('模型服务需要 API Key：请在设置里填写（oMLX 可在其「服务器」设置中查看）', true);
    }
    llmWasDown = true;
  } else {
    setPill('llm', 'bad', '翻译服务未连接');
    if (!llmWasDown) {
      toast('翻译服务未连接：请确认模型服务（如 oMLX）已启动，或在设置里检查地址与模型', true);
    }
    llmWasDown = true;
  }
}

function setPill(kind, cls, text) {
  const pill = kind === 'asr' ? $('asrPill') : $('llmPill');
  const dot = pill.querySelector('.dot');
  dot.className = 'dot ' + cls;
  (kind === 'asr' ? $('asrText') : $('llmText')).textContent = text;
}

function shortModel(name) {
  if (!name) return '';
  return name.length > 28 ? name.slice(0, 26) + '…' : name;
}

/* ---------------- 录音与传输 ---------------- */
async function toggleRun() {
  if (state.busy) return;
  if (state.running) return stopAll();
  return startAll();
}

function resetLiveTranscript() {
  removePartial();
  state.segments = [];
  state.lastPartial = null;
  state.partialId = null;
  state.append = null;
  state.latency = null;
  $('latency').textContent = '';
  $('transcript').innerHTML = '';
  updateSubtitle();
  if (state.overlayOn && nativeBridge) {
    nativeBridge.postMessage({ action: 'overlay_text', src: '', dst: '等待声音…' });
  }
}

async function startAll() {
  if (state.settings.llm_backend === 'builtin') {
    if (!state.llmOk) {
      toast('内置翻译模型还在加载，请稍候几秒再开始', true);
      return;
    }
  } else if (!(state.settings.llm_model || '').trim()) {
    toast('还没有配置翻译模型：请在设置里填写地址并选择模型', true);
    openDrawer();
    return;
  }
  resetLiveTranscript();
  state.sessionCourseId = Number($('courseSelect').value || 0);
  if (nativeBridge) {
    const source = $('sourceSelect').value;
    nativeBridge.postMessage({
      action: 'start',
      config: {
        source,
        course_id: Number($('courseSelect').value || 0),
        source_lang: $('sourceLang').value,
        target_lang: $('targetSelect').value,
        partials: state.settings.partials,
        end_silence_ms: state.settings.end_silence_ms,
        max_segment_s: state.settings.max_segment_s,
      },
    });
    state.running = true;
    state.busy = false;
    state.paused = false;
    updateRunUI();
    $('empty')?.remove();
    $('sessionHint').textContent =
      (source === 'mic' ? '麦克风' : '系统音频') +
      courseSuffix();
    return;
  }
  state.busy = true;
  updateRunUI();
  try {
    const sourceKind = $('sourceSelect').value;
    await acquireAudio(sourceKind);

    let ctx;
    try {
      ctx = new AudioContext({ sampleRate: 16000 });
    } catch {
      ctx = new AudioContext();
    }
    await ctx.resume();
    await ctx.audioWorklet.addModule('/static/pcm-worklet.js');

    const ws = new WebSocket(
      `${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/ws/audio`
    );
    await new Promise((resolve, reject) => {
      ws.onopen = resolve;
      ws.onerror = () => reject(new Error('无法连接本地服务'));
    });

    ws.send(JSON.stringify({
      type: 'start',
      sample_rate: ctx.sampleRate,
      course_id: Number($('courseSelect').value || 0),
      source_lang: $('sourceLang').value,
      target_lang: $('targetSelect').value,
      partials: state.settings.partials,
      end_silence_ms: state.settings.end_silence_ms,
      max_segment_s: state.settings.max_segment_s,
    }));
    ws.onmessage = onWsMessage;
    ws.onclose = () => {
      if (state.running) {
        state.running = false;
        state.paused = false;
        updateRunUI();
      }
    };

    const source = ctx.createMediaStreamSource(state.stream);
    const node = new AudioWorkletNode(ctx, 'pcm-capture', {
      processorOptions: { frameSize: 512 },
    });
    const sink = ctx.createGain();
    sink.gain.value = 0;
    source.connect(node);
    node.connect(sink);
    sink.connect(ctx.destination);

    node.port.onmessage = (event) => {
      if (ws.readyState === WebSocket.OPEN) ws.send(event.data);
    };

    state.ctx = ctx;
    state.node = node;
    state.ws = ws;
    state.running = true;
    state.paused = false;
    state.latency = null;
    $('empty')?.remove();
    $('sessionHint').textContent =
      (sourceKind === 'mic' ? '麦克风' : '系统音频') +
      courseSuffix();
    if (!state.settings.partials) $('sessionHint').textContent += ' · 无预览';
  } catch (err) {
    releaseAudio();
    toast(err.message || String(err), true);
    state.running = false;
  } finally {
    state.busy = false;
    updateRunUI();
  }
}

async function acquireAudio(kind) {
  releaseAudio();
  if (!navigator.mediaDevices) {
    throw new Error('浏览器不支持音频采集，请使用 Chrome');
  }
  if (kind === 'mic') {
    state.stream = await navigator.mediaDevices.getUserMedia({
      audio: {
        echoCancellation: false,
        noiseSuppression: false,
        autoGainControl: true,
        channelCount: 1,
      },
    });
  } else {
    const display = await navigator.mediaDevices.getDisplayMedia({
      video: true,
      audio: true,
    });
    const audioTracks = display.getAudioTracks();
    if (audioTracks.length === 0) {
      display.getTracks().forEach((t) => t.stop());
      throw new Error('没有捕获到系统音频。请重新点击开始，在弹窗中选择「整个屏幕」并勾选「分享系统音频」。');
    }
    state.stream = display;
  }
}

function releaseAudio() {
  if (state.node) {
    try { state.node.disconnect(); } catch {}
    state.node = null;
  }
  if (state.ctx) {
    try { state.ctx.close(); } catch {}
    state.ctx = null;
  }
  if (state.stream) {
    state.stream.getTracks().forEach((t) => t.stop());
    state.stream = null;
  }
}

function stopAll() {
  state.paused = false;
  if (state.overlayOn && nativeBridge) {
    nativeBridge.postMessage({ action: 'overlay_text', src: '', dst: '已停止' });
  }
  if (nativeBridge) {
    nativeBridge.postMessage({ action: 'stop' });
    state.running = false;
    updateRunUI();
    $('sessionHint').textContent = '';
    maybeAutoGlossary();
    return;
  }
  const ws = state.ws;
  state.ws = null;
  if (ws) {
    try { ws.send(JSON.stringify({ type: 'stop' })); } catch {}
    setTimeout(() => { try { ws.close(); } catch {} }, 2500);
  }
  releaseAudio();
  state.running = false;
  updateRunUI();
  $('sessionHint').textContent = '';
  maybeAutoGlossary();
}

function updateRunUI() {
  const btn = $('startBtn');
  btn.classList.toggle('running', state.running);
  $('startIcon').textContent = state.running ? '■' : '▶';
  $('startLabel').textContent = state.busy ? '请稍候…' : state.running ? '停止' : '开始';
  btn.disabled = state.busy;
  const pause = $('pauseBtn');
  pause.classList.toggle('hidden', !state.running);
  pause.textContent = state.paused ? '继续' : '暂停';
  pause.classList.toggle('active', state.paused);
}

function togglePause() {
  if (!state.running) return;
  state.paused = !state.paused;
  updateRunUI();
  if (nativeBridge) {
    nativeBridge.postMessage({ action: state.paused ? 'pause' : 'resume' });
  } else {
    if (state.ws && state.ws.readyState === WebSocket.OPEN) {
      try {
        state.ws.send(JSON.stringify({ type: state.paused ? 'pause' : 'resume' }));
      } catch {}
    }
    if (state.stream) {
      state.stream.getAudioTracks().forEach((track) => {
        track.enabled = !state.paused;
      });
    }
  }
  $('sessionHint').textContent = state.paused ? '已暂停' : '';
  if (state.paused) removePartial();
  if (state.overlayOn && nativeBridge) {
    nativeBridge.postMessage({
      action: 'overlay_text',
      src: '',
      dst: state.paused ? '已暂停' : '等待声音…',
    });
  }
  toast(state.paused ? '已暂停采集' : '继续采集');
}

/* ---------------- WebSocket 事件 ---------------- */
function onWsMessage(event) {
  let msg;
  try { msg = JSON.parse(event.data); } catch { return; }
  if (state.settings && state.settings.translate_mode === 'append') {
    switch (msg.type) {
      case 'partial': appendPartial(msg); return;
      case 'draft_reset':
      case 'draft_delta': appendDraft(msg); return;
      case 'segment': appendSegment(msg); return;
      case 'translation_delta': appendFinalDelta(msg); return;
      case 'translation_reuse': appendFinalReuse(msg); return;
      case 'translation_done': appendDone(msg); return;
    }
  }
  switch (msg.type) {
    case 'ready':
      break;
    case 'partial':
      showPartial(msg);
      break;
    case 'segment': {
      const sameSentence = state.partialId === msg.id;
      const draftEl = sameSentence && state.lastPartial
        ? state.lastPartial.querySelector('.draft')
        : null;
      const draftText = draftEl ? draftEl.textContent.trim() : '';
      removePartial();
      addSegment(msg, draftText);
      break;
    }
    case 'translation_delta':
      appendTranslation(msg);
      break;
    case 'translation_done':
      finishTranslation(msg);
      break;
    case 'correction':
      applyCorrection(msg);
      break;
    case 'summary':
      renderSummary(msg);
      break;
    case 'speaker':
      applySpeaker(msg);
      break;
    case 'recap':
      renderRecap(msg);
      break;
    case 'confidence':
      applyConfidence(msg);
      break;
    case 'bookmark_saved':
      toast('已标记');
      break;
    case 'hotkey':
      if (msg.action === 'overlay') toggleOverlay();
      else if (msg.action === 'subtitle') toggleSubtitle();
      else if (msg.action === 'bookmark') requestBookmark();
      else if (msg.action === 'toggle') toggleRun();
      break;
    case 'question':
      renderQuestion(msg);
      break;
    case 'translation_reuse':
      applyReuse(msg);
      break;
    case 'draft_reset':
      if (state.lastPartial && msg.id === state.partialId) {
        const d = state.lastPartial.querySelector('.draft');
        if (d) d.textContent = '';
      }
      break;
    case 'draft_delta':
      if (state.lastPartial && msg.id === state.partialId) {
        const d = state.lastPartial.querySelector('.draft');
        if (d) {
          d.textContent += msg.delta;
          updateOverlay();
          scrollIfNeeded();
        }
      }
      break;
    case 'draft_done':
      break;
    case 'error':
      showError(msg);
      break;
  }
}

function nearBottom() {
  const el = $('transcript');
  return el.scrollHeight - el.scrollTop - el.clientHeight < 140;
}

function scrollIfNeeded(force = false) {
  const el = $('transcript');
  if (force || nearBottom()) el.scrollTop = el.scrollHeight;
}

/* ---------------- 流式追加（append-only）模式 ---------------- */
function commonPrefix(a, b) {
  const n = Math.min(a.length, b.length);
  let i = 0;
  while (i < n && a[i] === b[i]) i++;
  return a.slice(0, i);
}

function appendEnsure(id) {
  const existing = state.segments.find((seg) => seg.id === id && seg.append);
  if (existing && existing.appendState) return existing.appendState;

  const transcript = $('transcript');
  const el = document.createElement('div');
  el.className = 'line live';
  el.dataset.id = id;
  const src = document.createElement('div');
  src.className = 'src';
  const dst = document.createElement('div');
  dst.className = 'dst';
  const solid = document.createElement('span');
  solid.className = 'append-committed';
  const ghost = document.createElement('span');
  ghost.className = 'append-ghost';
  dst.append(solid, ghost);
  el.append(src, dst);
  transcript.appendChild(el);

  const seg = {
    id, src: '', srcOrig: '', srcEl: src, dst: '', dstEl: dst,
    solidEl: solid, ghostEl: ghost, isDraft: false, finalBuf: '',
    t0: 0, t1: 0, el, append: true, appendState: null,
  };
  const appendState = {
    id, seg, committed: '', prev: '', draft: '', finalText: '',
  };
  seg.appendState = appendState;
  state.segments.push(seg);
  state.append = appendState;
  return appendState;
}

function appendRender(a, candidate) {
  const seg = a.seg;
  const aligned = candidate.startsWith(a.committed);
  seg.solidEl.textContent = a.committed;
  seg.ghostEl.textContent = aligned ? candidate.slice(a.committed.length) : '';
  seg.dst = a.committed;
  scrollIfNeeded();
  updateSubtitle();
}

function appendCommit(a, candidate) {
  const stable = commonPrefix(a.prev, candidate);
  if (stable.length > a.committed.length && stable.startsWith(a.committed)) {
    a.committed = stable;
  }
  a.prev = candidate;
  appendRender(a, candidate);
}

function appendPartial(msg) {
  const a = appendEnsure(msg.id);
  a.seg.src = msg.text;
  a.seg.srcEl.textContent = msg.text;
  scrollIfNeeded();
}

function appendDraft(msg) {
  const a = appendEnsure(msg.id);
  if (msg.type === 'draft_reset') {
    a.draft = '';
    return;
  }
  a.draft += msg.delta;
  appendCommit(a, a.draft);
}

function appendSegment(msg) {
  const a = appendEnsure(msg.id);
  a.seg.src = msg.text;
  a.seg.srcEl.textContent = msg.text;
  a.seg.t0 = msg.t0 || 0;
  a.seg.t1 = msg.t1 || 0;
  a.finalText = '';
}

function appendFinalDelta(msg) {
  const a = appendEnsure(msg.id);
  a.finalText += msg.delta;
  appendCommit(a, a.finalText);
}

function appendFinalText(id, text) {
  const a = appendEnsure(id);
  a.finalText = text || '';
  // 句末以正式译文为准：草稿前缀即使和正式译文分叉，也必须整体替换，
  // 否则会永久停在草稿的半句上。
  if (a.finalText) {
    a.committed = a.finalText;
  }
  a.prev = a.finalText;
  a.seg.solidEl.textContent = a.committed;
  a.seg.ghostEl.textContent = '';
  a.seg.dst = a.committed;
  a.seg.el.classList.remove('live');
  scrollIfNeeded();
  updateSubtitle();
}

function appendFinalReuse(msg) {
  appendFinalText(msg.id, msg.text);
}

function appendDone(msg) {
  const a = appendEnsure(msg.id);
  appendFinalText(msg.id, a.finalText || a.draft || '');
}

function showPartial(msg) {
  if (!msg.text) return;
  const transcript = $('transcript');
  let el = state.lastPartial;
  if (!el || !el.isConnected || state.partialId !== msg.id) {
    el = document.createElement('div');
    el.className = 'line partial';
    el.innerHTML = '<div class="src"></div><div class="draft"></div>';
    transcript.appendChild(el);
    state.lastPartial = el;
    state.partialId = msg.id;
  }
  el.querySelector('.src').textContent = msg.text;
  updateOverlay();
  scrollIfNeeded();
}

function removePartial() {
  if (state.lastPartial) {
    state.lastPartial.remove();
    state.lastPartial = null;
  }
  state.partialId = null;
}

function addSegment(msg, draftText = '') {
  const transcript = $('transcript');
  const el = document.createElement('div');
  el.className = 'line';
  el.dataset.id = msg.id;
  const src = document.createElement('div');
  src.className = 'src';
  src.textContent = msg.text;
  const dst = document.createElement('div');
  dst.className = 'dst';
  el.appendChild(src);
  el.appendChild(dst);
  if (draftText) {
    dst.textContent = draftText;
    dst.classList.add('drafting');
  }
  transcript.appendChild(el);
  if (!state.settings.show_source) el.classList.add('src-hidden');

  state.segments.push({
    id: msg.id,
    isDraft: !!draftText,
    finalBuf: '',
    src: msg.text,
    srcOrig: '',
    srcEl: src,
    dst: draftText,
    t0: msg.t0 || 0,
    t1: msg.t1 || 0,
    el,
    dstEl: dst,
    startedAt: performance.now(),
  });
  scrollIfNeeded(true);
  updateSubtitle();
}

function applySubtitleStyle() {
  const size = Number($('subtitleSize')?.value || 46);
  const opacity = Number($('subtitleOpacity')?.value || 82) / 100;
  document.documentElement.style.setProperty('--sub-size', `${size}px`);
  document.documentElement.style.setProperty('--sub-bg', String(opacity));
  if ($('subSizeVal')) $('subSizeVal').textContent = `${size}px`;
  if ($('subOpacityVal')) $('subOpacityVal').textContent = `${Math.round(opacity * 100)}%`;
  if (state.overlayOn && nativeBridge) {
    nativeBridge.postMessage({
      action: 'overlay',
      on: true,
      size,
      opacity,
    });
  }
}

function requestRecap() {
  if (!state.running) {
    toast('先开始一次会话再总结', true);
    return;
  }
  if (nativeBridge) {
    nativeBridge.postMessage({ action: 'recap', minutes: 5 });
  } else if (state.ws && state.ws.readyState === WebSocket.OPEN) {
    state.ws.send(JSON.stringify({ type: 'recap', minutes: 5 }));
  } else {
    toast('先开始一次会话再总结', true);
    return;
  }
  toast('正在总结最近 5 分钟…');
}

function renderRecap(msg) {
  const box = $('recapList');
  box.innerHTML = '';
  const wrap = document.createElement('div');
  wrap.className = 'summary-text';
  const head = document.createElement('div');
  head.className = 'summary-topic';
  head.textContent = `最近 ${msg.minutes || 5} 分钟`;
  wrap.appendChild(head);
  (msg.text || '').split(/\n+/).map((l) => l.trim()).filter(Boolean).forEach((line) => {
    const div = document.createElement('div');
    div.textContent = line.replace(/^(-\s*)+/, '- ');
    wrap.appendChild(div);
  });
  box.appendChild(wrap);
}

function applyConfidence(msg) {
  const seg = state.segments.find((s) => s.id === msg.id);
  const node = seg ? seg.el : $('transcript').querySelector(`.line[data-id="${msg.id}"]`);
  if (!node) return;
  node.classList.remove('conf-low', 'conf-medium');
  if (msg.level === 'low') node.classList.add('conf-low');
  else if (msg.level === 'medium') node.classList.add('conf-medium');
  if (msg.level === 'low') node.title = '识别置信度较低，建议对照原文或录音';
}

function requestBookmark() {
  if (!state.running) { toast('开始上课后才能标记', true); return; }
  if (nativeBridge) {
    nativeBridge.postMessage({ action: 'bookmark' });
  } else if (state.ws && state.ws.readyState === WebSocket.OPEN) {
    state.ws.send(JSON.stringify({ type: 'bookmark' }));
  }
}

function applySpeaker(msg) {
  const line = document.querySelector(`.line[data-id="${msg.id}"]`);
  if (!line) return;
  const src = line.querySelector('.src');
  let chip = line.querySelector('.spk');
  if (!chip) {
    chip = document.createElement('span');
    chip.className = 'spk';
    src.prepend(chip);
  }
  chip.textContent = msg.label || '';
  if (msg.color) {
    chip.style.color = msg.color;
    chip.style.borderColor = msg.color + '66';
  }
  const seg = state.segments.find((s) => s.id === msg.id);
  if (seg) {
    seg.speaker = msg.label || '';
    seg.speakerId = msg.speaker_id;
  }
  updateSubtitle();
}

function applyCorrection(msg) {
  const seg = state.segments.find((s) => s.id === msg.id);
  if (!seg || !msg.text || msg.text === seg.src) return;
  seg.srcOrig = seg.srcOrig || seg.src;
  seg.src = msg.text;
  if (!seg.showOrig) seg.srcEl.textContent = seg.src;
  seg.srcEl.classList.add('fixable');
  seg.srcEl.title = '已按上下文校正，点击查看原始识别';
  if (!seg.srcEl.dataset.bound) {
    seg.srcEl.dataset.bound = '1';
    seg.srcEl.addEventListener('click', () => {
      if (!seg.srcOrig) return;
      seg.showOrig = !seg.showOrig;
      seg.srcEl.textContent = seg.showOrig ? seg.srcOrig : seg.src;
      seg.srcEl.classList.toggle('orig', seg.showOrig);
      seg.srcEl.title = seg.showOrig
        ? '当前显示原始识别，点击恢复校正文本'
        : '已按上下文校正，点击查看原始识别';
    });
  }
  updateSubtitle();
}

function appendTranslation(msg) {
  const seg = state.segments.find((s) => s.id === msg.id);
  if (!seg) return;
  if (seg.isDraft) {
    // 草稿还在屏幕上时，正式译文先缓冲，完成后再整体淡入，避免清空重打
    seg.finalBuf += msg.delta;
    return;
  }
  seg.dst += msg.delta;
  seg.dstEl.textContent = seg.dst;
  if (state.latency === null) {
    state.latency = ((performance.now() - seg.startedAt) / 1000).toFixed(1);
    $('latency').textContent = `响应 ${state.latency} s`;
  }
  scrollIfNeeded();
  updateSubtitle();
}

function applyReuse(msg) {
  const seg = state.segments.find((s) => s.id === msg.id);
  if (!seg) return;
  seg.isDraft = false;
  seg.dst = msg.text;
  seg.dstEl.textContent = msg.text;
  seg.dstEl.classList.remove('drafting');
  seg.dstEl.classList.add('swap-in');
  updateSubtitle();
}

function finishTranslation(msg) {
  const seg = state.segments.find((s) => s.id === msg.id);
  if (!seg) return;
  seg.t1 = seg.t1 || 0;
  if (seg.isDraft) {
    seg.isDraft = false;
    seg.dstEl.classList.remove('drafting');
    if (seg.finalBuf.trim()) {
      seg.dst = seg.finalBuf;
      seg.dstEl.textContent = seg.dst;
      seg.dstEl.classList.add('swap-in');
    } else {
      // 正式译文没赶上（或失败），草稿就作为最终结果保留
      seg.dst = seg.dstEl.textContent;
    }
  }
  if (!seg.dst.trim()) seg.dstEl.textContent = '（无译文）';
  updateSubtitle();
}

function showError(msg) {
  if (msg.id) {
    const seg = state.segments.find((s) => s.id === msg.id);
    if (seg) {
      seg.el.classList.add('error');
      seg.dstEl.textContent = msg.message;
    }
  }
  toast(msg.message, true);
}

/* ---------------- 字幕模式 ---------------- */
function toggleSubtitle(force) {
  state.subtitleMode = force !== undefined ? force : !state.subtitleMode;
  $('subtitle').classList.toggle('hidden', !state.subtitleMode);
  document.body.classList.toggle('subtitle-on', state.subtitleMode);
  if (state.subtitleMode) updateSubtitle();
}

let subtitleRaf = 0;

function toggleOverlay(force) {
  const next = force !== undefined ? force : !state.overlayOn;
  if (!nativeBridge) {
    toast('悬浮字幕只在 mac 应用内可用', true);
    return;
  }
  state.overlayOn = next;
  localStorage.setItem('li_overlay', next ? '1' : '0');
  $('overlayBtn').classList.toggle('active', next);
  nativeBridge.postMessage({
    action: 'overlay',
    on: next,
    size: Number($('subtitleSize').value || 46),
    opacity: Number($('subtitleOpacity').value || 82) / 100,
  });
  if (next && (state.segments.length || state.lastPartial)) {
    state.overlayLastAt = 0;
    updateOverlay(true);
  }
}

function overlayText() {
  const partial = state.lastPartial;
  if (partial && partial.isConnected) {
    const src =
      state.settings.show_source === false
        ? ''
        : partial.querySelector('.src')?.textContent || '';
    const draft = partial.querySelector('.draft')?.textContent || '';
    if (draft) return { src, dst: draft };
    if (src) return { src, dst: '…' };
  }
  const seg = state.segments[state.segments.length - 1];
  if (seg) {
    return {
      src: state.settings.show_source === false ? '' : seg.src || '',
      dst: seg.dst || '',
    };
  }
  return { src: '', dst: '' };
}

function updateOverlay(force = false) {
  if (!state.overlayOn || !nativeBridge) return;
  const now = performance.now();
  if (!force && now - state.overlayLastAt < 120) {
    if (!state.overlayTimer) {
      state.overlayTimer = setTimeout(() => {
        state.overlayTimer = 0;
        updateOverlay(true);
      }, 120);
    }
    return;
  }
  state.overlayLastAt = now;
  const text = overlayText();
  nativeBridge.postMessage({ action: 'overlay_text', src: text.src, dst: text.dst });
}

function updateSubtitle() {
  updateOverlay();
  if (!state.subtitleMode) return;
  if (subtitleRaf) return;
  subtitleRaf = requestAnimationFrame(() => {
    subtitleRaf = 0;
    const recent = state.segments.slice(-3);
    const inner = $('subInner');
    inner.innerHTML = '';
    recent.forEach((seg, i) => {
      const line = document.createElement('div');
      line.className = 'sub-line' + (i === recent.length - 1 ? ' current' : '');
      if (state.settings.show_source) {
        const s = document.createElement('div');
        s.className = 's-src';
        s.textContent = seg.src;
        line.appendChild(s);
      }
      const d = document.createElement('div');
      d.className = 's-dst';
      d.textContent = seg.dst || '…';
      line.appendChild(d);
      inner.appendChild(line);
    });
  });
}

/* ---------------- 导出 ---------------- */
function fmtMB(value) {
  const n = Number(value || 0);
  return n >= 1024 ? `${(n / 1024).toFixed(1)} GB` : `${n.toFixed(1)} MB`;
}

async function loadStorage() {
  const box = $('storageStats');
  if (!box) return;
  try {
    const res = await fetch('/api/storage');
    const data = await res.json();
    box.textContent = `数据库 ${fmtMB(data.db_mb)} · 录音 ${fmtMB(data.audio_mb)} · 资料 ${fmtMB(
      data.materials_mb
    )} · 已下载模型 ${fmtMB(data.models_mb)} · 数据总计 ${fmtMB(
      data.total_mb
    )}；磁盘剩余 ${data.free_gb} / ${data.disk_gb} GB`;
    if ($('retentionDays') && document.activeElement !== $('retentionDays')) {
      $('retentionDays').value = data.retention_days ?? 0;
    }
  } catch (err) {
    box.textContent = '读取失败：' + err.message;
  }
}

async function cleanupStorage() {
  const days = Number($('retentionDays').value || 0);
  if (!days) { toast('先把保留天数设为大于 0 的值'); return; }
  if (!confirm(`删除 ${days} 天前的课次、字幕和录音？此操作不可恢复。`)) return;
  try {
    const res = await fetch('/api/storage/cleanup', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ days }),
    });
    const data = await res.json();
    if (!data.ok) throw new Error(data.error || '清理失败');
    try {
      await fetch('/api/settings', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ retention_days: days }),
      });
      if (state.settings) state.settings.retention_days = days;
    } catch (err) {
      // 清理已完成，保留天数保存失败只影响下次启动
    }
    toast(`已清理 ${data.removed} 个课次`);
    loadStorage();
    loadCourses();
  } catch (err) {
    toast('清理失败：' + err.message, true);
  }
}

async function loadModelStatus() {
  const box = $('modelStatus');
  if (!box) return;
  try {
    const res = await fetch('/api/models/status');
    const data = await res.json();
    const tr = data.translator || {};
    const asst = data.assistant || {};
    const parts = [
      `翻译模型：${tr.bundled ? '已内置' : '未内置'}（${tr.status || '未知'}）`,
      `助教模型：${
        asst.bundled
          ? '已下载'
          : asst.download && asst.download.state === 'running'
          ? asst.download.message
          : '未下载'
      }${
        asst.status && asst.status !== 'api' && asst.status !== 'missing'
          ? `（${asst.status}）`
          : ''
      }`,
      `语音识别：${data.asr ? data.asr.engine : ''}（${data.asr ? data.asr.status : ''}）`,
    ];
    box.textContent = parts.join(' · ');
    const downloadBtn = $('assistantDownload');
    if (downloadBtn) {
      downloadBtn.disabled = !!asst.bundled;
      downloadBtn.textContent = asst.bundled ? '助教模型已内置' : '下载助教模型';
    }
    if (asst.download && asst.download.state === 'running') {
      setTimeout(loadModelStatus, 5000);
    }
  } catch (err) {
    box.textContent = '读取失败：' + err.message;
  }
}

async function downloadAssistant() {
  const btn = $('assistantDownload');
  btn.disabled = true;
  try {
    const res = await fetch('/api/models/download', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ which: 'assistant' }),
    });
    const data = await res.json();
    if (!data.ok) throw new Error(data.error || '下载失败');
    if (data.state === 'done') {
      toast(data.message || '助教模型已就绪');
    } else {
      toast('开始下载助教模型，期间可以正常使用翻译');
    }
  } catch (err) {
    toast('下载失败：' + err.message, true);
  } finally {
    loadModelStatus();
  }
}

async function runEval() {
  const btn = $('evalBtn');
  const box = $('evalResult');
  btn.disabled = true;
  box.textContent = '评测中…';
  try {
    const res = await fetch('/api/eval/run', { method: 'POST' });
    const data = await res.json();
    if (!data.ok) throw new Error(data.error || '评测失败');
    box.innerHTML = '';
    box.appendChild(
      el(
        'div',
        'eval-summary',
        `通过 ${Math.round((data.pass_rate || 0) * 100)}% · 首字平均 ${
          data.avg_first_ms ?? '-'
        } ms · 整句平均 ${data.avg_total_ms ?? '-'} ms`
      )
    );
    (data.results || []).forEach((item) => {
      const row = el(
        'div',
        'eval-row' + (item.ok ? '' : ' bad'),
        `${item.ok ? '✓' : '✗'} ${item.source} → ${item.output || item.error || '（空）'} · ${
          item.first_ms ?? '-'
        }/${item.total_ms} ms`
      );
      box.appendChild(row);
    });
  } catch (err) {
    box.textContent = '评测失败：' + err.message;
  } finally {
    btn.disabled = false;
  }
}

function fmtClock(sec) {
  const total = Math.max(0, Math.floor(Number(sec) || 0));
  const minutes = Math.floor(total / 60);
  const seconds = total % 60;
  return `${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`;
}

function fmtTime(sec) {
  const ms = Math.max(0, Math.round(sec * 1000));
  const h = String(Math.floor(ms / 3600000)).padStart(2, '0');
  const m = String(Math.floor((ms % 3600000) / 60000)).padStart(2, '0');
  const s = String(Math.floor((ms % 60000) / 1000)).padStart(2, '0');
  const milli = String(ms % 1000).padStart(3, '0');
  return `${h}:${m}:${s},${milli}`;
}

function exportSrt() {
  const segs = state.segments.filter((s) => s.dst.trim());
  if (segs.length === 0) {
    toast('还没有可导出的字幕');
    return;
  }
  const lines = [];
  segs.forEach((seg, i) => {
    const start = fmtTime(seg.t0);
    const end = fmtTime(Math.max(seg.t1, seg.t0 + 1));
    lines.push(String(i + 1));
    lines.push(`${start} --> ${end}`);
    if (state.settings.show_source) lines.push(seg.src);
    lines.push(seg.dst.trim());
    lines.push('');
  });
  const blob = new Blob([lines.join('\n')], { type: 'text/plain;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = `同传字幕_${new Date().toISOString().slice(0, 16).replace(/[:T]/g, '-')}.srt`;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 3000);
}

function clearTranscript() {
  removePartial();
  state.segments = [];
  state.lastPartial = null;
  state.partialId = null;
  state.append = null;
  state.latency = null;
  $('latency').textContent = '';
  const transcript = $('transcript');
  transcript.innerHTML = `
    <div class="empty" id="empty">
      <h1>离线同声传译</h1>
      <p>选择音频来源后点「开始」。语音识别与翻译全部在本机完成，不联网。</p>
      <p class="hint">系统音频：点开始后在弹窗中选择「整个屏幕」并勾选「分享系统音频」。</p>
    </div>`;
  updateSubtitle();
}

/* ---------------- 设置 ---------------- */
async function openDrawer() {
  $('drawerMask').classList.remove('hidden');
  $('drawer').classList.remove('hidden');
  try {
    const res = await fetch('/api/settings');
    const data = await res.json();
    state.settings = data.settings;
    state.options = data.options;
    renderSelects();
  } catch {}
  fillSettingsUI();
  loadStorage();
  loadModelStatus();
}

function closeDrawer() {
  $('drawerMask').classList.add('hidden');
  $('drawer').classList.add('hidden');
}

/* ---------------- 翻译服务配置 ---------------- */
function escapeHtml(value) {
  return String(value).replace(/[&<>"]/g, (c) =>
    ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c])
  );
}

function updateBackendUI() {
  const builtin = $('llmBackend').value === 'builtin';
  $('apiConfig').style.display = builtin ? 'none' : '';
  $('builtinNote').style.display = builtin ? '' : 'none';
}

function backendInputs() {
  return {
    base_url: $('llmBaseUrl').value.trim(),
    model: $('llmModel').value.trim(),
    api_key: $('llmApiKey').value.trim(),
  };
}

function fillModelSelect(models, current) {
  const sel = $('llmModelSelect');
  sel.innerHTML = '';
  let list = (models || []).filter(Boolean);
  if (current && !list.includes(current)) list = [current, ...list];
  if (!list.length) {
    const opt = document.createElement('option');
    opt.value = '';
    opt.textContent = '（未检测到模型）';
    sel.appendChild(opt);
    return;
  }
  for (const item of list) {
    const opt = document.createElement('option');
    opt.value = item;
    opt.textContent = item;
    sel.appendChild(opt);
  }
  sel.value = current && list.includes(current) ? current : list[0];
  if (!current) $('llmModel').value = sel.value;
}

async function readOmlxKey() {
  try {
    const res = await fetch('/api/backends/omlx-key');
    const data = await res.json();
    if (!data.ok || !data.api_key) {
      $('llmNote').textContent = '没有在 oMLX 配置里找到 API Key，请手动填写。';
      return;
    }
    $('llmApiKey').value = data.api_key;
    $('llmNote').textContent = '已读取 oMLX 的 API Key，正在重新检测…';
    await detectBackends();
  } catch (err) {
    $('llmNote').textContent = '读取失败：' + err.message;
  }
}

async function detectBackends() {
  const note = $('llmNote');
  note.textContent = '正在扫描本机服务…';
  try {
    const key = $('llmApiKey').value.trim();
    const res = await fetch(
      '/api/backends/detect' + (key ? `?api_key=${encodeURIComponent(key)}` : '')
    );
    const data = await res.json();
    const services = data.services || [];
    if (!services.length) {
      note.textContent = '没有发现运行中的服务。请先在 oMLX 中启动模型服务（默认端口 8000）。';
      return;
    }
    const svc = services.find((s) => s.name === 'oMLX') || services[0];
    $('llmBaseUrl').value = svc.base_url;
    if (svc.requires_key) {
      $('llmModel').value = $('llmModel').value || '';
      note.textContent =
        '发现了 oMLX，但它需要 API Key。请在 oMLX 的「服务器」设置里复制 Key，填到下面的 API Key 栏，再点一次「自动检测」。';
      return;
    }
    fillModelSelect(svc.models, $('llmModel').value);
    note.textContent = `发现 ${services.length} 个服务：${services
      .map((s) => `${s.name}（${s.models.length} 个模型）`)
      .join('、')}。已填入 ${svc.name}。`;
  } catch (err) {
    note.textContent = '检测失败：' + err.message;
  }
}

async function refreshModels() {
  const note = $('llmNote');
  note.textContent = '正在读取模型列表…';
  try {
    const payload = backendInputs();
    const res = await fetch('/api/backends/models', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    });
    const data = await res.json();
    if (!data.ok) {
      note.textContent = data.error || '读取失败';
      return;
    }
    fillModelSelect(data.models, $('llmModel').value);
    note.textContent = data.models.length
      ? `已连接，共 ${data.models.length} 个模型`
      : '服务已连接，但没有加载模型';
  } catch (err) {
    note.textContent = '读取失败：' + err.message;
  }
}

async function testBackend() {
  const box = $('testResult');
  box.classList.remove('hidden');
  box.innerHTML = '<span class="note">正在测试（会真实翻译一句，模型冷启动可能需要几秒）…</span>';
  try {
    const builtin = $('llmBackend').value === 'builtin';
    const payload = backendInputs();
    const res = await fetch(builtin ? '/api/builtin/test' : '/api/backends/test', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: builtin ? '{}' : JSON.stringify(payload),
    });
    const data = await res.json();
    if (!data.ok) {
      box.innerHTML =
        '<span class="bad">测试失败：</span>' + escapeHtml(data.error || '未知错误');
      return;
    }
    box.innerHTML =
      `<span class="ok">连接成功</span> · 首字 ${data.first_ms} ms · 总耗时 ${data.total_ms} ms<br>` +
      `<span class="mono">${escapeHtml(data.source)}</span><br>` +
      `<span class="mono">→ ${escapeHtml(data.translation)}</span>`;
    if (data.models && data.models.length) fillModelSelect(data.models, payload.model);
  } catch (err) {
    box.innerHTML = '<span class="bad">测试失败：</span>' + escapeHtml(err.message);
  }
}

function renderProfiles() {
  const wrap = $('profileList');
  wrap.innerHTML = '';
  const profiles = state.settings.backends || [];
  if (!profiles.length) {
    const empty = document.createElement('p');
    empty.className = 'note';
    empty.textContent = '暂无配置档。填好地址和模型后可以保存，方便在多个模型间切换。';
    wrap.appendChild(empty);
    return;
  }
  profiles.forEach((profile, index) => {
    const item = document.createElement('div');
    item.className = 'profile-item';
    const name = document.createElement('span');
    name.className = 'p-name';
    name.textContent = profile.name || profile.model;
    const model = document.createElement('span');
    model.className = 'p-model';
    model.textContent = profile.model || '';
    const del = document.createElement('span');
    del.className = 'p-del';
    del.textContent = '×';
    del.title = '删除';
    item.append(name, model, del);
    item.addEventListener('click', () => {
      $('llmBaseUrl').value = profile.base_url || '';
      $('llmModel').value = profile.model || '';
      $('llmApiKey').value = profile.api_key || '';
      fillModelSelect([], profile.model || '');
      $('llmNote').textContent = `已载入「${profile.name || profile.model}」，点保存生效。`;
    });
    del.addEventListener('click', async (event) => {
      event.stopPropagation();
      const next = (state.settings.backends || []).filter((_, i) => i !== index);
      await persistBackends(next);
    });
    wrap.appendChild(item);
  });
}

async function persistBackends(list) {
  try {
    const res = await fetch('/api/settings', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ backends: list }),
    });
    const data = await res.json();
    state.settings = data.settings;
    renderProfiles();
  } catch (err) {
    toast('保存配置档失败：' + err.message, true);
  }
}

async function saveProfile() {
  const base = $('llmBaseUrl').value.trim();
  const model = $('llmModel').value.trim();
  if (!base || !model) {
    toast('先填写接口地址和模型', true);
    return;
  }
  const name = prompt('给这套配置起个名字：', model);
  if (!name) return;
  const profile = {
    name: name.trim(),
    base_url: base,
    model,
    api_key: $('llmApiKey').value.trim(),
  };
  await persistBackends([...(state.settings.backends || []), profile]);
  toast('配置档已保存');
}

async function saveSettings() {
  const patch = {
    llm_base_url: $('llmBaseUrl').value.trim(),
    llm_backend: $('llmBackend').value,
    translate_mode: $('translateMode').value,
    llm_model: $('llmModel').value.trim(),
    llm_api_key: $('llmApiKey').value.trim(),
    backends: state.settings.backends || [],
    asr_engine: $('asrEngine').value,
    asr_qwen_model: $('asrQwenModel').value,
    asr_model: $('asrModel').value,
    source_lang: $('sourceLang').value,
    end_silence_ms: Number($('endSilence').value),
    max_segment_s: Number($('maxSeg').value),
    partials: $('partials').checked,
    context_fix: $('contextFix').checked,
    partial_translate: $('partialTranslate').checked,
    assistant_enabled: $('assistantEnabled').checked,
    assistant_summary: $('assistantSummary').checked,
    assistant_qa: $('assistantQA').checked,
    diarization_enabled: $('diarization').checked,
    glossary_auto: $('glossaryAuto').checked,
    assistant_only_teacher: $('onlyTeacher').checked,
    assistant_target_speaker: $('targetSpeaker').value.trim(),
    subtitle_size: Number($('subtitleSize').value),
    subtitle_opacity: Number($('subtitleOpacity').value) / 100,
    assistant_base_url: $('assistantBaseUrl').value.trim(),
    assistant_model: $('assistantModel').value.trim(),
    assistant_api_key: $('assistantApiKey').value.trim(),
    show_source: $('showSource').checked,
    retention_days: Number($('retentionDays').value || 0),
    target_lang: $('targetSelect').value,
  };
  try {
    const res = await fetch('/api/settings', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(patch),
    });
    const data = await res.json();
    state.settings = data.settings;
    applyShowSource();
    closeDrawer();
    toast('设置已保存' + (state.running ? '，重新开始后生效' : ''));
    pollHealth();
  } catch (err) {
    toast('保存失败：' + err.message, true);
  }
}
