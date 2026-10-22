<script setup lang="ts">
import { ref, watch } from 'vue'
import { api, errorText, loadPage } from '../api/http'
import type { Row } from '../types'
const props = withDefaults(defineProps<{modelValue?:string; resourcePath:string; labelField?:string; params?:Record<string,unknown>; placeholder?:string; disabled?:boolean; activeOnly?:boolean}>(), { modelValue:'', labelField:'name', placeholder:'请选择', activeOnly:true })
const emit = defineEmits<{ 'update:modelValue':[value:string]; change:[value:string, row:Row|undefined] }>()
const rows = ref<Row[]>([]), selected = ref<Row>(), loading = ref(false), error = ref(''), page = ref(1), total = ref(0), query = ref('')
let request = 0
async function fetchRows(more = false) {
 const token = ++request; loading.value = true; error.value = ''
 try { const result = await loadPage<Row>(props.resourcePath, { ...props.params, enabled:props.activeOnly?true:undefined, q:query.value || undefined, page:more?page.value+1:1, size:100 }); if(token!==request)return; page.value=more?page.value+1:1; total.value=Number(result.total); rows.value=more?[...rows.value,...result.items]:result.items }
 catch(e) { if(token===request) error.value=errorText(e) } finally { if(token===request)loading.value=false }
}
async function hydrate() { if(!props.modelValue || rows.value.some(r=>r.id===props.modelValue))return; try { selected.value=(await api.get(`${props.resourcePath}/${props.modelValue}`)).data } catch { selected.value=undefined } }
function change(value:string) { emit('update:modelValue',value||''); emit('change',value,rows.value.find(r=>r.id===value)||selected.value) }
watch(()=>[props.resourcePath,JSON.stringify(props.params)],()=>{ query.value=''; rows.value=[]; selected.value=undefined; void fetchRows(); void hydrate() },{immediate:true})
watch(()=>props.modelValue,hydrate)
</script>
<template>
 <el-select :model-value="modelValue" :aria-label="placeholder" :placeholder="placeholder" :disabled="disabled" filterable remote clearable :remote-method="(q:string)=>{query=q;fetchRows()}" :loading="loading" style="width:100%" @change="change">
  <el-option v-if="selected && !rows.some(r=>r.id===selected?.id)" :value="selected.id" :label="`${selected.code || ''} ${selected[labelField]}${selected.enabled===false?'（已停用）':''}`" disabled />
  <el-option v-for="r in rows" :key="r.id" :value="r.id" :label="`${r.code || ''} ${r[labelField] || r.id}${r.unit?' · '+r.unit:''}${r.enabled===false?'（已停用）':''}`" :disabled="activeOnly&&r.enabled===false" />
  <template #footer><span v-if="error" class="error-box">{{error}} <el-button link @click="fetchRows()">重试</el-button></span><el-button v-else-if="rows.length<total" link :loading="loading" @click="fetchRows(true)">加载更多（{{rows.length}} / {{total}}）</el-button><span v-else class="muted">共 {{total}} 项</span></template>
 </el-select>
</template>
