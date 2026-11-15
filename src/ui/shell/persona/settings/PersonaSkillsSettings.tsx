import { useEffect, useRef, useState, useSyncExternalStore } from 'react';
import type { PersonaSkill } from '../../../../types/domain';
import { importPersonaSkill, normalizePersonaSkills, resolvePersonaSkills } from '../../../../engines/personaSkills';
import { canUseNativeSystemFilePicker, pickNativeSystemFiles } from '../../../../native/systemPickedFiles';
import type { PersonaTabProps } from '../personaUiShared';
import { getSkillEvidence, subscribeSkillEvidence } from '../../../../engines/personaSkillEvidence';

function SkillSourceEditor({ skill, onSave }: { skill: PersonaSkill; onSave: (source: string) => void }) {
  const [draft, setDraft] = useState(skill.source);
  useEffect(() => setDraft(skill.source), [skill.id, skill.source]);
  return <><label>技能原文<textarea aria-label={`${skill.name} 原文`} rows={8} maxLength={64000} value={draft} onChange={event => setDraft(event.target.value)} /></label><button type="button" className="mcp-btn secondary" disabled={!draft.trim() || draft === skill.source} onClick={() => onSave(draft)}>保存原文修改</button></>;
}
function ResourceNameField({ name, label, onSave }: { name: string; label: string; onSave: (value: string) => void }) {
  const [draft, setDraft] = useState(name);
  useEffect(() => setDraft(name), [name]);
  return <label>资料名称<input aria-label={label} value={draft} maxLength={120} onChange={event => setDraft(event.target.value)} onBlur={() => { if (draft.trim()) onSave(draft.trim()); else setDraft(name); }} /></label>;
}

