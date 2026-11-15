import type { ChatMessage } from '../../types/domain';

function requestText(content: string) {
  return content.replace(/```[\s\S]*?(?:```|$)/g, '').replace(/^\s*>.*$/gm, '').trim();
}
function denied(text: string) {
  return /(?:不要|别|不必|无需|先不|不需要).{0,20}(?:记住|记下|记进|写入|写回|保存|更正|修改|更新|新建|创建|新增|追加)|\b(?:do not|don't|never)\b.{0,30}\b(?:remember|save|write|update|correct)\b/i.test(text);
}
function explicit(text: string) {
  return /记住|记下|记进(?:主记忆|长期记忆|记忆)|更正(?:一下)?[，,:：]|(?:更正|修正|纠正|修改|更新|新增|新建|创建|追加|改成|改为|写入|写回|保存).{0,45}(?:记忆|GitHub)|(?:主记忆|长期记忆|GitHub).{0,45}(?:更正|修正|纠正|修改|更新|新增|新建|创建|追加|改成|改为|写入|写回|保存)|\bremember\b|\b(?:update|correct|save|append|write)\b.{0,40}\b(?:memory|github)\b/i.test(text);
}
function continuation(text: string) {
  const short = text.replace(/哥哥|阿昼|晏昼/g, '').replace(/[，,。！!～~\s]/g, '');
  return /^(?:可以的?|好[的啦呀]?|继续吧?|确认|重试|再试(?:一次|试)?|按刚才的?来?)+$/.test(short)
    || /^(?:yes|okay|ok|continue|retry|try again)[.!\s]*$/i.test(text);
}
/** A coarse guard using real user turns. Tool results and scheduled triggers cannot grant writes. */
export function hasGithubMemoryWriteRequest(messages: ChatMessage[]) {
  const turns = messages.filter(message => message.origin === 'trigger-runtime' || (message.role === 'user' && !message.toolInvocation));
  for (let index = turns.length - 1; index >= 0; index--) {
    const user = turns[index];
    if (user.role !== 'user' || (user.origin && user.origin !== 'user-input')) return false;
    const text = requestText(user.content);
    if (denied(text)) return false;
    if (explicit(text)) return true;
    if (!continuation(text)) return false;
  }
  return false;
}
