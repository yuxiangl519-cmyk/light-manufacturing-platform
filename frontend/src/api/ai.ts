import { api, clearCsrf } from './http'
import type {Execution} from './ai-presentation'

export interface ConversationSummary { id: string; title: string; status: string; updatedAt?: string }
export interface AiMessage { id: string; role: string; content: string; createdAt?: string }
export interface Conversation extends ConversationSummary { messages: AiMessage[]; pendingQuestion?: string; context?: Record<string,unknown> }
export type AiEvent = { event: string; data: { node?: string; message?: string; content?: string; code?: string; status?: string; context?: Record<string,unknown>;execution?:Execution } }
export const listConversations = async () => (await api.get<ConversationSummary[]>('/ai/conversations')).data
export const createConversation = async (title?: string) => (await api.post<ConversationSummary>('/ai/conversations', title?{title}:{})).data
export const getConversation = async (id: string) => (await api.get<Conversation>(`/ai/conversations/${encodeURIComponent(id)}`)).data
export const cancelConversation = async (id: string) => { await api.post(`/ai/conversations/${encodeURIComponent(id)}/cancel`) }

/** Incremental UTF-8 SSE decoder. The final event still requires its blank-line delimiter. */
export function createSseParser(receive: (event: AiEvent) => void) {
  const decoder = new TextDecoder()
  let buffer = '', event = 'message', lines: string[] = [], frameSize = 0
  function line(value: string) {
    if (!value) {
      if (lines.length) receive({ event, data: JSON.parse(lines.join('\n')) as AiEvent['data'] })
      event = 'message'; lines = []; frameSize = 0; return
    }
    if (value.startsWith(':')) return
    const colon = value.indexOf(':')
    const field = colon < 0 ? value : value.slice(0, colon)
    let data = colon < 0 ? '' : value.slice(colon + 1)
    if (data.startsWith(' ')) data = data.slice(1)
    if (field === 'event') event = data
    if (field === 'data') { frameSize += data.length; if(frameSize > 40000)throw new Error('分析响应过大，已停止接收'); lines.push(data) }
  }
  return (bytes?: Uint8Array) => {
    buffer += decoder.decode(bytes, { stream: bytes !== undefined })
    let index: number
    while ((index = buffer.indexOf('\n')) >= 0) {
      line(buffer.slice(0, index).replace(/\r$/, '')); buffer = buffer.slice(index + 1)
    }
    if(buffer.length > 40000)throw new Error('分析响应过大，已停止接收')
  }
}

export async function streamMessage(id:string,message:string,signal:AbortSignal,receive:(event:AiEvent)=>void,timeoutMs=190000){
  const controller=new AbortController();let timedOut=false
  const abort=()=>controller.abort();signal.addEventListener('abort',abort,{once:true});if(signal.aborted)abort()
  const timeout=setTimeout(()=>{timedOut=true;controller.abort()},timeoutMs)
  try{return await readMessage(id,message,controller.signal,receive)}catch(cause){if(timedOut)throw new Error('分析等待超时，已停止接收。请刷新任务状态或取消后再重试。');throw cause}
  finally{clearTimeout(timeout);signal.removeEventListener('abort',abort)}
}
async function readMessage(id: string, message: string, signal: AbortSignal, receive: (event: AiEvent) => void) {
  const { data: csrf } = await api.get<{ token: string }>('/auth/csrf', { signal })
  const response = await fetch(`/api/v1/ai/conversations/${encodeURIComponent(id)}/messages`, {
    method: 'POST', credentials: 'same-origin', signal,
    headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream', 'X-CSRF-TOKEN': csrf.token },
    body: JSON.stringify({ message }),
  })
  if (response.status === 401) { clearCsrf(); window.dispatchEvent(new CustomEvent('session-expired')) }
  if (!response.ok) throw new Error(response.status === 403 ? '没有分析权限或会话校验失效，请刷新页面后重试' : `分析请求失败（${response.status}），请检查服务后重试`)
  if (!response.headers.get('content-type')?.includes('text/event-stream') || !response.body) throw new Error('分析服务返回了无效的数据流')
  const reader = response.body.getReader(), parse = createSseParser(receive)
  try {
    while (true) { const { value, done } = await reader.read(); if (done) break; parse(value) }
    parse()
  } finally { reader.releaseLock() }
}

/** Minimal Markdown blocks rendered only through Vue text interpolation, never raw HTML. */
export type MarkdownBlock = {kind:'heading'|'item'|'text';text:string} | {kind:'table';headers:string[];rows:string[][]}
export function markdownBlocks(content: string):MarkdownBlock[] {
  const lines=content.split('\n'),blocks:MarkdownBlock[]=[]
  const cells=(line:string)=>line.trim().replace(/^\|/,'').replace(/\|$/,'').split('|').map(cell=>cell.trim())
  for(let i=0;i<lines.length;i++) {
    const line=lines[i]!
    if(line.trim().startsWith('|')&&/^\s*\|(?:\s*:?-+:?\s*\|)+\s*$/.test(lines[i+1]||'')) {
      const headers=cells(line),rows:string[][]=[];i+=2
      while(i<lines.length&&lines[i]!.trim().startsWith('|')){rows.push(cells(lines[i]!));i++}
      i--;blocks.push({kind:'table',headers,rows});continue
    }
    const heading = /^(#{1,3})\s+(.+)$/.exec(line)
    if (heading) {blocks.push({kind:'heading',text:heading[2]!});continue}
    const item = /^\s*[-*]\s+(.+)$/.exec(line)
    blocks.push({ kind: item ? 'item' : 'text', text: item ? item[1]! : line })
  }
  return blocks
}
