<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {conversationId,scopeText} from '../api/ai-context'
import {analysisExamples,publicStage,reportSections,executionText,createTurnGate,type Execution} from '../api/ai-presentation'
import {useAuthStore} from '../stores/auth'
import AiReportContent from '../components/AiReportContent.vue'
import { cancelConversation, createConversation, getConversation, listConversations, streamMessage, type AiMessage, type ConversationSummary } from '../api/ai'
import { errorText } from '../api/http'
const history = ref<ConversationSummary[]>([]), currentId = ref(''), messages = ref<AiMessage[]>([])
const input = ref(''), error = ref(''), stage = ref(''), pendingQuestion = ref(''), busy = ref(false), loading = ref(false), cancelling = ref(false)
const route=useRoute(),router=useRouter(),currentScope=ref<Record<string,unknown>>({}),serverRunning=ref(false)
const scopeDescription=computed(()=>scopeText(currentScope.value))
const auth=useAuthStore(),examples=computed(()=>analysisExamples(auth.user?.permissions||[]))
const reportContent=ref(''),execution=ref<Execution>(),steps=ref<string[]>([]),retryAllowed=ref(false),lastQuestion=ref(''),scopeChange=ref('')
const report=computed(()=>reportSections(reportContent.value))
const hasReport=computed(()=>reportContent.value.includes('|')||reportContent.value.includes('### 已查询确认'))
const historyVisible = ref(false), historyCollapsed = ref(false), historyQuery = ref(''), conversationBody = ref<HTMLElement>(), nearBottom = ref(true)
const visibleHistory = computed(() => history.value.filter(item => (item.title || '未命名分析').toLowerCase().includes(historyQuery.value.trim().toLowerCase())))
const latestAssistantId = computed(() => messages.value.filter(message => message.role === 'assistant').at(-1)?.id)
function trackScroll() { const el = conversationBody.value; if (el) nearBottom.value = el.scrollHeight - el.scrollTop - el.clientHeight < 100 }
async function scrollToLatest() { await nextTick(); const el = conversationBody.value; if (el) el.scrollTop = el.scrollHeight; nearBottom.value = true }
async function scrollToLastMessage() {
  await nextTick()
  const el = conversationBody.value, last = el?.querySelector<HTMLElement>('.message:last-child')
  if (el && last) { el.scrollTop += last.getBoundingClientRect().top - el.getBoundingClientRect().top - 20; trackScroll() }
}
function chooseExample(example: string) { input.value = example; document.getElementById('analysis-question')?.focus() }
function composerKeydown(event: KeyboardEvent) { if (event.key === 'Enter' && (event.ctrlKey || event.metaKey) && !event.isComposing) { event.preventDefault(); void send() } }
watch(() => messages.value.length, () => { if (nearBottom.value) void scrollToLastMessage() })
let controller: AbortController | undefined, localId = 0
const historyStatus:Record<string,string>={IDLE:'待分析',RUNNING:'分析中',CLARIFICATION:'待补充',COMPLETED:'已完成',ERROR:'失败',CANCELLED:'已取消'}
const canSend = computed(() => input.value.trim().length > 0 && !busy.value && !serverRunning.value && !loading.value)
async function refreshHistory() { history.value = await listConversations() }
async function open(id: string) {
  if (busy.value) return
  loading.value = true; error.value = ''
  try { const conversation = await getConversation(id); currentId.value = id; messages.value = conversation.messages; pendingQuestion.value = conversation.pendingQuestion || ''; currentScope.value=conversation.context||{};serverRunning.value=conversation.status==='RUNNING'; stage.value = serverRunning.value?'原任务仍在运行，请刷新状态或先取消':'';reportContent.value=['COMPLETED','CLARIFICATION'].includes(conversation.status)?conversation.messages.filter(m=>m.role==='assistant').at(-1)?.content||'':'';execution.value=undefined;steps.value=[];scopeChange.value='';retryAllowed.value=conversation.status==='ERROR';lastQuestion.value=conversation.messages.filter(m=>m.role==='user').at(-1)?.content||'';await router.replace({query:{...route.query,conversation:id}}) }
  catch (cause) { error.value = errorText(cause) }
  finally { loading.value = false; historyVisible.value = false; void scrollToLastMessage() }
}
function newChat() { if (busy.value || loading.value) return; currentId.value = ''; messages.value = []; pendingQuestion.value = ''; currentScope.value={};serverRunning.value=false; stage.value = ''; error.value = ''; input.value = '';reportContent.value='';execution.value=undefined;steps.value=[];retryAllowed.value=false;scopeChange.value='';historyVisible.value=false;const query={...route.query};delete query.conversation;void router.replace({query}) }
async function send() {
  if (!canSend.value) return
  nearBottom.value = true
  const text = input.value.trim(); busy.value = true; error.value = ''; stage.value = '正在连接分析服务'; pendingQuestion.value = ''
  reportContent.value='';execution.value=undefined;steps.value=[];retryAllowed.value=false;lastQuestion.value=text
  controller = new AbortController()
  let completed = false
  const publish=createTurnGate()
  try {
    if (!currentId.value) { const conversation = await createConversation('新的分析会话'); currentId.value = conversation.id;await router.replace({query:{...route.query,conversation:conversation.id}}) }
    messages.value.push({ id: `local-${++localId}`, role: 'user', content: text }); input.value = ''
    await streamMessage(currentId.value, text, controller.signal, event => {
      const terminal=publish(event)
      if(terminal){messages.value.push({id:`local-${++localId}`,role:'assistant',content:terminal.content});reportContent.value=terminal.content;if(terminal.clarification){pendingQuestion.value=terminal.content;if(currentScope.value.question==='evidence')retryAllowed.value=true}}
      if(event.data.context){const previous=scopeText(currentScope.value),next=scopeText(event.data.context);scopeChange.value=previous===next?'沿用已确认范围，本轮重新查询。':`修改前：${previous||'未指定'}。本轮：${next||'待澄清'}。`;currentScope.value=event.data.context}
      if(event.data.execution)execution.value=event.data.execution
      if (event.event === 'stage') {stage.value=publicStage(event.data.node);if(steps.value.at(-1)!==stage.value)steps.value.push(stage.value)}
      if (event.event === 'error') {error.value=event.data.message||'分析失败，请检查服务后重试';retryAllowed.value=true}
      if (event.event === 'done') { completed = true;serverRunning.value=false; stage.value = event.data.status?.toUpperCase() === 'CANCELLED' ? '已取消' : event.data.status?.toUpperCase()==='ERROR'?'分析失败，未形成完整报告':pendingQuestion.value ? '等待补充信息' : '本次分析已结束' }
    })
    if (!completed && !error.value) {error.value = '连接已中断，分析状态尚未确认。请打开历史会话查看后再决定是否发送。';retryAllowed.value=true}
  } catch (cause) {
    if (cause instanceof Error && cause.name === 'AbortError') stage.value = '已停止接收'
    else {error.value = errorText(cause);retryAllowed.value=true}
  } finally {
    busy.value = false; controller = undefined
    try { await refreshHistory();if(currentId.value){const detail=await getConversation(currentId.value);serverRunning.value=detail.status==='RUNNING';currentScope.value=detail.context||currentScope.value} } catch (cause) { if (!error.value) error.value = errorText(cause) }
  }
}
function retry(){if(busy.value||serverRunning.value||!lastQuestion.value)return;input.value=lastQuestion.value;void send()}
async function cancel() {
  if (!currentId.value || cancelling.value) return
  cancelling.value = true
  try { await cancelConversation(currentId.value); controller?.abort();serverRunning.value=false; stage.value = '已取消分析' }
  catch (cause) { error.value = errorText(cause) }
  finally { cancelling.value = false }
}
onMounted(async () => { loading.value = true; try { await refreshHistory();const id=conversationId(route.query.conversation);if(id)await open(id) } catch (cause) { error.value = errorText(cause) } finally { loading.value = false } })
watch(()=>route.query.conversation,async value=>{const id=conversationId(value);if(!busy.value&&!loading.value){if(id&&id!==currentId.value)await open(id);else if(!id&&currentId.value)newChat()}})
onBeforeUnmount(() => controller?.abort())
</script>

