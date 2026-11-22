"""All provider values below are synthetic; no CLI/account/tool execution."""
import json

import pytest

from agent_monitor.provider_controls import normalize_request, UnsupportedControl
from agent_monitor.model_capabilities import normalize_catalog, selected_settings

SESSION = '12345678-1234-5678-9012-123456789abc'


def request(provider='codex', **changes):
    if provider == 'codex':
        value = {'id': 'r1', 'method': 'item/commandExecution/requestApproval', 'params': {
            'threadId': SESSION, 'turnId': 'turn1', 'itemId': 'item1', 'command': 'synthetic-operation', 'cwd': '/synthetic',
            'availableDecisions': ['accept', 'decline', 'cancel']}}
        value['params'].update(changes)
    else:
        value = {'type': 'control_request', 'request_id': 'r1', 'request': {'subtype': 'can_use_tool',
            'tool_name': 'SyntheticTool', 'tool_use_id': 'tool1', 'input': {'value': 'synthetic'}}}
        value['request'].update(changes)
    return value


def normalize(value, provider='codex', *, codex_local_command=False):
    return normalize_request(provider, value, command_id='command1', source_id=provider+':'+SESSION,
                             session_id=SESSION, turn_id='turn1', now=100, codex_local_command=codex_local_command)


@pytest.mark.parametrize('provider', ['codex', 'claude'])
def test_once_only_allow_and_deny(provider):
    item = normalize(request(provider), provider)
    wire = item.response({'decision': 'allow_once'})
    assert 'ForSession' not in json.dumps(wire) and 'updatedPermissions' not in json.dumps(wire)
    assert 'synthetic-operation' not in json.dumps(item.public['id'])
    deny = item.response({'decision': 'deny'})
    assert 'decline' in json.dumps(deny) or 'deny' in json.dumps(deny)
    with pytest.raises(UnsupportedControl): item.response({'decision': 'allow_session'})


@pytest.mark.parametrize('changes', [
    {'threadId': 'foreign'}, {'turnId': 'foreign'}, {'additionalPermissions': {'network': True}},
    {'networkApprovalContext': {'host': 'invalid.example'}}, {'grantRoot': '/unseen'},
    {'proposedExecpolicyAmendment': ['command']}, {'availableDecisions': ['acceptForSession', 'cancel']},
    {'availableDecisions': ['accept']}, {'unknownField': True}, {'command': ''}, {'cwd': None}])
def test_codex_unknown_scope_never_becomes_approval(changes):
    with pytest.raises(UnsupportedControl): normalize(request(**changes))


def test_codex_question_choices_exact_and_private_question_rejected():
    value = request()
    value['method'] = 'item/tool/requestUserInput'
    value['params'] = {'threadId': SESSION, 'turnId': 'turn1', 'itemId': 'item1', 'questions': [
        {'id': 'q1', 'header': 'Choice', 'question': 'Synthetic choice?', 'isOther': True, 'isSecret': False,
         'options': [{'label': 'First', 'description': 'One'}, {'label': 'Second', 'description': 'Two'}]}]}
    item = normalize(value)
    answer = item.response({'decision': 'answer', 'answers': [{'question_id': 'q1', 'choices': ['o1']}]})
    assert answer == {'id': 'r1', 'result': {'answers': {'q1': {'answers': ['Second']}}}}
    for answers in ([], [{'question_id': 'q1', 'choices': ['unknown']}],
                    [{'question_id': 'q1', 'choices': ['o1'], 'text': 'mixed'}]):
        with pytest.raises(UnsupportedControl): item.response({'decision': 'answer', 'answers': answers})
    value['params']['questions'][0]['isSecret'] = True
    with pytest.raises(UnsupportedControl): normalize(value)


@pytest.mark.parametrize('changes', [
    {'threadId': 'foreign'}, {'turnId': None}, {'turnId': 'previous'},
    {'mode': 'url', 'url': 'https://example.test/'}, {'mode': 'openai/form'},
    {'requestedSchema': {'type': 'object', 'properties': {'password': {'type': 'string'}}}},
])
def test_codex_mcp_does_not_expose_unbound_or_non_choice_forms(changes):
    value = {'id': 123, 'method': 'mcpServer/elicitation/request', 'params': {
        'threadId': SESSION, 'turnId': 'turn1', 'mode': 'form', 'serverName': 'test', 'message': 'Choose',
        'requestedSchema': {'type': 'object', 'properties': {'theme': {'type': 'string', 'enum': ['blue']}}},
        **changes}}
    with pytest.raises(UnsupportedControl):
        normalize(value)


def test_claude_question_restores_original_input_not_permission_updates():
    value = request('claude', tool_name='AskUserQuestion', input={'questions': [
        {'header': 'Choice', 'question': 'Synthetic choice?', 'multiSelect': True,
         'options': [{'label': 'First', 'description': '', 'preview': '<div>layout preview</div>'},
                     {'label': 'Second', 'description': ''}]}], 'metadata': {'source': 'synthetic-source'}})
    item = normalize(value, 'claude')
    assert [o.label for o in item.questions[0].options] == ['First', 'Second']
    assert 'synthetic-source' not in json.dumps(item.public)
    result = item.response({'decision': 'answer', 'answers': [{'question_id': 'q0', 'choices': ['o0', 'o1']}]})
    payload = result['response']['response']
    assert payload['updatedInput']['questions'] == value['request']['input']['questions']
    assert payload['updatedInput']['metadata'] == value['request']['input']['metadata']
    assert payload['updatedInput']['answers'] == {'Synthetic choice?': 'First, Second'}
    custom = item.response({'decision': 'answer', 'answers': [{'question_id': 'q0', 'text': 'My choice'}]})
    assert custom['response']['response']['updatedInput']['answers'] == {'Synthetic choice?': 'My choice'}
    assert 'updatedPermissions' not in payload


