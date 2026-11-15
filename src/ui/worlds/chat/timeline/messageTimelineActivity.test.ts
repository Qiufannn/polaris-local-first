import { describe, expect, it } from 'vitest';
import type { ChatMessage } from '../../../../types/domain';
import { buildTimelineRenderItems } from './messageTimelineItems';
import { activityMessages, projectTimelineActivity } from './messageTimelineActivity';
import { resolveTimelineWindow } from './messageTimelineWindow';
function message(id:string,role:ChatMessage['role'],content=''):ChatMessage{return{id,role,content,timestamp:1};}
function tool(id:string,origin:string):ChatMessage{return{...message(id,'system'),origin:'tool-runtime',toolInvocation:{id,originMessageId:origin,kind:'runCode',status:'executed',title:'Synthetic tool',summary:'Done'}};}
function sixReads(){return[message('user','user','Read memory'),...Array.from({length:6},(_,i)=>[message(`a${i}`,'assistant'),tool(`t${i}`,`a${i}`)]).flat(),message('reply','assistant','I read the files.')];}
describe('chat activity view projection',()=>{
 it('groups six receipts without mutating stored messages or source items',()=>{
  const messages=sixReads(),before=JSON.stringify(messages),items=buildTimelineRenderItems(messages),grouped=projectTimelineActivity(items,'collapsed');
  expect(grouped).toHaveLength(3);expect(grouped[1].activityItems?.flatMap(activityMessages)).toHaveLength(6);expect(grouped[2].message.content).toBe('I read the files.');expect(JSON.stringify(messages)).toBe(before);expect(items[1].activityItems).toBeUndefined();expect(projectTimelineActivity(items,'full')).toBe(items);
 });
 it('hides completed activity and preserves the visible assistant identity and answer',()=>{
  const result=projectTimelineActivity(buildTimelineRenderItems(sixReads()),'hidden');expect(result.map(i=>i.message.id)).toEqual(['user','reply']);expect(result[1].isAssistantContinuation).toBe(false);expect(result[1].isTerminalAssistantInUserTurn).toBe(true);
 });
 it('preserves prose and does not group across a user turn',()=>{
  const messages=[message('u1','user'),message('a1','assistant'),tool('t1','a1'),message('speech','assistant','Here is what I found.'),message('a2','assistant'),tool('t2','a2'),message('u2','user'),message('a3','assistant'),tool('t3','a3')];
  const result=projectTimelineActivity(buildTimelineRenderItems(messages),'collapsed');expect(result.filter(i=>i.activityItems)).toHaveLength(3);expect(result.find(i=>i.message.id==='speech')?.message.content).toBe('Here is what I found.');
 });
 it.each(['failed','preview']as const)('keeps %s actions available in hidden mode',status=>{
  const action=tool('t','a');action.toolInvocation!.status=status;expect(projectTimelineActivity(buildTimelineRenderItems([message('a','assistant'),action]),'hidden')).toHaveLength(1);
 });
 it('keeps image, file and card products outside collapsed groups',()=>{
  const image=tool('img','a');image.attachments=[{id:'image',kind:'image',name:'Synthetic image',mimeType:'image/png'} as never];
  const file=tool('file','b');file.attachments=[{id:'file',kind:'file',name:'Synthetic file',mimeType:'text/plain'} as never];const card=tool('card','c');card.toolInvocation!.cardId='unit-card';
  const items=buildTimelineRenderItems([message('a','assistant'),image,message('b','assistant'),file,message('c','assistant'),card]);for(const mode of ['collapsed','hidden']as const)expect(projectTimelineActivity(items,mode).map(i=>i.message.id)).toEqual(['a','b','c']);
 });
 it('preserves ordinary code answers but folds code associated with execution',()=>{
  const items=buildTimelineRenderItems([message('answer','assistant','```js\nconsole.log("example");\n```'),message('execution','assistant','```js\nconsole.log("run");\n```'),tool('run','execution')]);expect(projectTimelineActivity(items,'hidden').map(i=>i.message.id)).toEqual(['answer']);
 });
 it('keeps a focused child in the virtualized window after grouping',()=>{
  const messages=Array.from({length:100},(_,i)=>[message(`u${i}`,'user'),message(`a${i}`,'assistant'),tool(`t${i}`,`a${i}`)]).flat();const items=projectTimelineActivity(buildTimelineRenderItems(messages),'collapsed');const window=resolveTimelineWindow(items,'bottom','t5',{scrollTop:30000,viewportHeight:600,rowHeights:{}});expect(window.visibleItems.some(i=>i.activityItems?.some(c=>c.toolMessages.some(t=>t.id==='t5')))).toBe(true);
 });
});
