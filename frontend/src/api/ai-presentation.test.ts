import {describe,it,expect} from 'vitest'
import {analysisExamples,publicStage,reportSections,executionText,createTurnGate} from './ai-presentation'
describe('analysis presentation',()=>{
 it('publishes only a matching success terminal, never result before done or after error',()=>{
  const gate=createTurnGate()
  expect(gate({event:'result',data:{content:'120.01'}})).toBeUndefined()
  expect(gate({event:'error',data:{message:'断流'}})).toBeUndefined()
  expect(gate({event:'done',data:{status:'ERROR'}})).toBeUndefined()
  const success=createTurnGate();expect(success({event:'result',data:{content:'120.01'}})).toBeUndefined()
  expect(success({event:'done',data:{status:'completed'}})).toEqual({content:'120.01',clarification:false})
  const eof=createTurnGate();expect(eof({event:'result',data:{content:'未提交'}})).toBeUndefined()
 })
 it('requires both inventory and production reads for shortage examples',()=>{
  expect(analysisExamples(['mes:production:read'])).toEqual([])
  expect(analysisExamples(['wms:stock:read'])).not.toEqual(expect.arrayContaining([expect.stringContaining('工单')]))
  expect(analysisExamples(['erp:sales:read'])).toEqual(expect.arrayContaining([expect.stringContaining('客户')]))
 })
 it('does not display arbitrary upstream reasoning as a stage',()=>{
  expect(publicStage('Reflection')).toBe('核对证据完整性')
  expect(publicStage('chain_of_thought')).toBe('处理查询')
 })
 it('splits evidence from hypotheses and actions without changing numbers',()=>{
  const r=reportSections('销售分析\n| 指标 | 本期 |\n|---|---|\n|销售额|120.01|\n### 已查询确认的事实与证据\n来源甲\n### 仍需核实的可能解释\n待核实缺货\n### 建议采取的行动\n核对客户11\n### 未回答的问题\n趋势工具失败')
  expect(r.overview).toContain('120.01');expect(r.evidence).toContain('来源甲');expect(r.hypotheses).toContain('待核实缺货');expect(r.actions).toContain('客户11');expect(r.unanswered).toContain('工具失败')
 })
 it('never invents token totals when usage is absent or partial',()=>{
  expect(executionText({toolCalls:2,modelCalls:1,elapsedMs:500,usage:null})).toContain('未返回用量')
  expect(executionText({toolCalls:2,modelCalls:1,elapsedMs:500,usage:{prompt_tokens:4}})).not.toContain('total_tokens')
 })
})
