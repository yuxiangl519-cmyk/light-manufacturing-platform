<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api,errorText,loadPage } from '../api/http'
import { businessConfig,type Resource } from '../api/business'
import { useAuthStore } from '../stores/auth'
import { dateTime,money,statusLabel } from '../utils/format'
import type { Row } from '../types'
import StatusTag from '../components/StatusTag.vue'
import BusinessForm from '../components/BusinessForm.vue'
import RemoteSelect from '../components/RemoteSelect.vue'
const route=useRoute(),router=useRouter(),auth=useAuthStore(),resource=computed(()=>route.meta.resource as Resource),config=computed(()=>businessConfig[resource.value])
const rows=ref<Row[]>([]),loading=ref(false),error=ref(''),q=ref(''),status=ref(''),page=ref(1),total=ref(0),drawer=ref(false),initial=ref<Row>(),warehouseId=ref(''),dates=ref<string[]>([])
async function load(){loading.value=true;error.value='';try{const result=await loadPage<Row>(config.value.path,{q:q.value||undefined,status:status.value||undefined,warehouseId:warehouseId.value||undefined,from:dates.value?.[0]?new Date(dates.value[0]).toISOString():undefined,to:dates.value?.[1]?new Date(dates.value[1]).toISOString():undefined,page:page.value,size:20});rows.value=result.items;total.value=Number(result.total)}catch(e){error.value=errorText(e)}finally{loading.value=false}}
async function edit(row:Row){try{initial.value=(await api.get(`${config.value.path}/${row.id}`)).data;drawer.value=true}catch(e){error.value=errorText(e)}}
watch(()=>[resource.value,route.query.status],()=>{q.value='';status.value=typeof route.query.status==='string'&&(config.value.statuses as readonly string[]).includes(route.query.status)?route.query.status:'';warehouseId.value='';dates.value=[];page.value=1;drawer.value=false;void load()},{immediate:true})
</script>
<template>
 <div class="page-header"><div><h1>{{config.title}}</h1></div><el-button v-if="auth.can(config.permission+':write')" type="primary" @click="initial=undefined;drawer=true">新建{{config.title}}</el-button></div>
 <section class="panel"><form class="filters" @submit.prevent="page=1;load()"><el-input v-model="q" placeholder="搜索单号或名称" aria-label="搜索单号或名称" clearable /><el-select v-model="status" placeholder="全部状态" aria-label="状态" clearable><el-option v-for="s in config.statuses" :key="s" :label="statusLabel(s)" :value="s" /></el-select><RemoteSelect v-if="auth.can('master:warehouse:read')" v-model="warehouseId" resource-path="/warehouses" :active-only="false" placeholder="全部仓库"/><el-date-picker v-model="dates" type="datetimerange" start-placeholder="起始时间" end-placeholder="结束时间" aria-label="单据创建日期区间"/><el-button type="primary" native-type="submit">查询</el-button><el-button @click="q='';status='';warehouseId='';dates=[];page=1;load()">重置</el-button></form>
 <div v-if="error" class="error-box" role="alert">{{error}} <el-button link @click="load">重试</el-button></div>
 <el-table v-loading="loading" :data="rows" :empty-text="`暂无${config.title}`"><el-table-column prop="orderNo" label="单号" min-width="190" /><el-table-column v-if="resource==='production'" prop="productName" label="成品" min-width="170" /><el-table-column v-else :prop="resource==='purchase'?'supplierName':'customerName'" :label="resource==='purchase'?'供应商':'客户'" min-width="160" /><el-table-column :prop="resource==='production'?'issueWarehouseName':'warehouseName'" :label="resource==='production'?'领料仓库':'仓库'" min-width="140" /><el-table-column v-if="resource==='production'" prop="plannedQuantity" label="计划数量" align="right" width="110"/><el-table-column v-else label="总金额" align="right" width="140"><template #default="{row}">{{money(row.totalAmount)}}</template></el-table-column><el-table-column label="状态" width="110"><template #default="{row}"><StatusTag :value="row.status" /></template></el-table-column><el-table-column label="创建时间" min-width="175"><template #default="{row}">{{dateTime(row.createdAt)}}</template></el-table-column><el-table-column label="操作" fixed="right" width="130"><template #default="{row}"><el-button link type="primary" @click="router.push(`${config.route}/${row.id}`)">查看</el-button><el-button v-if="row.status==='DRAFT' && auth.can(config.permission+':write')" link @click="edit(row)">编辑</el-button></template></el-table-column></el-table>
 <div class="table-footer"><el-pagination v-model:current-page="page" :total="total" :page-size="20" layout="total, prev, pager, next" @current-change="load" /></div></section>
 <el-drawer v-model="drawer" :title="`${initial?'编辑':'新建'}${config.title}`" size="min(620px, 100%)" destroy-on-close><BusinessForm :resource="resource" :initial="initial" @cancel="drawer=false" @saved="drawer=false;load()" /></el-drawer>
</template>
