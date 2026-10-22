<script setup lang="ts">
import { computed,ref,watch } from 'vue'
import { useRoute,useRouter } from 'vue-router'
import { ElMessage,ElMessageBox } from 'element-plus'
import { api,errorText } from '../api/http'
import { kindLabel } from '../api/business'
import { dateTime,sourceRoute } from '../utils/format'
import { useAuthStore } from '../stores/auth'
import type { Row } from '../types'
import StatusTag from '../components/StatusTag.vue'
const route=useRoute(),router=useRouter(),auth=useAuthStore(),row=ref<Row>(),error=ref(''),loading=ref(false),busy=ref(false)
const source=computed(()=>{if(!row.value)return null;const t=sourceRoute(row.value.sourceType,row.value.sourceId);return t&&auth.can(t.permission)?t.path:null})
async function load(){loading.value=true;error.value='';try{row.value=(await api.get(`/stock-documents/${route.params.id}`)).data}catch(e){error.value=errorText(e)}finally{loading.value=false}}
async function execute(){if(busy.value||!row.value)return;busy.value=true;try{await ElMessageBox.confirm(`入库仓库：${row.value.warehouseName}。全部数量：${row.value.items.map((i:Row)=>`${i.materialName} ${i.quantity} ${i.unit}`).join('；')}。整单入库后增加成品库存，不能撤回或反冲。`,'执行整机入库',{confirmButtonText:'确认整单入库',cancelButtonText:'返回',type:'warning'})}catch{busy.value=false;return}try{await api.post(`/stock-documents/${row.value.id}/execute`,{version:row.value.version});ElMessage.success('整机已入库，生产工单已完成');await load()}catch(e){const text=errorText(e);if((e as {response?:{status?:number}}).response?.status===409)await load();error.value=text}finally{busy.value=false}}
watch(()=>route.params.id,()=>{row.value=undefined;void load()},{immediate:true})
</script>
<template>
 <div class="page-header"><div><h1>{{row?.documentNo||'库存单详情'}}</h1></div><div><el-button @click="router.push(`/wms/${row?.direction==='OUT'?'outbound':'inbound'}`)">返回列表</el-button><el-button v-if="row?.status==='PENDING'&&row?.kind==='PRODUCTION_RECEIPT'&&auth.can('wms:receipt:execute')" type="primary" :loading="busy" @click="execute">执行整机入库</el-button></div></div>
 <div v-if="error" class="error-box" role="alert">{{error}} <el-button link @click="load">重试</el-button></div><el-skeleton v-if="loading&&!row" :rows="7" animated/>
 <template v-if="row"><section class="panel"><el-descriptions :column="3" border><el-descriptions-item label="状态"><StatusTag :value="row.status"/></el-descriptions-item><el-descriptions-item label="仓库">{{row.warehouseName}}</el-descriptions-item><el-descriptions-item label="方向">{{row.direction==='IN'?'入库':'出库'}}</el-descriptions-item><el-descriptions-item label="业务来源"><router-link v-if="source" :to="source">{{row.sourceNo}}</router-link><span v-else>{{row.sourceNo}}</span></el-descriptions-item><el-descriptions-item label="创建时间">{{dateTime(row.createdAt)}}</el-descriptions-item><el-descriptions-item label="执行时间">{{dateTime(row.executedAt)}}</el-descriptions-item><el-descriptions-item label="执行人">{{row.executedByName||'—'}}</el-descriptions-item></el-descriptions><p v-if="row.status==='PENDING'" class="muted">待执行整机入库；报工尚未增加整机库存。</p></section><section class="panel"><h2>全部物料明细</h2><el-table :data="row.items"><el-table-column prop="materialCode" label="物料编码" min-width="150"/><el-table-column prop="materialName" label="物料名称" min-width="200"/><el-table-column prop="unit" label="单位" width="100"/><el-table-column prop="quantity" label="数量" align="right" width="150"/></el-table></section></template>
</template>
