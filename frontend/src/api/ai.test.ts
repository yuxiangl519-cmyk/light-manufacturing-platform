import { describe, expect, it, vi } from 'vitest'
import { createSseParser, markdownBlocks, streamMessage, type AiEvent } from './ai'
import {api} from './http'

describe('AI event stream', () => {
  it('explains daily quota and rate limits without exposing raw server errors or retrying',async()=>{
    vi.spyOn(api,'get').mockResolvedValue({data:{token:'test-csrf'}})
    try {
      for(const [code,text] of [['AI_DAILY_LIMIT','明日零点'],['AI_RATE_LIMIT','过于频繁'],['AI_CAPACITY','任务较多'],['UNKNOWN','限流']]) {
        const fetcher=vi.fn().mockResolvedValue(new Response(JSON.stringify({code,message:'private backend detail'}),{status:429}))
        vi.stubGlobal('fetch',fetcher)
        await expect(streamMessage('1','测试',new AbortController().signal,()=>{})).rejects.toThrow(text)
        expect(fetcher).toHaveBeenCalledTimes(1)
      }
    } finally {vi.restoreAllMocks();vi.unstubAllGlobals()}
  })
  it('bounds a stalled stream without automatically resending the question',async()=>{
    vi.spyOn(api,'get').mockResolvedValue({data:{token:'test-csrf'}})
    const fetcher=vi.fn((_url,options)=>new Promise((_resolve,reject)=>options.signal.addEventListener('abort',()=>reject(new DOMException('abort','AbortError')))))
    vi.stubGlobal('fetch',fetcher)
    try{await expect(streamMessage('1','测试',new AbortController().signal,()=>{},10)).rejects.toThrow('分析等待超时');expect(fetcher).toHaveBeenCalledTimes(1)}finally{vi.restoreAllMocks();vi.unstubAllGlobals()}
  })
  it('rejects oversized unfinished frames before buffering indefinitely',()=>{expect(()=>createSseParser(()=>{})(new TextEncoder().encode('data: '+ 'x'.repeat(40001)))).toThrow('分析响应过大')})
  it('preserves split UTF-8, CRLF, multiline JSON and multiple events', () => {
    const events: AiEvent[] = [], parse = createSseParser(event => events.push(event))
    const bytes = new TextEncoder().encode(': heartbeat\r\nevent: stage\r\ndata: {"message":\r\ndata: "正在分析"}\r\n\r\nevent: done\ndata: {"status":"COMPLETED"}\n\n')
    for (const byte of bytes) parse(new Uint8Array([byte]))
    parse()
    expect(events).toEqual([{ event: 'stage', data: { message: '正在分析' } }, { event: 'done', data: { status: 'COMPLETED' } }])
  })
  it('does not deliver an incomplete event or silently accept malformed JSON', () => {
    const events: AiEvent[] = [], parse = createSseParser(event => events.push(event))
    parse(new TextEncoder().encode('event: result\ndata: {"content":"partial"}\n'))
    expect(events).toEqual([])
    expect(() => createSseParser(() => {})(new TextEncoder().encode('data: invalid\n\n'))).toThrow()
  })
})
describe('safe minimal Markdown', () => {
  it('parses report tables without interpreting HTML cells',()=>{expect(markdownBlocks('| 指标 | 金额 |\n|---|---:|\n| <img src=x> | 0.00 |')).toEqual([{kind:'table',headers:['指标','金额'],rows:[['<img src=x>','0.00']]}])})
  it('keeps HTML and script URLs as text for Vue interpolation', () => {
    expect(markdownBlocks('# 标题\n- <img src=x onerror=alert(1)>\n<script>alert(1)</script>\n[x](javascript:alert(1))')).toEqual([
      { kind: 'heading', text: '标题' }, { kind: 'item', text: '<img src=x onerror=alert(1)>' },
      { kind: 'text', text: '<script>alert(1)</script>' }, { kind: 'text', text: '[x](javascript:alert(1))' },
    ])
  })
})