@pytest.mark.parametrize('changes', [{'agent_id': 'subagent'}, {'tool_use_id': None},
                                    {'subtype': 'hook_callback'}, {'unknown_field': True}])
def test_claude_unknown_requests_are_not_mobile_approvals(changes):
    with pytest.raises(UnsupportedControl): normalize(request('claude', **changes), 'claude')


@pytest.mark.parametrize('provider', ['codex', 'claude'])
def test_catalog_explicit_efforts_and_next_message_binding(provider):
    rows = ([{'model': 'synthetic-model', 'displayName': 'Synthetic model',
              'supportedReasoningEfforts': [{'reasoningEffort': 'high'}]}] if provider == 'codex' else
            [{'value': 'synthetic-model', 'displayName': 'Synthetic model', 'supportsEffort': True,
              'supportedEffortLevels': ['high']}])
    catalog = normalize_catalog(provider, rows, current_model='observed-model', now=100)
    choice = {'provider': provider, 'catalog_revision': catalog['catalog_revision'],
              'model': 'synthetic-model', 'effort': 'high', 'apply_at': 'next_message'}
    assert selected_settings(choice, catalog, provider=provider, session_id=SESSION, now=101) == {
        'model': 'synthetic-model', 'effort': 'high'}
    for changed in ({'effort': 'ultra'}, {'catalog_revision': 'foreign'}, {'apply_at': 'current_turn'}):
        with pytest.raises(ValueError): selected_settings({**choice, **changed}, catalog, provider=provider, session_id=SESSION, now=101)
    with pytest.raises(ValueError): selected_settings(choice, catalog, provider=provider, session_id=SESSION, now=221)
    assert catalog['current_model'] == 'observed-model'


def test_claude_omitted_capability_does_not_guess_effort_from_model_or_mode():
    catalog = normalize_catalog('claude', [{'value': 'synthetic-opus', 'displayName': 'Synthetic',
                                          'permissionMode': 'high'}], now=100)
    assert catalog['models'][0]['efforts'] == []


def test_catalog_drops_account_fields_and_rejects_ambiguous_ids():
    rows = [{'value': 'synthetic', 'displayName': 'Synthetic', 'account': 'PRIVATE'}]
    assert 'PRIVATE' not in json.dumps(normalize_catalog('claude', rows, now=100))
    with pytest.raises(ValueError): normalize_catalog('claude', rows * 2, now=100)


def test_current_codex_command_metadata_is_explicitly_understood():
    item = normalize(request(kind='command', startedAtMs=100, environmentId=None,
        approvalId='11111111-1111-4111-8111-111111111111', proposedNetworkPolicyAmendments=None))
    assert '/synthetic' in item.public['operation'] and 'synthetic-operation' in item.public['operation']
    for changes in ({'kind': 'writeStdin'}, {'environmentId': 'unseen-environment'},
                    {'startedAtMs': True}, {'proposedNetworkPolicyAmendments': []}, {'approvalId': 'unknown'}):
        with pytest.raises(UnsupportedControl): normalize(request(**changes))


def test_current_local_command_only_offers_literal_once_or_stop():
    proposal = ['synthetic-private-rule']
    value = request(environmentId='local', proposedExecpolicyAmendment=proposal, availableDecisions=[
        'accept', 'cancel', 'acceptForSession', {'acceptWithExecpolicyAmendment': {'execpolicy_amendment': proposal}}])
    with pytest.raises(UnsupportedControl): normalize(value)
    item = normalize(value, codex_local_command=True)
    assert '拒绝会停止本轮' in item.public['detail']
    assert 'synthetic-private-rule' not in json.dumps(item.public)
    assert 'proposedExecpolicyAmendment' not in item.original and 'availableDecisions' not in item.original
    assert item.response({'decision': 'allow_once', 'scope': 'session'}) == {'id': 'r1', 'result': {'decision': 'accept'}}
    assert item.response({'decision': 'deny'}) == {'id': 'r1', 'result': {'decision': 'cancel'}}
    with pytest.raises(UnsupportedControl): item.response({'decision': 'acceptWithExecpolicyAmendment'})


@pytest.mark.parametrize('changes', [
    {'environmentId': 'remote'}, {'additionalPermissions': {}}, {'grantRoot': '/synthetic'},
    {'networkApprovalContext': {}}, {'proposedNetworkPolicyAmendments': []},
    {'availableDecisions': ['accept', 'cancel', {'applyNetworkPolicyAmendment': {}}]},
    {'availableDecisions': ['accept', 'cancel', {'acceptWithExecpolicyAmendment': {'execpolicy_amendment': ['other-rule']}}]},
    {'availableDecisions': ['acceptForSession', 'cancel']},
    {'availableDecisions': ['accept', 'cancel', 'cancel']},
    {'availableDecisions': None}, {'proposedExecpolicyAmendment': [{'command': 'synthetic-rule'}]},
])
def test_verified_local_command_still_refuses_new_authority(changes):
    fields = {'environmentId': 'local', 'proposedExecpolicyAmendment': ['synthetic-rule'],
              'availableDecisions': ['accept', 'cancel']}
    with pytest.raises(UnsupportedControl):
        normalize(request(**{**fields, **changes}), codex_local_command=True)


def test_long_command_is_not_truncated_into_an_approvable_operation():
    with pytest.raises(UnsupportedControl): normalize(request(command='a'*8000, cwd='/synthetic'))


def test_mode_changing_claude_tools_stay_on_desktop():
    for name in ('ExitPlanMode', 'EnterPlanMode'):
        with pytest.raises(UnsupportedControl): normalize(request('claude', tool_name=name), 'claude')