<template>
  <div class="ai-workspace">
    <div class="ai-layout" :class="{ 'show-history': historyVisible, 'history-collapsed': historyCollapsed }">
      <aside id="analysis-history" class="history-panel" aria-label="历史会话">
        
        <el-button class="new-chat" :disabled="busy || loading" @click="newChat"><span aria-hidden="true">＋</span> 新建会话</el-button>
        <el-input v-model="historyQuery" placeholder="搜索会话" aria-label="搜索历史会话" clearable class="history-search"/>
        <div class="history-list">
          <p v-if="loading" class="history-empty" role="status">正在加载会话…</p>
          <p v-else-if="!visibleHistory.length" class="history-empty">{{ historyQuery ? '没有匹配的会话' : '暂无历史会话，开始一个新问题。' }}</p>
          <button v-for="item in visibleHistory" :key="item.id" class="history-item" :class="{ selected: currentId === item.id }" :aria-current="currentId === item.id ? 'true' : undefined" :disabled="busy || loading" @click="open(item.id)">
            <strong>{{ item.title || '未命名分析' }}</strong><small v-if="['RUNNING','CLARIFICATION','ERROR'].includes(item.status)"><span class="history-status-dot" :class="`status-${item.status.toLowerCase()}`"/>{{ historyStatus[item.status] || item.status }}</small>
          </button>
        </div>
      </aside>
      <section class="conversation-panel" :class="{'is-empty': !messages.length}" aria-label="分析对话" :aria-busy="busy">
        <header class="conversation-header">
          <h1>AI 智能分析</h1><div class="chat-header-actions">
            <el-button class="desktop-history-toggle" text :aria-expanded="!historyCollapsed" aria-controls="analysis-history" @click="historyCollapsed = !historyCollapsed">{{ historyCollapsed ? '历史会话' : '收起会话' }}</el-button>
            <el-button class="history-toggle" text :aria-expanded="historyVisible" aria-controls="analysis-history" @click="historyVisible = !historyVisible">{{ historyVisible ? '收起会话' : '历史会话' }}</el-button>
          </div>
          <details v-if="currentId" class="query-details">
            <summary>范围与执行详情</summary>
            <div class="query-popover">
              <p v-if="scopeDescription" aria-label="当前查询条件">{{scopeDescription}}。每次续问重新查询与鉴权。</p>
              <p v-if="scopeChange" aria-label="范围变更">{{scopeChange}}</p>
              <ol v-if="steps.length" class="execution-steps" aria-label="执行阶段"><li v-for="(step,index) in steps" :key="index"><span>{{index+1}}</span>{{step}}</li></ol>
              <p class="execution-statistics" aria-label="执行统计">{{executionText(execution)}}</p>
            </div>
          </details>
        </header>
        <div ref="conversationBody" class="conversation-body" @scroll="trackScroll">
          <div v-if="messages.length" class="messages" role="log" aria-label="对话消息" aria-live="polite" aria-relevant="additions">
            <article v-for="message in messages" :key="message.id" class="message" :class="{ user: message.role === 'user' }">
              <div class="message-role"><span v-if="message.role !== 'user'" class="assistant-mark" aria-hidden="true">AI</span>{{ message.role === 'user' ? '你' : '分析助手' }}</div>
              <div class="message-content">
                <section v-if="message.id === latestAssistantId && hasReport" class="analysis-result" aria-label="本次分析结果">
                  <details open><summary>查询结果与口径</summary><AiReportContent :content="report.overview"/></details>
                  <details v-if="report.evidence" open><summary>事实与证据来源</summary><AiReportContent :content="report.evidence"/></details>
                  <details v-if="report.hypotheses" open class="verification-section"><summary>需核实事项</summary><AiReportContent :content="report.hypotheses"/></details>
                  <details v-if="report.actions" open><summary>建议采取的行动</summary><AiReportContent :content="report.actions"/></details>
                  <div v-if="report.unanswered || currentScope.question==='evidence'" class="partial-result" role="note"><strong>部分结果 / 尚未回答</strong><AiReportContent :content="report.unanswered||'必要证据尚未补齐，请按澄清提示补充或重试。'"/></div>
                </section>
                <AiReportContent v-else :content="message.content"/>
              </div>
            </article>
          </div>
        </div>
        <div class="composer-dock">
          <h2 v-if="!messages.length" class="welcome-title">有什么可以帮你？</h2>
          <button v-if="!nearBottom && messages.length" class="jump-latest" @click="scrollToLatest">↓ 回到最新消息</button>
          <div v-if="error" class="error-box" role="alert">{{ error }}</div>
          <div v-if="stage || serverRunning" class="stage" role="status"><span class="status-dot" :class="{working: busy || serverRunning}"/><span>{{ busy ? '分析进行中 · ' : '' }}{{stage || '原任务仍在运行'}}</span><el-button v-if="serverRunning && !busy" size="small" :disabled="loading" @click="open(currentId)">刷新任务状态</el-button></div>
          <div v-if="retryAllowed" class="retry-notice"><span>本次未形成完整报告。重试将重新查询并校验权限。</span><el-button size="small" :disabled="busy||serverRunning||!lastQuestion" @click="retry">重试本次问题</el-button></div>
          <div v-if="pendingQuestion" class="clarification"><strong>等待你的补充</strong></div>
          <form class="composer" @submit.prevent="send">
            <label class="sr-only" for="analysis-question">{{ pendingQuestion ? '补充说明' : '你的问题' }}</label>
            <el-input id="analysis-question" v-model="input" type="textarea" :autosize="{minRows:2,maxRows:5}" maxlength="4000" :disabled="busy || loading" :placeholder="pendingQuestion ? '补充信息后，继续这次分析…' : '询问业务问题'" @keydown="composerKeydown"/>
            <div class="composer-actions"><el-button v-if="busy || serverRunning" :loading="cancelling" :disabled="!currentId" @click="cancel">取消分析</el-button><el-button class="send-button" type="primary" native-type="submit" :aria-label="pendingQuestion ? '继续分析' : '发送问题'" :title="pendingQuestion ? '继续分析' : '发送问题（Ctrl / ⌘ + Enter）'" :disabled="!canSend" :loading="busy"><svg v-if="!busy" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><path d="M12 19V5m-6 6 6-6 6 6"/></svg></el-button></div>
          </form>
          <div v-if="!messages.length" class="example-grid"><button v-for="example in examples.slice(0,3)" :key="example" :disabled="busy || loading" @click="chooseExample(example)">{{ example }}</button></div>
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped src="./ai-analysis.css"></style>
