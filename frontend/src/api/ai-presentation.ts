export function analysisExamples(permissions:string[]):string[]{
 const p=new Set(permissions),items:string[]=[]
 if(p.has('erp:sales:read'))items.push('分析最近30天的销售情况','上个月客户销售排行前5名')
 if(p.has('wms:stock:read'))items.push('原料仓哪些物料库存不足？')
 if(p.has('mes:production:read')&&p.has('wms:stock:read'))items.push('这张工单还缺哪些零部件？')
 if(p.has('wms:document:read'))items.push('目前有哪些成品等待入库？')
 return items
}
export function publicStage(node?:string):string{return ({Decision:'模型决策',Tools:'执行受控工具',Gateway:'连接分析服务',Planner:'确认范围与查询计划',Context:'更新查询条件',Executor:'读取受控业务数据',TextToSQL:'读取预设统计',Analyzer:'计算并校验指标',Reflection:'核对证据完整性',Reporter:'组织事实与建议',Clarification:'等待补充信息',DimensionResolver:'确认客户或商品',DimensionExecutor:'查询对象销售',Operations:'查询库存与工单'} as Record<string,string>)[node||'']||'处理查询'}
export function reportSections(content:string){
 const sections={overview:'',evidence:'',hypotheses:'',actions:'',unanswered:''};let section:keyof typeof sections='overview'
 for(const line of content.split('\n')){
  const title=line.replace(/^#+\s*/,'').replace(/[:：].*$/,'').trim()
  const next=/^已查询确认的事实/.test(title)?'evidence':/^仍需核实/.test(title)?'hypotheses':/^建议采取的行动/.test(title)?'actions':/^(?:未回答|尚未回答)/.test(title)?'unanswered':null
  if(next){section=next;const inline=line.indexOf('：');if(inline>=0)sections[section]+=line.slice(inline+1)+'\n';continue}
  sections[section]+=line+'\n'
 }
 return sections
}
export interface Execution {toolCalls:number;modelCalls:number;elapsedMs:number;usage?:Record<string,number>|null;usageReports?:number}
export function executionText(e?:Execution):string{
 if(!e)return '历史记录未保存执行统计；不推算用量或成本。'
 const usage=e.usage?Object.entries(e.usage).filter(([k,v])=>['prompt_tokens','completion_tokens','total_tokens'].includes(k)&&Number.isSafeInteger(v)&&v>=0).map(([k,v])=>`${k}=${v}`).join('，'):''
 return `本轮 ${(e.elapsedMs/1000).toFixed(2)}秒 · 工具调用 ${e.toolCalls} 次 · 模型请求 ${e.modelCalls} 次。${usage?`提供方已返回用量（${e.usageReports??'未知'}次响应）：${usage}。`:'提供方未返回用量，不估算token或成本。'}`
}
import type {AiEvent} from './ai'
export function createTurnGate(){
 let pending:AiEvent|undefined,failed=false
 return (event:AiEvent):{content:string;clarification:boolean}|undefined=>{
  if(event.event==='error'){failed=true;pending=undefined}
  if(event.event==='result'||event.event==='clarification')pending=event
  if(event.event==='done'){
   const status=event.data.status?.toUpperCase(),expected=pending?.event==='result'?'COMPLETED':'CLARIFICATION'
   if(!failed&&pending?.data.content&&status===expected)return {content:pending.data.content,clarification:expected==='CLARIFICATION'}
  }
  return undefined
 }
}
