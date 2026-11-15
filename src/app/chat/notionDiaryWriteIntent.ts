import type { ChatMessage } from '../../types/domain';

function requestText(content: string) {
  return content.replace(/```[\s\S]*?(?:```|$)/g, '').replace(/^\s*>.*$/gm, '')
    // "Don't claim it is saved" governs reporting, not whether to write. Remove
    // that clause from both checks so it can neither deny nor authorize a save.
    // Stop at punctuation or the next prohibition: even without a comma, a
    // later "don't write" must still take precedence.
    .replace(/(?:不要|别|不必|无需|不需要)(?:再|先)?(?:说|声称|宣称|谎称|假装|称|告诉我|回复)(?:(?!(?:不要|别|不必|无需|先不|不需要))[^，,。！？!?；;\n])*/g, '')
    .replace(/\b(?:do not|don't|never)\s+(?:claim|say|pretend|report|tell)\b(?:(?!\b(?:do not|don't|never)\b)[^，,。！？!?.；;\n])*/gi, '')
    .trim();
}

/** Only a real user's diary request can authorize the native diary writer. */
export function hasNotionDiaryWriteRequest(messages: ChatMessage[]) {
  const turns = messages.filter(message => message.origin === 'trigger-runtime' || (message.role === 'user' && !message.toolInvocation));
  for (let index = turns.length - 1; index >= 0; index--) {
    const user = turns[index];
    if (user.role !== 'user' || (user.origin && user.origin !== 'user-input')) return false;
    const text = requestText(user.content);
    if (/(?:不要|别|不必|无需|先不|不需要).{0,25}(?:日记|写入|写回|保存|记录)|\b(?:do not|don't|never)\b.{0,40}\b(?:diary|journal|write|save)\b/i.test(text)) return false;
    if (/(?:写|补|续|保存|记录|记进|存到).{0,16}(?:日记|Notion)|(?:日记|Notion).{0,20}(?:写入|写回|补写|保存|记录)|\b(?:write|append|save)\b.{0,35}\b(?:diary|journal|notion)\b/i.test(text)) return true;
    const short = text.replace(/哥哥|阿昼|晏昼/g, '').replace(/[，,。！!～~\s]/g, '');
    if (!/^(?:可以的?|好[的啦呀]?|继续吧?|确认|重试|再试(?:一次|试)?|按刚才的?来?)+$/.test(short) && !/^(?:yes|okay|ok|continue|retry|try again)[.!\s]*$/i.test(text)) return false;
  }
  return false;
}
