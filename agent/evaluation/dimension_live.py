"""Opt-in model-first HTTP scenarios with real Java/MySQL tools."""
import uuid
import re
from .dimension_checks import query,check_entity,check_ranking

async def scenarios(client,headers,create,ask,check):
    async def ranking(question,dimension):
        events=await ask(await create(),question)
        assert events[-1]==('done',{'status':'completed'}),'Ranking did not complete'
        value=next(v for k,v in events if k=='result')
        check_ranking(value['content'],'2026-09-01','2026-10-01',dimension)
    await check('dimension_customer_last_month',lambda:ranking('上个月销售额最高的客户有哪些？','customer'))
    await check('dimension_product_last_month',lambda:ranking('上个月商品销售排行','product'))
    async def product():
        cid=await create();events=await ask(cid,'标准办公台式机最近30天卖了多少？')
        if events[-1][1]['status']=='clarification':
            value=next(v for k,v in events if k=='clarification')
            assert value['context']['question']=='entity','Unexpected product clarification'
            candidates=re.findall(r'\bID\s+([1-9]\d*)',value['content']);assert candidates,'No test factory office PC candidate'
            # Explicit simulated human selection in the evaluator, never agent/model selection.
            events=await ask(cid,'选择商品ID '+candidates[0])
        assert events[-1]==('done',{'status':'completed'}),'Product scope did not complete'
        check_entity(next(v for k,v in events if k=='result'),'2026-09-21','2026-10-21','product')
    await check('dimension_named_office_pc',product)
    async def customer():
        id=query('SELECT id FROM customer ORDER BY id LIMIT 1')[0][0]
        cid=await create();events=await ask(cid,'某个客户最近一个月采购情况如何？')
        initial=next(v for k,v in events if k=='clarification')
        assert initial['context']['question']=='entity','Generic customer should ask identity'
        events=await ask(cid,'选择客户ID '+id)
        assert events[-1]==('done',{'status':'completed'}),'Customer scope did not complete'
        check_entity(next(v for k,v in events if k=='result'),'2026-09-21','2026-10-21','customer')
    await check('dimension_customer_month_resume',customer)
    async def duplicate():
        name='评测同名客户-'+uuid.uuid4().hex[:10];ids=[]
        for _ in range(2):
            response=await client.post('/api/v1/customers',headers=headers,json={'code':'EV'+uuid.uuid4().hex,'name':name,
                'contactName':'','phone':'','address':'','enabled':False})
            assert response.status_code==201,'Test customer fixture failed';ids.append(response.json()['id'])
        cid=await create();events=await ask(cid,'客户“'+name+'”最近30天销售')
        value=next(v for k,v in events if k=='clarification')
        assert set(re.findall(r'\bID\s+([1-9]\d*)',value['content']))==set(ids),'Duplicate candidates differ from backend IDs'
        events=await ask(cid,'选择客户ID 9223372036854775807')
        assert events[-1]==('done',{'status':'clarification'}),'Unreturned ID was not rejected'
        events=await ask(cid,'选择客户ID '+ids[1])
        assert events[-1]==('done',{'status':'completed'}),'Explicit duplicate selection did not resume'
        check_entity(next(v for k,v in events if k=='result'),'2026-09-21','2026-10-21','customer')
    await check('dimension_duplicate_disabled_revalidated',duplicate)
    async def no_match():
        events=await ask(await create(),'客户“%\' OR 1=1 --不存在”最近30天销售')
        value=next(v for k,v in events if k=='clarification')
        assert value['context']['question']=='entity' and not re.findall(r'\bID\s+([1-9]\d*)',value['content']),'Literal name widened candidate query'
        assert not any(k=='result' for k,v in events),'Unknown name produced report'
    await check('dimension_literal_unknown_name',no_match)
