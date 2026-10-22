<script setup lang="ts">
import {markdownBlocks} from '../api/ai'
defineProps<{content:string}>()
</script>
<template><div class="report-content"><template v-for="(block,index) in markdownBlocks(content)" :key="index"><div v-if="block.kind==='table'" class="report-table"><table><thead><tr><th v-for="(cell,c) in block.headers" :key="c">{{cell}}</th></tr></thead><tbody><tr v-for="(cells,r) in block.rows" :key="r"><td v-for="(cell,c) in cells" :key="c">{{cell}}</td></tr></tbody></table></div><h3 v-else-if="block.kind==='heading'">{{block.text}}</h3><p v-else>{{block.kind==='item'?'• ':''}}{{block.text}}</p></template></div></template>
<style scoped>
.report-content{min-width:0;overflow-wrap:anywhere;font-size:14px;line-height:1.85;color:var(--text-primary,#252a34)}
.report-content p{white-space:pre-wrap;margin:7px 0}.report-content p:empty{display:none}.report-content h3{font-size:14px;font-weight:600;margin:20px 0 10px}
.report-table{max-width:100%;overflow:auto;margin:16px 0;border:1px solid var(--line,#e6e8ed);border-radius:8px;scrollbar-width:thin}
.report-table table{border-collapse:collapse;width:100%;min-width:450px;background:white;font-variant-numeric:tabular-nums}
.report-table th,.report-table td{padding:11px 14px;border-bottom:1px solid var(--line,#e6e8ed);text-align:left;line-height:1.7;font-size:12px;vertical-align:top}
.report-table th{background:#f7f8fa;font-weight:500;color:#566176;white-space:nowrap}.report-table tbody tr:last-child td{border-bottom:0}.report-table tbody tr:hover{background:#fafbfc}
</style>
