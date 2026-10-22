"""Deterministic Shanghai civil-date ranges; model output never supplies dates.

Pinned IANA Shanghai data also works on Windows without a system timezone DB.
Business query boundaries remain civil dates; Java uses ZoneId independently.
"""
import re
from datetime import date, datetime, timedelta
from pathlib import Path
from zoneinfo import ZoneInfo

with (Path(__file__).parent/'timezone_data/Shanghai.tzif').open('rb') as _zone_file:
    SHANGHAI = ZoneInfo.from_file(_zone_file, key='Asia/Shanghai')
DAY = timedelta(days=1)
DATE_TOKEN = re.compile(r'(?<!\d)(\d{4})(?:-(\d{1,2})-(\d{1,2})|年(\d{1,2})月(\d{1,2})日)(?!\d)')
MONTH_TOKEN = re.compile(r'(?<!\d)(\d{4})(?:-(\d{1,2})(?![-\d])|年(\d{1,2})月)(?!\d)')
RECENT = re.compile(r'(?:最近|近|过去)\s*(\d+)\s*天')
NATURAL = re.compile(r'今天|昨天|本周|上周|本月|上月')


def shanghai_day(value=None):
    value = value if value is not None else datetime.now(SHANGHAI)
    if isinstance(value, datetime):
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError('Clock datetime must have a timezone')
        return value.astimezone(SHANGHAI).date()
    if isinstance(value, date):
        return value
    raise ValueError('Clock must return a date or aware datetime')


def period(start, end):
    if not 0 < (end-start).days <= 366 or start.year < 1001:
        raise ValueError('Supported range is 1..366 days, year >= 1001')
    return {'startDate': str(start), 'endDate': str(end)}


def next_month(first):
    return date(first.year+1, 1, 1) if first.month == 12 else date(first.year, first.month+1, 1)


def parse_period(message, today):
    """None means no range given. Invalid/conflicting explicit input raises."""
    message = message.replace('上个月', '上月').replace('最近一个月', '最近30天')
    dates = list(DATE_TOKEN.finditer(message))
    remainder = DATE_TOKEN.sub('', message)
    months = list(MONTH_TOKEN.finditer(remainder))
    recent = list(RECENT.finditer(remainder))
    natural = list(NATURAL.finditer(remainder))
    if sum(bool(items) for items in (dates, months, recent, natural)) > 1:
        raise ValueError('Conflicting time ranges')
    if dates:
        if len(dates) > 2:
            raise ValueError('Only one continuous range is supported')
        values = [date(int(m[1]), int(m[2] or m[4]), int(m[3] or m[5])) for m in dates]
        if len(values) == 1 and re.search(r'至|到|~|～', remainder):
            raise ValueError('Range end is missing')
        return period(values[0], values[-1]+DAY)
    if months:
        if len(months) != 1:
            raise ValueError('Ambiguous month')
        match = months[0]
        start = date(int(match[1]), int(match[2] or match[3]), 1)
        end = today+DAY if start == today.replace(day=1) else next_month(start)
        return period(start, end)
    if recent:
        if len(recent) != 1:
            raise ValueError('Ambiguous recent range')
        days = int(recent[0][1])
        if not 1 <= days <= 366:
            raise ValueError('Recent day count must be 1..366')
        return period(today+DAY-timedelta(days=days), today+DAY)
    if natural:
        if len(natural) != 1:
            raise ValueError('Ambiguous natural range')
        term = natural[0][0]
        monday = today-timedelta(days=today.weekday())
        first = today.replace(day=1)
        start, end = {
            '今天': (today, today+DAY),
            '昨天': (today-DAY, today),
            '本周': (monday, today+DAY),
            '上周': (monday-timedelta(days=7), monday),
            '本月': (first, today+DAY),
            '上月': ((first-DAY).replace(day=1), first),
        }[term]
        return period(start, end)
    # Date-shaped but unrecognized input must not silently fall back to history.
    if re.search(r'\d{4}[-年]|\d+月|\d+天|前天|下周|下月', message):
        raise ValueError('Incomplete or unsupported date expression')
    return None


def resolve_period(message, resume, *, today=None):
    today = shanghai_day(today)
    try:
        selected = parse_period(message, today)
        if selected:
            return selected
        if resume.get('startDate') and resume.get('endDate'):
            return period(date.fromisoformat(resume['startDate']), date.fromisoformat(resume['endDate']))
        if resume.get('originalMessage'):
            return parse_period(resume['originalMessage'], today)
    except (OverflowError, TypeError) as exc:
        raise ValueError('Date outside supported range') from exc
    return None
