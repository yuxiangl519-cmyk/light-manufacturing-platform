"""Per-request counts. Provider usage is optional, never estimated from text."""
from contextvars import ContextVar
from time import perf_counter

_current=ContextVar('analysis_execution',default=None)

class Scope:
    def __init__(self):self.token=_current.set({'start':perf_counter(),'toolCalls':0,'modelCalls':0,'usage':None,'usageReports':0})
    def close(self):_current.reset(self.token)

def begin():return Scope()
def record_tool():
    if (c:=_current.get()) is not None:c['toolCalls']+=1
def record_model():
    if (c:=_current.get()) is not None:c['modelCalls']+=1
def record_usage(raw):
    c=_current.get()
    if c is None or not isinstance(raw,dict):return
    values={k:v for k,v in raw.items() if k in ('prompt_tokens','completion_tokens','total_tokens') and type(v)==int and 0<=v<=1000000000}
    if values:
        c['usageReports']+=1
        if c['usage'] is None:c['usage']={}
        for k,v in values.items():c['usage'][k]=c['usage'].get(k,0)+v
def finish():
    c=_current.get()
    return {'toolCalls':c['toolCalls'],'modelCalls':c['modelCalls'],'elapsedMs':round((perf_counter()-c['start'])*1000),'usage':c['usage'],'usageReports':c['usageReports']}
