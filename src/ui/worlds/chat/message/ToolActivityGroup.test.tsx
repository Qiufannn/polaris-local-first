import { describe, expect, it } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import { ToolActivityGroup } from './ToolActivityGroup';
import type { ChatMessage } from '../../../../types/domain';
const tool:ChatMessage={id:'t',role:'system',content:'',timestamp:1,toolInvocation:{id:'t',kind:'runCode',status:'executed',title:'Test',summary:'Done'}};
describe('ToolActivityGroup',()=>{
 it('keeps completed payload out of initial markup until expanded',()=>{const html=renderToStaticMarkup(<ToolActivityGroup messages={[tool]} mode="collapsed"><span>private result detail</span></ToolActivityGroup>);expect(html).toContain('<summary');expect(html).not.toContain('private result detail');expect(html).not.toContain('open=""');});
 it('renders full mode and omits completed records in hidden mode',()=>{expect(renderToStaticMarkup(<ToolActivityGroup messages={[tool]} mode="full">result</ToolActivityGroup>)).toBe('result');expect(renderToStaticMarkup(<ToolActivityGroup messages={[tool]} mode="hidden">result</ToolActivityGroup>)).toBe('');});
 it('opens failed actions and focused groups so details stay accessible',()=>{const failed={...tool,toolInvocation:{...tool.toolInvocation!,status:'failed' as const}};expect(renderToStaticMarkup(<ToolActivityGroup messages={[failed]} mode="hidden">failure detail</ToolActivityGroup>)).toContain('failure detail');expect(renderToStaticMarkup(<ToolActivityGroup messages={[tool]} mode="collapsed" forceOpen>focused detail</ToolActivityGroup>)).toContain('focused detail');});
});
