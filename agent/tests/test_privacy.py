from app.privacy import redact_question

def test_obvious_sensitive_question_fields_are_masked():
    text='最近30天 13800138000 demo@example.org sk-THIS_IS_ONLY_A_FAKE_TEST_KEY'
    result=redact_question(text)
    assert result=='最近30天 [REDACTED_PHONE] [REDACTED_EMAIL] [REDACTED_KEY]'

def test_iso_period_and_business_request_are_preserved():
    text='分析2026-10-01至2026-10-19的全厂销售，最近30天'
    assert redact_question(text)==text
