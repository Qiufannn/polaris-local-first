import { describe, expect, it } from 'vitest';
import { importPersonaSkill, normalizePersonaSkills, resolvePersonaSkills, buildPersonaSkillContext } from './personaSkills';
import { createPersonaTemplate } from '../config/persona/personaBuilder';
import { buildAssistantPromptParts } from './request/requestPromptLayers';
import { selectPromptPartsForBudget } from './request/requestTruncation';
import type { AssistantRequestBudgetPlan } from './request/requestBudget';
import { recordSkillPreparation, getSkillEvidence } from './personaSkillEvidence';
import type { AssistantRequestAudit } from './request/requestAudit';
import { normalizeHomeUrl } from './localHome';

const original = '---\nname: unit-skill\ndescription: "Only for unit requests"\n---\n\n# Unit workflow\nRead 《Unit source》 first.\nKeep the original.\n';
describe('portable local skills', () => {
  it('preserves the exact supplied source and discovers missing companion documents without inventing access', () => {
    const skill = importPersonaSkill(original); expect(skill.source).toBe(original); expect(skill.name).toBe('unit-skill'); expect(skill.enabled).toBe(false); expect(skill.resources).toEqual([{ title: 'Unit source', content: '' }]);
    expect(() => importPersonaSkill('')).toThrow(); expect(() => importPersonaSkill('a'.repeat(64001))).toThrow(); expect(() => importPersonaSkill('---\nname: empty\n---\n')).toThrow();
  });
  it('accepts ordinary Markdown and CRLF metadata without running any code', () => {
    expect(importPersonaSkill('# Plain workflow\n```js\nthrow new Error()\n```').name).toBe('Plain workflow');
    expect(importPersonaSkill(original.replace(/\n/g, '\r\n')).name).toBe('unit-skill');
    expect(importPersonaSkill('---\nname: folded\ndescription: >\n  First line.\n  Second line.\n---\n# Workflow').description).toBe('First line. Second line.');
  });
  it('does not activate imported or unmatched skills, even when assistant history contains their trigger', () => {
    const skill = importPersonaSkill(original); expect(buildPersonaSkillContext([skill], [{ role: 'user', content: '@unit-skill' }])).toBe('');
    skill.enabled = true; expect(buildPersonaSkillContext([skill], [{ role: 'assistant', content: '@unit-skill' }, { role: 'user', content: 'hello' }])).toBe('');
  });
  it('blocks missing sources instead of claiming the full skill is loaded', () => {
    const imported = importPersonaSkill(original);
    const skill = { ...imported, enabled: true, activation: 'always' as const, resources: imported.resources.map(resource => ({ ...resource, required: true })) };
    const context = buildPersonaSkillContext([skill], []); expect(context).toContain('缺少配套资料 Unit source'); expect(context).not.toContain('# Unit workflow');
    expect(resolvePersonaSkills([skill], [])[0].status).toBe('missing');
    expect(resolvePersonaSkills([{ ...skill, resources: [{ title: 'Unit source', content: 'https://unit.invalid/source.md', required: true }] }], [])[0].status).toBe('missing');
  });
  it('treats extracted book titles and legacy unconfirmed entries as reference hints instead of mandatory documents', () => {
    const skill = { ...importPersonaSkill(original), enabled: true, activation: 'always' as const };
    const state = resolvePersonaSkills([skill], [])[0]; expect(state.status).toBe('loaded'); expect(state.missing).toEqual([]); expect(state.unresolved).toEqual(['Unit source']);
    const context = buildPersonaSkillContext([skill], []); expect(context).toContain('# Unit workflow'); expect(context).toContain('未提供的引用线索：Unit source'); expect(context).toContain('不代表文件存在或已读取'); expect(context).not.toContain('（用户手动提供）：\n\n');
  });
  it('loads a complete manual skill and the actual resource text without replacing identity or world books', () => {
    const skill = { ...importPersonaSkill(original), enabled: true, resources: [{ title: 'Unit source', content: 'Actual unit source content.' }] };
    const context = buildPersonaSkillContext([skill], [{ role: 'user', content: 'Please @unit-skill' }]); expect(context).toContain('# Unit workflow'); expect(context).toContain('Actual unit source content.'); expect(context).toContain('不虚构平台验证结果');
    const persona = createPersonaTemplate({ id: 'unit', name: 'Unit', description: '', compiledPrompt: 'Existing identity', advanced: { skills: [skill], regexTriggers: 'Unit|Existing world book' } });
    const restored = createPersonaTemplate(JSON.parse(JSON.stringify(persona)));
    expect(restored.advanced.skills).toEqual([skill]); expect(restored.compiledPrompt).toBe('Existing identity'); expect(restored.advanced.regexTriggers).toContain('Existing world book');
  });
  it('keeps malformed imports out and bounds oversized skill collections', () => {
    expect(normalizePersonaSkills([null, {}, { source: 42 }])).toEqual([]); expect(normalizePersonaSkills(Array.from({ length: 20 }, () => importPersonaSkill('# Unit\nHello')))).toHaveLength(12);
  });
  it('reports whole skills that exceed the local context budget instead of silently cutting their instructions', () => {
    const skill = { ...importPersonaSkill('# Unit\n' + 'x'.repeat(50000)), enabled: true, activation: 'always' as const, resources: [{ title: 'Resource', content: 'y'.repeat(50000) }] };
    expect(resolvePersonaSkills([skill], [])[0].status).toBe('budget'); expect(buildPersonaSkillContext([skill], [])).not.toContain('xxxx');
  });
  it('routes skills through real request prompt assembly and records outer budget drops', () => {
    const skill = { ...importPersonaSkill('# Unit\nA unit workflow.'), enabled: true, activation: 'always' as const };
    const parts = buildAssistantPromptParts({ personaPrompt: 'Identity', personaPromptSource: 'custom', templateContext: {} as never, messages: [], skills: [skill] });
    const part = parts.find(item => item.name === 'local_skill_context')!; expect(part.enabled).toBe(true); expect(part.content).toContain('A unit workflow.');
    const plan = { buckets: { identity: { maxTokens: null }, capability: { maxTokens: 1 } } } as AssistantRequestBudgetPlan;
    const result = selectPromptPartsForBudget({ promptParts: [part], plan }); expect(result.selectedPromptParts).toEqual([]); expect(result.promptPartDecisions[0].status).toBe('dropped_budget');
    recordSkillPreparation({ collaboratorId: 'unit-evidence', requestId: 'unit-request', truncation: { promptParts: result.promptPartDecisions } } as AssistantRequestAudit, [skill], []);
    expect(getSkillEvidence('unit-evidence')).toMatchObject({ requestId: 'unit-request', retention: 'dropped_budget', items: [{ name: 'Unit', status: 'loaded' }] }); expect(getSkillEvidence('other-persona')).toBeNull();
    expect(JSON.stringify(getSkillEvidence('unit-evidence'))).not.toContain('A unit workflow.');
  });
  it('allows only HTTPS home links without URL credentials', () => {
    for (const url of ['javascript:alert(1)', 'http://unit.invalid', 'https://user:password@unit.invalid', 'file:///tmp/home', 'broken']) expect(normalizeHomeUrl(url)).toBe('');
    expect(normalizeHomeUrl(' https://unit.invalid/home ')).toBe('https://unit.invalid/home');
  });
});