export function PersonaSkillsSettings({ activeCollaboratorId, activePersona, onUpdatePersona }: PersonaTabProps) {
  const [source, setSource] = useState(''), [notice, setNotice] = useState('');
  const picker = useRef<HTMLInputElement>(null), context = useRef({ id: activeCollaboratorId, generation: 0 });
  if (context.current.id !== activeCollaboratorId) context.current = { id: activeCollaboratorId, generation: context.current.generation + 1 };
  const personaRef = useRef(activePersona); personaRef.current = activePersona;
  const pickerTask = useRef<{ generation: number; persona: typeof activePersona } | null>(null);
  useEffect(() => { setSource(''); setNotice(''); }, [activeCollaboratorId]);
  const skills = normalizePersonaSkills(activePersona?.advanced.skills);
  const lastRequest = useSyncExternalStore(subscribeSkillEvidence, () => getSkillEvidence(activeCollaboratorId), () => null);
  function save(next: PersonaSkill[]) { onUpdatePersona({ advanced: { skills: next } }); }
  function change(id: string, patch: Partial<PersonaSkill>) { save(skills.map(skill => skill.id === id ? { ...skill, ...patch } : skill)); }
  function add(text: string) {
    try {
      const skill = importPersonaSkill(text);
      if (skills.length >= 12) throw new Error('最多保存 12 份技能，请先整理已有条目。');
      if (skills.some(item => item.name === skill.name)) throw new Error('已有同名技能，请先编辑或删除旧条目，避免重复加载。');
      save([...skills, skill]); setSource(''); setNotice(`已保存 ${skill.name}，默认关闭。补齐资料后再启用。`);
    } catch (error) { setNotice(error instanceof Error ? error.message : '导入未完成。'); }
  }
  async function importFile(file?: File, task = pickerTask.current ?? { generation: context.current.generation, persona: activePersona }) {
    if (!file) return;
    if (task.generation !== context.current.generation || task.persona !== personaRef.current) return;
    if (file.size > 256000) { setNotice('文件过大，请导入单份 SKILL.md。'); return; }
    const text = await file.text();
    if (task.generation !== context.current.generation) return;
    if (task.persona !== personaRef.current) { setNotice('设置已变化，文件未导入，请重新选择。'); return; }
    add(text);
  }
  return <div className="local-skills-settings">
    {lastRequest && <section className="local-skill-card" aria-label="最近一次技能加载核验"><strong>最近一次请求准备</strong><p className="ps-footnote">{new Date(lastRequest.preparedAt).toLocaleString()} · {lastRequest.retention === 'kept' ? '技能上下文已保留' : lastRequest.retention === 'dropped_budget' ? '技能上下文因预算不足未加入' : '本次没有加载技能'}</p>{lastRequest.items.map((item, i) => <p className="local-skill-status" key={i}>{item.name}：{({ loaded: '正文与资料已准备', missing: `缺少 ${item.missing.join('、')}`, unmatched: '未手动触发', disabled: '已关闭', budget: '超出技能预算' } as Record<string, string>)[item.status]}</p>)}<small>这是该次请求的组装记录，不代表服务已成功回复或模型已执行全部规则；重开应用后清空。</small></section>}
    <p className="ps-footnote">导入 SKILL.md 或粘贴原文。原文与配套资料保存在当前协作者的本机设置中，随协作者完整备份；启用后会随模型请求发送。这里加载文字流程，不自动执行脚本或取得官端权限。</p>
    <label className="ps-field"><span className="ps-field-label">粘贴技能原文</span><textarea aria-label="技能原文" rows={8} value={source} onChange={event => setSource(event.target.value)} placeholder="粘贴包含 name、description 和正文的 SKILL.md，或普通 Markdown 正文" /></label>
    <div className="local-skill-actions">
      <button type="button" className="mcp-btn secondary" disabled={!source.trim()} onClick={() => add(source)}>保存为新技能</button>
      <button type="button" className="mcp-btn secondary" onClick={() => {
        const task = { generation: context.current.generation, persona: activePersona }; pickerTask.current = task;
        if (canUseNativeSystemFilePicker()) { void pickNativeSystemFiles({ accept: '.md,.txt,text/plain,text/markdown', multiple: false }).then(files => importFile(files[0], task)).catch(() => { if (task.generation === context.current.generation) setNotice('文件未导入，可直接粘贴原文。'); }); }
        else picker.current?.click();
      }}>导入 SKILL.md</button>
      <input hidden ref={picker} type="file" accept=".md,.txt,text/plain,text/markdown" onChange={event => { void importFile(event.target.files?.[0]).catch(() => setNotice('文件未读取成功。')); event.target.value = ''; }} />
    </div>
    {notice && <p role="status" className="ps-footnote">{notice}</p>}
    {skills.map(skill => {
      const status = resolvePersonaSkills([skill], [{ role: 'user', content: `@${skill.name}` }])[0];
      return <section className="local-skill-card" key={skill.id} aria-label={`技能 ${skill.name}`}>
        <header><strong>{skill.name}</strong><label><input type="checkbox" aria-label={`启用 ${skill.name}`} checked={skill.enabled} onChange={event => change(skill.id, { enabled: event.target.checked })} /> 启用</label></header>
        {skill.description && <p className="ps-footnote">{skill.description}</p>}
        <label>加载方式 <select aria-label={`${skill.name} 加载方式`} value={skill.activation} onChange={event => change(skill.id, { activation: event.target.value === 'always' ? 'always' : 'manual' })}><option value="manual">手动：消息中写 @{skill.name}</option><option value="always">常驻：当前协作者每轮加载</option></select></label>
        <p className="local-skill-status" role="status">{!skill.enabled ? '已关闭，不加入请求。' : status?.status === 'missing' ? `尚未加载，待补齐：${status.missing.join('、')}` : skill.activation === 'manual' ? `资料齐全；消息中写 @${skill.name} 时加载。` : '资料齐全；发送时加入请求，可在请求详情核对实际保留或预算丢弃状态。'}</p>
        <details><summary>原文与配套资料</summary>
          <SkillSourceEditor skill={skill} onSave={text => {
            try {
              const next = importPersonaSkill(text);
              if (skills.some(item => item.id !== skill.id && item.name === next.name)) throw new Error('已有同名技能，原文未修改。');
              const resources = [...skill.resources, ...next.resources.filter(resource => !skill.resources.some(item => item.title === resource.title))];
              change(skill.id, { source: next.source, name: next.name, description: next.description, resources }); setNotice('原文修改已保存；新增引用资料请补齐，旧资料项可按需删除。');
            } catch (error) { setNotice(error instanceof Error ? error.message : '原文未修改。'); }
          }} />
          <p className="ps-footnote">《书名号》引用已列为待补资料；误识别项可删除。请粘贴实际正文，填写链接不代表已读取。不会自动扫描 GitHub、Notion 或官端 Project。</p>
          {skill.resources.map((resource, index) => <div className="local-skill-resource" key={index}>
            <ResourceNameField name={resource.title} label={`${skill.name} 资料 ${index + 1} 名称`} onSave={title => change(skill.id, { resources: skill.resources.map((item, i) => i === index ? { ...item, title } : item) })} />
            <label>资料正文<textarea aria-label={`${skill.name} 资料 ${index + 1} 正文`} rows={5} maxLength={64000} value={resource.content} onChange={event => change(skill.id, { resources: skill.resources.map((item, i) => i === index ? { ...item, content: event.target.value } : item) })} /></label>
            <button type="button" className="mcp-btn secondary" onClick={() => change(skill.id, { resources: skill.resources.filter((_, i) => i !== index) })}>删除此资料项</button>
          </div>)}
          <button type="button" className="mcp-btn secondary" disabled={skill.resources.length >= 16} onClick={() => change(skill.id, { resources: [...skill.resources, { title: '配套资料', content: '' }] })}>添加配套资料</button>
        </details>
        <button type="button" className="mcp-btn secondary" onClick={() => save(skills.filter(item => item.id !== skill.id))}>删除技能</button>
      </section>;
    })}
  </div>;
}
