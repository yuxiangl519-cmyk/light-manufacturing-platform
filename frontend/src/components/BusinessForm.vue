<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import RemoteSelect from './RemoteSelect.vue'
import { api, errorText } from '../api/http'
import { businessConfig, type Resource } from '../api/business'
import type { Row } from '../types'
import { validMoney } from '../utils/format'
const props=defineProps<{resource:Resource; initial?:Row}>()
const emit=defineEmits<{saved:[row:Row];cancel:[]}>()
const form=reactive<Row>({}), busy=ref(false), error=ref('')
watch(()=>props.initial,()=>{ Object.keys(form).forEach(k=>delete form[k]); Object.assign(form,{supplierId:'',customerId:'',warehouseId:'',productMaterialId:'',bomId:'',plannedQuantity:'',issueWarehouseId:'',receiptWarehouseId:'',remark:'',items:[{materialId:'',quantity:'',unitPrice:''}]}, props.initial?{...props.initial,items:props.initial.items?.map((r:Row)=>({...r}))||[]}:{}); error.value='' },{immediate:true})
function validate() {
 const keys=props.resource==='production'?['productMaterialId','bomId','plannedQuantity','issueWarehouseId','receiptWarehouseId']:[props.resource==='purchase'?'supplierId':'customerId','warehouseId']
 if(keys.some(k=>!form[k]))return '请填写全部必填字段'
 if(props.resource==='production')return /^[1-9]\d{0,11}$/.test(form.plannedQuantity)?'':'计划数量须为最多12位的正整数'
 if(!form.items.length || form.items.length>100)return '明细须为1～100条'
 if(new Set(form.items.map((i:Row)=>i.materialId)).size!==form.items.length)return '不能重复选择同一物料'
 for(const i of form.items) { if(!i.materialId || !/^[1-9]\d{0,11}$/.test(i.quantity))return '请选择物料，数量须为最多12位正整数'; if(!validMoney(i.unitPrice))return '人民币含税单价须为非负金额，最多两位小数，不允许前导零' }
 return ''
}
async function save() {
 if(busy.value)return; error.value=validate(); if(error.value)return; busy.value=true
 const body:Row=props.resource==='production'?{productMaterialId:form.productMaterialId,bomId:form.bomId,plannedQuantity:form.plannedQuantity,issueWarehouseId:form.issueWarehouseId,receiptWarehouseId:form.receiptWarehouseId,remark:form.remark}:{[props.resource==='purchase'?'supplierId':'customerId']:form[props.resource==='purchase'?'supplierId':'customerId'],warehouseId:form.warehouseId,remark:form.remark,items:form.items.map((i:Row)=>({materialId:i.materialId,quantity:i.quantity,unitPrice:i.unitPrice}))}
 if(props.initial)body.version=props.initial.version
 try { const result=props.initial?await api.put(`${businessConfig[props.resource].path}/${props.initial.id}`,body):await api.post(businessConfig[props.resource].path,body); ElMessage.success('草稿已保存'); emit('saved',result.data) }catch(e){error.value=errorText(e)}finally{busy.value=false}
}
</script>
<template>
 <el-form label-position="top" @submit.prevent="save">
  <template v-if="resource==='production'">
   <el-form-item label="成品物料（必填）"><RemoteSelect v-model="form.productMaterialId" resource-path="/materials" :params="{type:'FINISHED_GOOD'}" placeholder="选择成品物料" @change="form.bomId=''" /></el-form-item>
   <el-form-item label="BOM（必填）"><RemoteSelect v-model="form.bomId" resource-path="/boms" :params="{productMaterialId:form.productMaterialId || undefined}" :disabled="!form.productMaterialId" placeholder="选择该成品的BOM" /></el-form-item>
   <el-form-item label="计划生产数量（必填）"><el-input v-model="form.plannedQuantity" inputmode="numeric" aria-label="计划生产数量" placeholder="输入正整数" /></el-form-item>
   <el-form-item label="领料仓库（必填）"><RemoteSelect v-model="form.issueWarehouseId" resource-path="/warehouses" placeholder="选择领料仓库" /></el-form-item>
   <el-form-item label="成品入库仓库（必填）"><RemoteSelect v-model="form.receiptWarehouseId" resource-path="/warehouses" placeholder="选择成品入库仓库" /></el-form-item>
  </template>
  <template v-else>
   <el-form-item v-if="resource==='purchase'" label="供应商（必填）"><RemoteSelect v-model="form.supplierId" resource-path="/suppliers" placeholder="选择供应商" /></el-form-item>
   <el-form-item v-else label="客户（必填）"><RemoteSelect v-model="form.customerId" resource-path="/customers" placeholder="选择客户" /></el-form-item>
   <el-form-item label="仓库（必填）"><RemoteSelect v-model="form.warehouseId" resource-path="/warehouses" placeholder="选择仓库" /></el-form-item>
   <div v-for="(item,index) in form.items" :key="index" class="line-editor">
    <strong>明细 {{index+1}}</strong>
    <el-form-item label="物料（必填）"><RemoteSelect v-model="item.materialId" resource-path="/materials" :params="{type:resource==='purchase'?'RAW_MATERIAL':'FINISHED_GOOD'}" :placeholder="`选择第${index+1}条物料`" /></el-form-item>
    <div class="line-numbers"><el-form-item label="数量（必填）"><el-input v-model="item.quantity" inputmode="numeric" :aria-label="`第${index+1}条数量`" placeholder="正整数" /></el-form-item><el-form-item label="人民币含税单价（必填）"><el-input v-model="item.unitPrice" inputmode="decimal" :aria-label="`第${index+1}条单价`" placeholder="最多两位小数" /></el-form-item></div>
    <el-button type="danger" link :disabled="form.items.length===1" @click="form.items.splice(index,1)">移除第{{index+1}}条</el-button>
   </div>
   <el-button :disabled="form.items.length>=100" @click="form.items.push({materialId:'',quantity:'',unitPrice:''})">添加明细</el-button>
  </template>
  <el-form-item label="备注"><el-input v-model="form.remark" type="textarea" maxlength="500" show-word-limit aria-label="备注" /></el-form-item>
  <div v-if="error" class="error-box" role="alert">{{error}}</div>
  <el-button type="primary" :loading="busy" @click="save">保存草稿</el-button><el-button :disabled="busy" @click="emit('cancel')">取消</el-button>
 </el-form>
</template>
<style scoped>.line-editor{border:1px solid #e2e8f0;padding:16px;border-radius:6px;margin:0 0 12px}.line-numbers{display:grid;grid-template-columns:1fr 1fr;gap:12px}</style>
